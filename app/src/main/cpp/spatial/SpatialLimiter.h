#pragma once

#include <vector>

namespace flactify::spatial {

class SpatialLimiter final {
public:
    SpatialLimiter(int sampleRateHz, int frameSize);

    SpatialLimiter(const SpatialLimiter&) = delete;
    SpatialLimiter& operator=(const SpatialLimiter&) = delete;

    void processFrame(const float* input, float* output) noexcept;
    bool drainFrame(float* output) noexcept;
    void reset() noexcept;

private:
    void applyPending(float* output, float lookaheadPeak) noexcept;
    static float peak(const float* interleaved, int samples) noexcept;

    int frameSize_;
    float releaseFactorPerSample_;
    std::vector<float> pendingFrame_;
    bool hasPendingFrame_ = false;
    float gain_ = 1.0f;
};

}  // namespace flactify::spatial
