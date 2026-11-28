"""Synthetic numeric ReplayGain PCM-stage prototype.

This is deliberately host-side and not an Android/Media3 implementation.  The stream token is
mandatory so a caller cannot accidentally process a new item's PCM with the old item's gain.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Iterable, Sequence


class StreamNotActivated(RuntimeError):
    pass


@dataclass(frozen=True)
class PcmFormat:
    sample_rate_hz: int
    channels: int


@dataclass(frozen=True)
class PrototypeResult:
    samples: tuple[float, ...]
    bypass_reason: str | None = None


class PcmGainPrototype:
    """Minimal state machine for numeric safety properties, not a product audio processor."""

    def __init__(self, fade_frames: int = 64, ceiling: float = 1.0):
        if fade_frames < 0 or ceiling <= 0 or not math.isfinite(ceiling):
            raise ValueError("invalid fade/ceiling")
        self.fade_frames = fade_frames
        self.ceiling = ceiling
        self.format: PcmFormat | None = None
        self.stream_id: str | None = None
        self.gain = 0.0
        self.target_gain = 0.0
        self.fade_remaining = 0

    def configure(self, fmt: PcmFormat) -> None:
        if fmt.sample_rate_hz <= 0 or fmt.channels <= 0:
            raise ValueError("invalid PCM format")
        # A format change invalidates the item/gain association.  Silence is safer than stale gain.
        self.format = fmt
        self.stream_id = None
        self.gain = 0.0
        self.target_gain = 0.0
        self.fade_remaining = 0

    def activate(self, stream_id: str, gain_db: float, peak_linear: float | None = None) -> None:
        if self.format is None:
            raise RuntimeError("configure must precede activate")
        if not stream_id or not math.isfinite(gain_db):
            raise ValueError("invalid stream/gain")
        peak_headroom = None
        if peak_linear is not None and math.isfinite(peak_linear) and peak_linear > 0:
            peak_headroom = 1.0 / peak_linear
        requested = 10.0 ** (gain_db / 20.0)
        # Peak metadata is a guard, not a promise that the PCM is bounded. process() applies a
        # second observed-sample guard to protect against stale/inaccurate metadata.
        target = min(requested, peak_headroom) if peak_headroom is not None else requested
        self.stream_id = stream_id
        self.gain = 0.0
        self.target_gain = target
        self.fade_remaining = self.fade_frames

    def bypass(self, stream_id: str, reason: str) -> None:
        if self.format is None:
            raise RuntimeError("configure must precede bypass")
        if not stream_id or not reason:
            raise ValueError("invalid bypass state")
        self.stream_id = stream_id
        self.gain = 1.0
        self.target_gain = 1.0
        self.fade_remaining = 0

    def process(self, samples: Sequence[float], stream_id: str, bypass_reason: str | None = None) -> PrototypeResult:
        if self.format is None:
            raise RuntimeError("configure must precede process")
        if stream_id != self.stream_id:
            raise StreamNotActivated(f"PCM token {stream_id!r} does not match active stream")
        if len(samples) % self.format.channels:
            raise ValueError("PCM is not frame aligned")
        if any(not math.isfinite(value) for value in samples):
            raise ValueError("non-finite PCM must be rejected")
        if bypass_reason is not None:
            return PrototypeResult(tuple(samples), bypass_reason)

        output = []
        observed_peak = max((abs(value) for value in samples), default=0.0)
        observed_guard = self.ceiling / observed_peak if observed_peak > 0 else math.inf
        effective_target = min(self.target_gain, observed_guard)
        for frame_start in range(0, len(samples), self.format.channels):
            if self.fade_remaining:
                # Linear ramp is a deterministic click-suppression model; production needs a
                # sample-clock-owned ramp with the same stream-generation guarantee.
                completed = self.fade_frames - self.fade_remaining + 1
                self.gain = self.target_gain * (completed / self.fade_frames)
                self.fade_remaining -= 1
            else:
                self.gain = effective_target
            output.extend(value * self.gain for value in samples[frame_start:frame_start + self.format.channels])
        return PrototypeResult(tuple(output))


def spatial_or_direct_bypass(*, spatial_active: bool, direct_or_offload: bool) -> str | None:
    if spatial_active:
        return "SPATIAL_OUTPUT_BOUNDARY_NOT_IDENTITY"
    if direct_or_offload:
        return "DIRECT_OR_OFFLOAD_MAY_BYPASS_PCM"
    return None

