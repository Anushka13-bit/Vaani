#!/usr/bin/env python3
"""
KV-cache Whisper -> ONNX export, vendored from k2-fsa/sherpa-onnx.

Source: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/scripts/whisper/export-onnx.py
Pinned tag: v1.13.8 (see docs/SHERPA_ONNX_CONTRACT.md for why this tag).
License: Apache License 2.0 (sherpa-onnx is Apache-2.0; original file carries
"Copyright 2023 Xiaomi Corp. (authors: Fangjun Kuang)", itself adapted from
https://github.com/TadaoYamaoka/whisper/blob/main/to_onnx.py).

What changed vs. the upstream script, and why:
  - Upstream is a CLI that only accepts a fixed set of ``--model`` names and
    calls ``whisper.load_model(name)``, which either downloads an official
    OpenAI checkpoint or expects a pre-named ``.pt`` file on disk. We already
    have a fine-tuned model in memory (HF Whisper + merged LoRA, converted to
    OpenAI format by ``ml/hf_to_openai_whisper.py``), so the CLI/file-path
    plumbing (``get_args``, ``load_model``, the ``--model`` argparse choices)
    is dropped in favor of a single function, ``export_whisper_onnx(model,
    output_dir, ...)``, that takes an already-constructed
    ``whisper.model.Whisper`` instance directly.
  - Output file names are ours (``encoder.onnx``/``decoder.onnx``/``tokens.txt``
    plus ``.int8.onnx`` variants) rather than upstream's ``{model_name}-encoder.onnx``
    style, since we assemble our own mobile bundle and record the exact names
    in ``mobile_manifest.json`` (see docs/SHERPA_ONNX_CONTRACT.md).
  - The ``AudioEncoder.forward`` monkey-patch (upstream applies it at import
    time, globally and permanently) is scoped to a context manager here so it
    doesn't leak into other code running in the same process (e.g. the HF
    sanity-check path in export_whisper_mobile.py, which also imports
    ``whisper``-adjacent code).
  - The external-data path for "large"/"turbo" models (>2GB protobuf limit) is
    dropped — our base model is whisper-small, which never hits that limit —
    but the tensor names, shapes, dynamic axes, opset version, ONNX metadata
    keys, and int8 quantization args are unchanged from upstream, since those
    are exactly the contract sherpa-onnx's C++/Kotlin runtime expects.

Everything else — the tensor cache wrapper classes, the exact input/output
names, the ONNX metadata schema the C++ runtime reads at load time, and the
int8 quantization call — is copied as-is from the pinned tag so the graph
shape sherpa-onnx expects can't drift from what we produce.
"""
from __future__ import annotations

import logging
from contextlib import contextmanager
from pathlib import Path
from typing import Optional

import onnx
import torch
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic
from torch import Tensor, nn

import whisper
from whisper.model import AudioEncoder, MultiHeadAttention, ResidualAttentionBlock, TextDecoder, disable_sdpa

logger = logging.getLogger(__name__)

OPSET_VERSION = 17


# ---------------------------------------------------------------------------
# Vendored verbatim from sherpa-onnx scripts/whisper/export-onnx.py @ v1.13.8
# ---------------------------------------------------------------------------
def _modified_audio_encoder_forward(self: AudioEncoder, x: torch.Tensor):
    """
    x : torch.Tensor, shape = (batch_size, n_mels, n_ctx)
        the mel spectrogram of the audio

    Removes the exact-30s assertion so any T <= 30s is accepted (sherpa-onnx's
    runtime feeds however many frames the utterance actually has, not always
    a full padded 3000).
    """
    x = F.gelu(self.conv1(x))
    x = F.gelu(self.conv2(x))
    x = x.permute(0, 2, 1)

    assert (
        x.shape[2] == self.positional_embedding.shape[1]
    ), f"incorrect audio shape: {x.shape}, {self.positional_embedding.shape}"
    assert (
        x.shape[1] == self.positional_embedding.shape[0]
    ), f"incorrect audio shape: {x.shape}, {self.positional_embedding.shape}"
    x = (x + self.positional_embedding[: x.shape[1]]).to(x.dtype)

    for block in self.blocks:
        x = block(x)

    x = self.ln_post(x)
    return x


@contextmanager
def _patched_audio_encoder_forward():
    original = AudioEncoder.forward
    AudioEncoder.forward = _modified_audio_encoder_forward
    try:
        yield
    finally:
        AudioEncoder.forward = original


