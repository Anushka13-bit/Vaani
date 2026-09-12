#!/usr/bin/env python3
"""
Transcription quality metrics.

Deliberately dependency-free and torch-free so the scoring logic can be unit
tested on any machine, including CI without a model runtime. evaluate_adapter.py
imports torch lazily and only for inference.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

_PUNCT = re.compile(r"[^\w\s']", flags=re.UNICODE)
_WS = re.compile(r"\s+")


def normalize(text: str) -> str:
    """
    Casing and punctuation are not errors for this use case — Whisper emits
    "Open settings." where the reference says "open settings", and scoring that
    as a substitution would drown out the differences that actually matter.
    """
    text = unicodedata.normalize("NFKC", text).lower().strip()
    text = _PUNCT.sub(" ", text)
    return _WS.sub(" ", text).strip()


def _levenshtein(ref: list, hyp: list) -> tuple[int, int, int, int]:
    """Returns (distance, substitutions, deletions, insertions)."""
    n, m = len(ref), len(hyp)
    if n == 0:
        return m, 0, 0, m
    # (cost, sub, del, ins) per cell; two rows is enough.
    prev = [(j, 0, 0, j) for j in range(m + 1)]
    for i in range(1, n + 1):
        cur = [(i, 0, i, 0)] + [(0, 0, 0, 0)] * m
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                cur[j] = prev[j - 1]
                continue
            sub_c, del_c, ins_c = prev[j - 1][0], prev[j][0], cur[j - 1][0]
            best = min(sub_c, del_c, ins_c)
            if best == sub_c:
                c, s, d, i_ = prev[j - 1]
                cur[j] = (c + 1, s + 1, d, i_)
            elif best == del_c:
                c, s, d, i_ = prev[j]
                cur[j] = (c + 1, s, d + 1, i_)
            else:
                c, s, d, i_ = cur[j - 1]
                cur[j] = (c + 1, s, d, i_ + 1)
        prev = cur
    return prev[m]


@dataclass
class ErrorRate:
    rate: float
    errors: int
    length: int
    substitutions: int = 0
    deletions: int = 0
    insertions: int = 0


def word_error_rate(reference: str, hypothesis: str) -> ErrorRate:
    ref = normalize(reference).split()
    hyp = normalize(hypothesis).split()
    dist, sub, dele, ins = _levenshtein(ref, hyp)
    # An empty reference cannot have a meaningful rate; report 0/1 by convention
    # so a bad sample cannot silently divide by zero and poison an average.
    rate = dist / len(ref) if ref else (1.0 if hyp else 0.0)
    return ErrorRate(rate, dist, len(ref), sub, dele, ins)


def character_error_rate(reference: str, hypothesis: str) -> ErrorRate:
    ref = list(normalize(reference).replace(" ", ""))
    hyp = list(normalize(hypothesis).replace(" ", ""))
    dist, sub, dele, ins = _levenshtein(ref, hyp)
    rate = dist / len(ref) if ref else (1.0 if hyp else 0.0)
    return ErrorRate(rate, dist, len(ref), sub, dele, ins)


def corpus_error_rate(pairs: list[tuple[str, str]], char: bool = False) -> ErrorRate:
    """
    Pooled rate over all samples: total errors / total reference length.

    Pooling rather than averaging per-sample rates, because averaging lets one
    short utterance with a single wrong word (WER 1.0) outweigh a long one that
    was almost perfect.
    """
    fn = character_error_rate if char else word_error_rate
    tot_err = tot_len = tot_s = tot_d = tot_i = 0
    for reference, hypothesis in pairs:
        r = fn(reference, hypothesis)
        tot_err += r.errors
        tot_len += r.length
        tot_s += r.substitutions
        tot_d += r.deletions
        tot_i += r.insertions
    return ErrorRate(
        rate=tot_err / tot_len if tot_len else 0.0,
        errors=tot_err, length=tot_len,
        substitutions=tot_s, deletions=tot_d, insertions=tot_i,
    )
