#include "SpatialLimiter.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace flactify::spatial {
namespace {

constexpr float kSamplePeakCeiling = 0.85f;
constexpr float kReleaseMilliseconds = 80.0f;

}  // namespace

SpatialLimiter::SpatialLimiter(int sampleRateHz, int frameSize)
    : frameSize_(frameSize),
      releaseFactorPerSample_(std::exp(
          -1.0f / (static_cast<float>(sampleRateHz) * kReleaseMilliseconds * 0.001f)
      )),
      pendingFrame_(static_cast<std::size_t>(frameSize) * 2, 0.0f) {}

void SpatialLimiter::processFrame(const float* input, float* output) noexcept {
    if (!hasPendingFrame_) {
        std::fill(output, output + static_cast<std::size_t>(frameSize_) * 2, 0.0f);
        std::copy(input, input + static_cast<std::size_t>(frameSize_) * 2, pendingFrame_.begin());
        hasPendingFrame_ = true;
        return;
    }

    const auto lookaheadPeak = std::max(
        peak(pendingFrame_.data(), frameSize_ * 2),
        peak(input, frameSize_ * 2)
    );
    applyPending(output, lookaheadPeak);
    std::copy(input, input + static_cast<std::size_t>(frameSize_) * 2, pendingFrame_.begin());
}

bool SpatialLimiter::drainFrame(float* output) noexcept {
    if (!hasPendingFrame_) {
        return false;
    }

    applyPending(output, peak(pendingFrame_.data(), frameSize_ * 2));
    hasPendingFrame_ = false;
    return true;
}

void SpatialLimiter::reset() noexcept {
    hasPendingFrame_ = false;
    gain_ = 1.0f;
    std::fill(pendingFrame_.begin(), pendingFrame_.end(), 0.0f);
}

void SpatialLimiter::applyPending(float* output, float lookaheadPeak) noexcept {
    auto targetGain = 1.0f;
    if (lookaheadPeak > kSamplePeakCeiling) {
        targetGain = kSamplePeakCeiling / lookaheadPeak;
    }
    gain_ = std::min(gain_, targetGain);
    for (int frame = 0; frame < frameSize_; ++frame) {
        const auto sampleOffset = static_cast<std::size_t>(frame) * 2;
        output[sampleOffset] = pendingFrame_[sampleOffset] * gain_;
        output[sampleOffset + 1] = pendingFrame_[sampleOffset + 1] * gain_;
        if (gain_ < targetGain) {
            gain_ += (targetGain - gain_) * (1.0f - releaseFactorPerSample_);
        }
    }
}

float SpatialLimiter::peak(const float* interleaved, int samples) noexcept {
    auto value = 0.0f;
    for (int sample = 0; sample < samples; ++sample) {
        if (std::isfinite(interleaved[sample])) {
            value = std::max(value, std::abs(interleaved[sample]));
        }
    }
    return value;
}

}  // namespace flactify::spatial