class AudioEncoderTensorCache(nn.Module):
    def __init__(self, inAudioEncoder: AudioEncoder, inTextDecoder: TextDecoder):
        super().__init__()
        self.audioEncoder = inAudioEncoder
        self.textDecoder = inTextDecoder

    def forward(self, x: Tensor):
        audio_features = self.audioEncoder(x)

        n_layer_cross_k_list = []
        n_layer_cross_v_list = []
        for block in self.textDecoder.blocks:
            n_layer_cross_k_list.append(block.cross_attn.key(audio_features))
            n_layer_cross_v_list.append(block.cross_attn.value(audio_features))

        return torch.stack(n_layer_cross_k_list), torch.stack(n_layer_cross_v_list)


class MultiHeadAttentionCross(nn.Module):
    def __init__(self, inMultiHeadAttention: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(self, x: Tensor, k: Tensor, v: Tensor, mask: Optional[Tensor] = None):
        q = self.multiHeadAttention.query(x)
        wv, qk = self.multiHeadAttention.qkv_attention(q, k, v, mask)
        return self.multiHeadAttention.out(wv)


class MultiHeadAttentionSelf(nn.Module):
    def __init__(self, inMultiHeadAttention: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(
        self,
        x: Tensor,  # (b, n_ctx      , n_state)
        k_cache: Tensor,  # (b, n_ctx_cache, n_state)
        v_cache: Tensor,  # (b, n_ctx_cache, n_state)
        mask: Tensor,
    ):
        q = self.multiHeadAttention.query(x)  # (b, n_ctx, n_state)
        k = self.multiHeadAttention.key(x)  # (b, n_ctx, n_state)
        v = self.multiHeadAttention.value(x)  # (b, n_ctx, n_state)

        k_cache[:, -k.shape[1] :, :] = k  # (b, n_ctx_cache + n_ctx, n_state)
        v_cache[:, -v.shape[1] :, :] = v  # (b, n_ctx_cache + n_ctx, n_state)

        wv, qk = self.multiHeadAttention.qkv_attention(q, k_cache, v_cache, mask)
        return self.multiHeadAttention.out(wv), k_cache, v_cache


class ResidualAttentionBlockTensorCache(nn.Module):
    def __init__(self, inResidualAttentionBlock: ResidualAttentionBlock):
        super().__init__()
        self.originalBlock = inResidualAttentionBlock
        self.attn = MultiHeadAttentionSelf(inResidualAttentionBlock.attn)
        self.cross_attn = (
            MultiHeadAttentionCross(inResidualAttentionBlock.cross_attn)
            if inResidualAttentionBlock.cross_attn
            else None
        )

    def forward(
        self,
        x: Tensor,
        self_k_cache: Tensor,
        self_v_cache: Tensor,
        cross_k: Tensor,
        cross_v: Tensor,
        mask: Tensor,
    ):
        self_attn_x, self_k_cache_updated, self_v_cache_updated = self.attn(
            self.originalBlock.attn_ln(x), self_k_cache, self_v_cache, mask=mask
        )
        x = x + self_attn_x

        if self.cross_attn:
            x = x + self.cross_attn(self.originalBlock.cross_attn_ln(x), cross_k, cross_v)

        x = x + self.originalBlock.mlp(self.originalBlock.mlp_ln(x))
        return x, self_k_cache_updated, self_v_cache_updated


class TextDecoderTensorCache(nn.Module):
    def __init__(self, inTextDecoder: TextDecoder, in_n_ctx: int):
        super().__init__()
        self.textDecoder = inTextDecoder
        self.n_ctx = in_n_ctx

        self.blocks = []
        for orginal_block in self.textDecoder.blocks:
            self.blocks.append(ResidualAttentionBlockTensorCache(orginal_block))

    def forward(
        self,
        tokens: Tensor,
        n_layer_self_k_cache: Tensor,
        n_layer_self_v_cache: Tensor,
        n_layer_cross_k: Tensor,
        n_layer_cross_v: Tensor,
        offset: Tensor,
    ):
        x = (
            self.textDecoder.token_embedding(tokens)
            + self.textDecoder.positional_embedding[offset[0] : offset[0] + tokens.shape[-1]]
        )
        x = x.to(n_layer_cross_k[0].dtype)

        i = 0
        for block in self.blocks:
            self_k_cache = n_layer_self_k_cache[i, :, : offset[0] + tokens.shape[-1], :]
            self_v_cache = n_layer_self_v_cache[i, :, : offset[0] + tokens.shape[-1], :]
            x, self_k_cache, self_v_cache = block(
                x,
                self_k_cache=self_k_cache,
                self_v_cache=self_v_cache,
                cross_k=n_layer_cross_k[i],
                cross_v=n_layer_cross_v[i],
                mask=self.textDecoder.mask,
            )
            n_layer_self_k_cache[i, :, : offset[0] + tokens.shape[-1], :] = self_k_cache
            n_layer_self_v_cache[i, :, : offset[0] + tokens.shape[-1], :] = self_v_cache
            i += 1

        x = self.textDecoder.ln(x)

        logits = (
            torch.matmul(
                self.textDecoder.token_embedding.weight.to(x.dtype),
                x.permute(0, 2, 1),
            )
            .permute(0, 2, 1)
            .float()
        )

        return logits, n_layer_self_k_cache, n_layer_self_v_cache


def _add_onnx_metadata(filename: str, meta_data: dict) -> None:
    model = onnx.load(filename)
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, filename)


def _write_tokens_txt(model: "whisper.model.Whisper", output_path: Path) -> None:
    """
    Copy openai-whisper's own bundled BPE vocab file verbatim, in the exact
    "<token> <rank>\\n" line format sherpa-onnx's tokenizer parses — this is
    NOT derived from our fine-tuned model or the HF tokenizer; fine-tuning
    (LoRA merge) never adds or removes vocabulary, so the stock vocab file
    that ships inside the installed `openai-whisper` package is authoritative,
    exactly as sherpa-onnx's own convert_tokens() does it.
    """
    whisper_dir = Path(whisper.__file__).parent
    multilingual = model.is_multilingual
    tokenizer_file = whisper_dir / "assets" / ("multilingual.tiktoken" if multilingual else "gpt2.tiktoken")
    if not tokenizer_file.is_file():
        raise FileNotFoundError(f"Cannot find {tokenizer_file}")

    contents = tokenizer_file.read_text(encoding="utf-8")
    tokens = {
        token: int(rank)
        for token, rank in (line.split() for line in contents.splitlines() if line)
    }
    with output_path.open("w", encoding="utf-8") as f:
        for t, i in tokens.items():
            f.write(f"{t} {i}\n")


def export_whisper_onnx(
    model: "whisper.model.Whisper",
    output_dir: Path,
    model_type: str,
    quantize: bool = True,
) -> dict[str, Path]:
    """
    Export an in-memory OpenAI-format Whisper model to sherpa-onnx's KV-cache
    encoder/decoder ONNX pair, plus tokens.txt.

    Returns a dict of the produced file paths, keyed
    "encoder"/"decoder"/"encoder_int8"/"decoder_int8"/"tokens".
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    model = model.eval()

    # torch's scaled_dot_product_attention path in whisper.model.MultiHeadAttention
    # can't trace an `is_causal` argument that is a Tensor rather than a bool (see
    # https://github.com/k2-fsa/sherpa-onnx/issues/1764, which upstream's own
    # `if __name__ == "__main__":` block works around the same way).
    with torch.no_grad(), _patched_audio_encoder_forward(), disable_sdpa():
        tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual, num_languages=model.num_languages)

        audio = torch.rand(16000 * 2)
        audio = whisper.pad_or_trim(audio)
        assert audio.shape == (16000 * 30,), audio.shape
        mel = whisper.log_mel_spectrogram(audio, n_mels=model.dims.n_mels).unsqueeze(0)
        batch_size = 1
        assert mel.shape == (batch_size, model.dims.n_mels, 30 * 100), mel.shape

        encoder = AudioEncoderTensorCache(model.encoder, model.decoder)
        n_layer_cross_k, n_layer_cross_v = encoder(mel)
        assert n_layer_cross_k.shape == (
            model.dims.n_text_layer, batch_size, model.dims.n_audio_ctx, model.dims.n_text_state,
        )
        assert n_layer_cross_v.shape == n_layer_cross_k.shape

        encoder_path = output_dir / "encoder.onnx"
        torch.onnx.export(
            encoder,
            mel,
            str(encoder_path),
            opset_version=OPSET_VERSION,
            input_names=["mel"],
            output_names=["n_layer_cross_k", "n_layer_cross_v"],
            dynamic_axes={
                "mel": {0: "n_audio", 2: "T"},
                "n_layer_cross_k": {1: "n_audio", 2: "T"},
                "n_layer_cross_v": {1: "n_audio", 2: "T"},
            },
            # torch>=2.5's default exporter is the dynamo/onnxscript-based one,
            # which needs the optional `onnxscript` package and doesn't accept
            # `dynamic_axes` the way this vendored (pre-dynamo-era) script
            # expects. dynamo=False forces the legacy TorchScript-based
            # exporter, matching what sherpa-onnx's own script relies on.
            dynamo=False,
        )

        encoder_meta_data = {
            "model_type": model_type,
            "version": "1",
            "maintainer": "k2-fsa",
            "n_mels": model.dims.n_mels,
            "n_audio_ctx": model.dims.n_audio_ctx,
            "n_audio_state": model.dims.n_audio_state,
            "n_audio_head": model.dims.n_audio_head,
            "n_audio_layer": model.dims.n_audio_layer,
            "n_vocab": model.dims.n_vocab,
            "n_text_ctx": model.dims.n_text_ctx,
            "n_text_state": model.dims.n_text_state,
            "n_text_head": model.dims.n_text_head,
            "n_text_layer": model.dims.n_text_layer,
            "sot_sequence": ",".join(map(str, tokenizer.sot_sequence)),
            "all_language_tokens": ",".join(map(str, tokenizer.all_language_tokens)),
            "all_language_codes": ",".join(tokenizer.all_language_codes),
            "sot": tokenizer.sot,
            "sot_index": tokenizer.sot_sequence.index(tokenizer.sot),
            "eot": tokenizer.eot,
            "blank_id": tokenizer.encode(" ")[0],
            "is_multilingual": int(model.is_multilingual),
            "no_speech": tokenizer.no_speech,
            "non_speech_tokens": ",".join(map(str, tokenizer.non_speech_tokens)),
            "transcribe": tokenizer.transcribe,
            "translate": tokenizer.translate,
            "sot_prev": tokenizer.sot_prev,
            "sot_lm": tokenizer.sot_lm,
            "no_timestamps": tokenizer.no_timestamps,
        }
        _add_onnx_metadata(str(encoder_path), encoder_meta_data)
        logger.info("Wrote %s with embedded ONNX metadata (sherpa-onnx's runtime reads config from this, not our JSON manifest)", encoder_path.name)

        n_audio = mel.shape[0]
        tokens = torch.tensor([[tokenizer.sot, tokenizer.sot, tokenizer.sot]] * n_audio)
        decoder = TextDecoderTensorCache(model.decoder, model.dims.n_text_ctx)
        n_layer_self_k_cache = torch.zeros(
            (len(model.decoder.blocks), n_audio, model.dims.n_text_ctx, model.dims.n_text_state)
        )
        n_layer_self_v_cache = torch.zeros(
            (len(model.decoder.blocks), n_audio, model.dims.n_text_ctx, model.dims.n_text_state)
        )
        offset = torch.zeros(1, dtype=torch.int64)
        logits, n_layer_self_k_cache, n_layer_self_v_cache = decoder(
            tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset,
        )

        offset = torch.tensor([tokens.shape[1]], dtype=torch.int64)
        tokens = torch.tensor([[tokenizer.sot]] * n_audio)
        decoder(tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset)

        decoder_path = output_dir / "decoder.onnx"
        torch.onnx.export(
            decoder,
            (tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset),
            str(decoder_path),
            opset_version=OPSET_VERSION,
            input_names=[
                "tokens", "in_n_layer_self_k_cache", "in_n_layer_self_v_cache",
                "n_layer_cross_k", "n_layer_cross_v", "offset",
            ],
            output_names=["logits", "out_n_layer_self_k_cache", "out_n_layer_self_v_cache"],
            dynamic_axes={
                "tokens": {0: "n_audio", 1: "n_tokens"},
                "in_n_layer_self_k_cache": {1: "n_audio"},
                "in_n_layer_self_v_cache": {1: "n_audio"},
                "n_layer_cross_k": {1: "n_audio", 2: "T"},
                "n_layer_cross_v": {1: "n_audio", 2: "T"},
            },
            dynamo=False,
        )
        logger.info("Wrote %s", decoder_path.name)

        tokens_path = output_dir / "tokens.txt"
        _write_tokens_txt(model, tokens_path)
        logger.info("Wrote %s", tokens_path.name)

    result = {"encoder": encoder_path, "decoder": decoder_path, "tokens": tokens_path}

    if quantize:
        encoder_int8 = output_dir / "encoder.int8.onnx"
        quantize_dynamic(
            model_input=str(encoder_path),
            model_output=str(encoder_int8),
            op_types_to_quantize=["MatMul"],
            weight_type=QuantType.QInt8,
        )
        decoder_int8 = output_dir / "decoder.int8.onnx"
        quantize_dynamic(
            model_input=str(decoder_path),
            model_output=str(decoder_int8),
            op_types_to_quantize=["MatMul"],
            weight_type=QuantType.QInt8,
        )
        logger.info("Wrote %s and %s (int8 dynamic quantized)", encoder_int8.name, decoder_int8.name)
        result["encoder_int8"] = encoder_int8
        result["decoder_int8"] = decoder_int8

    return result
