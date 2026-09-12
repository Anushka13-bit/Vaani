#!/usr/bin/env python3
"""
Convert a merged HuggingFace ``WhisperForConditionalGeneration`` checkpoint into
the native OpenAI ``openai-whisper`` checkpoint format.

Why this exists: sherpa-onnx's exporter (``scripts/whisper/export-onnx.py`` in
k2-fsa/sherpa-onnx) loads models exclusively via ``whisper.load_model()`` from
the ``openai-whisper`` pip package, which expects a checkpoint shaped like
``{"dims": {...}, "model_state_dict": {...}}`` and a module tree that names
things ``blocks``/``attn``/``query``/``key``/``value``/``out`` etc. Our training
pipeline produces a merged HF ``transformers`` model (LoRA baked in via
``peft.PeftModel.merge_and_unload()``), which uses different names
(``layers``/``self_attn``/``q_proj``/``k_proj``/``v_proj``/``out_proj``) and a
different module nesting (``model.encoder``/``model.decoder`` vs.
``encoder``/``decoder``). This module bridges the two formats in-memory — no
files touch disk — so the rest of the sherpa-onnx export logic can run
unmodified against a model built entirely from our fine-tuned weights.

The key-rename table below is the exact *inverse* of HuggingFace's own
OpenAI -> HF converter (``WHISPER_MAPPING`` in
``transformers/models/whisper/convert_openai_to_hf.py``, fetched from
huggingface/transformers @ main while writing this), not a guess: every
HF key pattern below is one of that table's *values*, mapped back to its key.
``merge_and_unload()`` folds LoRA into the existing weight matrices without
adding new parameters, so the mapping is plain Whisper-to-Whisper regardless
of whether an adapter was ever applied.

Validated by ``ml/verify_hf_to_openai_conversion.py``: encoder outputs and
teacher-forced decoder logits from the converted model match the original HF
model to float32 tolerance on a real sample clip, and greedy-decoded text is
identical.
"""
from __future__ import annotations

import re
from typing import Any

import torch

