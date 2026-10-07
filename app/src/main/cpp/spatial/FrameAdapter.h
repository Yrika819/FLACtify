#pragma once

#include <cstddef>
#include <vector>

namespace flactify::spatial {

class FrameAdapter final {
public:
    using ProcessFrame = void (*)(void* userData, const float* input, float* output);

    FrameAdapter(int frameSize, int pendingOutputCapacityFrames);

    bool canAcceptInputFrame() const noexcept;
    void pushInputFrame(
        float left,
        float right,
        void* userData,
        ProcessFrame processFrame
    ) noexcept;
    int drain(float* interleavedOutput, int outputCapacityFrames) noexcept;
    void finish(void* userData, ProcessFrame processFrame) noexcept;
    void reset() noexcept;

    int frameSize() const noexcept { return frameSize_; }
    int pendingOutputFrames() const noexcept { return pendingOutputFrames_; }

private:
    void enqueueProcessedFrame() noexcept;

    const int frameSize_;
    const int pendingOutputCapacityFrames_;
    std::vector<float> inputFrame_;
    std::vector<float> processedFrame_;
    std::vector<float> pendingOutput_;
    int inputFrameCount_ = 0;
    int pendingOutputHeadFrame_ = 0;
    int pendingOutputFrames_ = 0;
};

}  // namespace flactify::spatial
