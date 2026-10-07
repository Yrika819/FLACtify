#pragma once

#include <cstdint>
#include <vector>

#include "FrameAdapter.h"
#include "PcmConverter.h"
#include "SpatialLimiter.h"
#include "SteamAudioRenderer.h"

namespace flactify::spatial {

class SpatialEngine final {
public:
    SpatialEngine(
        int sampleRateHz,
        int frameSize,
        int hrtfMixPercent,
        const uint8_t* sofaHrtfData,
        int sofaHrtfDataSize
    );

    int process(
        const uint8_t* input,
        int inputFrames,
        uint8_t* output,
        int outputCapacityFrames,
        PcmEncoding encoding,
        int* consumedFrames,
        int* producedFrames
    ) noexcept;
    int drainTail(
        uint8_t* output,
        int outputCapacityFrames,
        PcmEncoding encoding
    ) noexcept;
    void reset() noexcept;

private:
    static void processFrame(void* userData, const float* input, float* output) noexcept;
    void applyWetMixWithPendingDry(float* wetOutput) noexcept;
    int drainOutput(
        uint8_t* output,
        int outputOffsetFrames,
        int outputCapacityFrames,
        PcmEncoding encoding
    ) noexcept;

    int frameSize_;
    float wetMix_;
    SteamAudioRenderer renderer_;
    SpatialLimiter limiter_;
    FrameAdapter adapter_;
    std::vector<float> outputScratch_;
    std::vector<float> rendererScratch_;
    std::vector<float> tailScratch_;
    std::vector<float> dryPendingFrame_;
    int tailBufferOffsetFrames_ = 0;
    int tailBufferFrames_ = 0;
    bool tailDrainStarted_ = false;
    bool steamTailComplete_ = false;
    bool limiterTailDrained_ = false;
    bool hasPendingDryFrame_ = false;
};

}  // namespace flactify::spatial