# HF module-tree key pattern -> OpenAI module-tree key pattern.
# Order matters only in that both patterns are unambiguous substrings on their
# own side (mirroring how HF's own WHISPER_MAPPING avoids collisions by always
# including the surrounding dots), so a single pass of "does this HF key
# contain this pattern" per key is enough — no cascading needed.
_LAYER_NORM_AND_STRUCTURE = [
    (r"^encoder\.conv1\.", "encoder.conv1."),
    (r"^encoder\.conv2\.", "encoder.conv2."),
    (r"^encoder\.embed_positions\.weight$", "encoder.positional_embedding"),
    (r"^encoder\.layer_norm\.", "encoder.ln_post."),
    (r"^decoder\.embed_tokens\.weight$", "decoder.token_embedding.weight"),
    (r"^decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
    (r"^decoder\.layer_norm\.", "decoder.ln."),
]

# Per-transformer-block sub-module renames, applied after the block index is
# extracted. Left side is the HF suffix (after "encoder.layers.{i}." or
# "decoder.layers.{i}."), right side is the OpenAI suffix (after
# "encoder.blocks.{i}." or "decoder.blocks.{i}.").
_BLOCK_SUBMODULE_MAP = [
    ("self_attn.q_proj", "attn.query"),
    ("self_attn.k_proj", "attn.key"),
    ("self_attn.v_proj", "attn.value"),
    ("self_attn.out_proj", "attn.out"),
    ("self_attn_layer_norm", "attn_ln"),
    ("encoder_attn.q_proj", "cross_attn.query"),
    ("encoder_attn.k_proj", "cross_attn.key"),
    ("encoder_attn.v_proj", "cross_attn.value"),
    ("encoder_attn.out_proj", "cross_attn.out"),
    ("encoder_attn_layer_norm", "cross_attn_ln"),
    ("fc1", "mlp.0"),
    ("fc2", "mlp.2"),
    ("final_layer_norm", "mlp_ln"),
]

_BLOCK_KEY_RE = re.compile(r"^(encoder|decoder)\.layers\.(\d+)\.(.+)$")


def _convert_key(hf_key: str) -> str | None:
    """Map one HF WhisperModel state_dict key to its OpenAI Whisper equivalent.

    Returns None for keys that have no OpenAI counterpart (e.g. buffers HF
    doesn't have, or the tied ``proj_out.weight`` at the outer
    WhisperForConditionalGeneration level — OpenAI's TextDecoder has no
    separate output-projection parameter; it reuses token_embedding.weight).
    """
    block_match = _BLOCK_KEY_RE.match(hf_key)
    if block_match:
        section, idx, rest = block_match.groups()
        for hf_sub, openai_sub in _BLOCK_SUBMODULE_MAP:
            if rest.startswith(hf_sub + "."):
                tail = rest[len(hf_sub) + 1 :]  # "weight" or "bias"
                return f"{section}.blocks.{idx}.{openai_sub}.{tail}"
        return None

    for pattern, replacement_prefix in _LAYER_NORM_AND_STRUCTURE:
        m = re.match(pattern, hf_key)
        if m:
            if hf_key.endswith(".weight") or hf_key.endswith(".bias"):
                if replacement_prefix.endswith("."):
                    tail = hf_key.rsplit(".", 1)[-1]
                    return replacement_prefix + tail
                return replacement_prefix  # exact single-tensor rename
            return replacement_prefix
    return None


def hf_state_dict_to_openai(hf_model) -> dict[str, torch.Tensor]:
    """
    Convert ``hf_model.model.state_dict()`` (the inner WhisperModel, i.e. minus
    the outer ``proj_out``) into an OpenAI ``whisper.model.Whisper``-shaped
    state dict.
    """
    inner_state_dict = hf_model.model.state_dict()
    openai_state_dict: dict[str, torch.Tensor] = {}
    unmapped: list[str] = []
    for hf_key, tensor in inner_state_dict.items():
        openai_key = _convert_key(hf_key)
        if openai_key is None:
            unmapped.append(hf_key)
            continue
        openai_state_dict[openai_key] = tensor.detach().clone()

    if unmapped:
        raise ValueError(
            "hf_to_openai_whisper: no mapping found for HF state_dict key(s): "
            f"{unmapped}. The HF Whisper module tree may have changed since this "
            "converter was written against transformers/models/whisper/convert_openai_to_hf.py."
        )
    return openai_state_dict


def hf_config_to_openai_dims(hf_model) -> dict[str, int]:
    """
    Derive OpenAI's ``ModelDimensions`` fields from the HF model config.
    Every field is read from ``config`` — never hardcoded to whisper-small's
    numbers — so this keeps working if the base model changes (e.g. to
    whisper-medium or a 128-mel-bin large-v3 variant).
    """
    cfg = hf_model.config
    return {
        "n_mels": int(cfg.num_mel_bins),
        "n_audio_ctx": int(cfg.max_source_positions),
        "n_audio_state": int(cfg.d_model),
        "n_audio_head": int(cfg.encoder_attention_heads),
        "n_audio_layer": int(cfg.encoder_layers),
        "n_vocab": int(cfg.vocab_size),
        "n_text_ctx": int(cfg.max_target_positions),
        "n_text_state": int(cfg.d_model),
        "n_text_head": int(cfg.decoder_attention_heads),
        "n_text_layer": int(cfg.decoder_layers),
    }


def convert_hf_to_openai_whisper(hf_model) -> "whisper.model.Whisper":  # noqa: F821
    """
    Build an in-memory ``openai-whisper`` ``Whisper`` model from a merged HF
    ``WhisperForConditionalGeneration`` instance. No files are written; the
    returned model is ready to feed straight into sherpa-onnx's export logic.
    """
    import whisper
    from whisper.model import ModelDimensions, Whisper

    hf_model.eval()
    dims_dict = hf_config_to_openai_dims(hf_model)
    state_dict = hf_state_dict_to_openai(hf_model)

    dims = ModelDimensions(**dims_dict)
    openai_model = Whisper(dims)
    missing, unexpected = openai_model.load_state_dict(state_dict, strict=False)
    # alignment_heads / decoder.mask are non-persistent buffers on the OpenAI
    # side (never present in any state_dict, ours included) — anything else
    # missing or unexpected means the conversion silently dropped a tensor.
    allowed_missing = {"alignment_heads"}
    bad_missing = [k for k in missing if k not in allowed_missing]
    if bad_missing or unexpected:
        raise ValueError(
            f"hf_to_openai_whisper: state_dict mismatch after conversion. "
            f"missing={bad_missing} unexpected={unexpected}"
        )
    openai_model.eval()
    return openai_model


__all__ = [
    "convert_hf_to_openai_whisper",
    "hf_config_to_openai_dims",
    "hf_state_dict_to_openai",
]
