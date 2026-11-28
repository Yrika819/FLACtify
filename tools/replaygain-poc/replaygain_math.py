"""ReplayGain/R128 tag parsing and clipping-safe gain math.

This module is deliberately framework-free and is not part of the Android app.  It models
metadata diagnostics only; it never decodes, rewrites, or plays audio.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
import re
from typing import Mapping


_DB_RE = re.compile(r"^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*(?:dB)?$", re.IGNORECASE)
_PEAK_RE = re.compile(r"^([+]?(?:\d+(?:\.\d*)?|\.\d+))$")
_R128_RE = re.compile(r"^([+-]?\d+)$")


@dataclass(frozen=True)
class ReplayGainTags:
    """Validated ReplayGain 1.0 fields, in dB and linear peak units."""

    track_gain_db: float | None = None
    album_gain_db: float | None = None
    track_peak: float | None = None
    album_peak: float | None = None
    reference_loudness_db: float | None = None


def _finite(value: float) -> float | None:
    return value if math.isfinite(value) else None


def parse_gain_db(value: str) -> float | None:
    """Parse a ReplayGain dB value, accepting the conventional optional ``dB`` suffix."""

    match = _DB_RE.fullmatch(value.strip())
    if not match:
        return None
    parsed = _finite(float(match.group(1)))
    # A tag outside this broad range is almost certainly malformed, not a useful gain.
    return parsed if parsed is not None and -120.0 <= parsed <= 120.0 else None


def parse_peak(value: str) -> float | None:
    """Parse a ReplayGain peak ratio (not dBFS); values above 1.0 are retained."""

    match = _PEAK_RE.fullmatch(value.strip())
    if not match:
        return None
    parsed = _finite(float(match.group(1)))
    return parsed if parsed is not None and parsed >= 0.0 else None


def parse_r128_track_gain(value: str) -> float | None:
    """Parse Opus ``R128_TRACK_GAIN`` Q7.8 signed integer into dB.

    R128 metadata is not ReplayGain: RFC 7845 defines this as a signed 16-bit integer in
    1/256 dB units relative to the R128 target (-23 LUFS).  A decimal or ``dB`` suffix is
    intentionally rejected so the two conventions cannot be silently mixed.
    """

    match = _R128_RE.fullmatch(value.strip())
    if not match:
        return None
    raw = int(match.group(1))
    if not -32768 <= raw <= 32767:
        return None
    return raw / 256.0


def parse_tags(tags: Mapping[str, str]) -> ReplayGainTags:
    """Parse known ReplayGain 1.0 keys, case-insensitively, without guessing missing fields."""

    normalized = {key.strip().upper(): value for key, value in tags.items()}
    return ReplayGainTags(
        track_gain_db=parse_gain_db(normalized["REPLAYGAIN_TRACK_GAIN"])
        if "REPLAYGAIN_TRACK_GAIN" in normalized
        else None,
        album_gain_db=parse_gain_db(normalized["REPLAYGAIN_ALBUM_GAIN"])
        if "REPLAYGAIN_ALBUM_GAIN" in normalized
        else None,
        track_peak=parse_peak(normalized["REPLAYGAIN_TRACK_PEAK"])
        if "REPLAYGAIN_TRACK_PEAK" in normalized
        else None,
        album_peak=parse_peak(normalized["REPLAYGAIN_ALBUM_PEAK"])
        if "REPLAYGAIN_ALBUM_PEAK" in normalized
        else None,
        reference_loudness_db=parse_gain_db(normalized["REPLAYGAIN_REFERENCE_LOUDNESS"])
        if "REPLAYGAIN_REFERENCE_LOUDNESS" in normalized
        else None,
    )


def requested_gain_db(tags: ReplayGainTags, album: bool = False) -> float | None:
    """Return album gain when requested and present, otherwise track gain, without fallback guesses."""

    if album and tags.album_gain_db is not None:
        return tags.album_gain_db
    return tags.track_gain_db


def clipping_safe_gain_db(
    requested_db: float,
    sample_peak: float | None = None,
    true_peak: float | None = None,
) -> float:
    """Clamp positive gain against the best known peak, preserving attenuation.

    ``sample_peak`` and ``true_peak`` are linear full-scale ratios.  True peak wins when it is
    finite and positive; sample peak is only a fallback.  With no usable peak, the requested
    value is returned but callers should surface that the clip guard is unavailable.
    """

    if not math.isfinite(requested_db):
        raise ValueError("requested gain must be finite")
    peak = true_peak if true_peak is not None and math.isfinite(true_peak) and true_peak > 0 else sample_peak
    if peak is None or not math.isfinite(peak) or peak <= 0:
        return requested_db
    headroom_db = -20.0 * math.log10(peak)
    return min(requested_db, headroom_db)

