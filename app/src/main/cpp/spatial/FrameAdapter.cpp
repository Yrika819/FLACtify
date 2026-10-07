#include "FrameAdapter.h"

#include <algorithm>

namespace flactify::spatial {

FrameAdapter::FrameAdapter(int frameSize, int pendingOutputCapacityFrames)
    : frameSize_(frameSize),
      pendingOutputCapacityFrames_(pendingOutputCapacityFrames),
      inputFrame_(static_cast<size_t>(frameSize) * 2),
      processedFrame_(static_cast<size_t>(frameSize) * 2),
      pendingOutput_(static_cast<size_t>(pendingOutputCapacityFrames) * 2) {}

bool FrameAdapter::canAcceptInputFrame() const noexcept {
    return pendingOutputFrames_ <= pendingOutputCapacityFrames_ - frameSize_;
}

void FrameAdapter::pushInputFrame(
    float left,
    float right,
    void* userData,
    ProcessFrame processFrame
) noexcept {
    const auto sampleOffset = static_cast<size_t>(inputFrameCount_) * 2;
    inputFrame_[sampleOffset] = left;
    inputFrame_[sampleOffset + 1] = right;
    ++inputFrameCount_;

    if (inputFrameCount_ == frameSize_) {
        processFrame(userData, inputFrame_.data(), processedFrame_.data());
        enqueueProcessedFrame();
        inputFrameCount_ = 0;
    }
}

void FrameAdapter::enqueueProcessedFrame() noexcept {
    const auto tailFrame =
        (pendingOutputHeadFrame_ + pendingOutputFrames_) % pendingOutputCapacityFrames_;
    for (int frame = 0; frame < frameSize_; ++frame) {
        const auto sourceSample = static_cast<size_t>(frame) * 2;
        const auto destinationFrame = (tailFrame + frame) % pendingOutputCapacityFrames_;
        const auto destinationSample = static_cast<size_t>(destinationFrame) * 2;
        pendingOutput_[destinationSample] = processedFrame_[sourceSample];
        pendingOutput_[destinationSample + 1] = processedFrame_[sourceSample + 1];
    }
    pendingOutputFrames_ += frameSize_;
}

int FrameAdapter::drain(float* interleavedOutput, int outputCapacityFrames) noexcept {
    const auto framesToCopy = std::min(outputCapacityFrames, pendingOutputFrames_);
    for (int frame = 0; frame < framesToCopy; ++frame) {
        const auto sourceFrame = (pendingOutputHeadFrame_ + frame) % pendingOutputCapacityFrames_;
        const auto sourceSample = static_cast<size_t>(sourceFrame) * 2;
        const auto destinationSample = static_cast<size_t>(frame) * 2;
        interleavedOutput[destinationSample] = pendingOutput_[sourceSample];
        interleavedOutput[destinationSample + 1] = pendingOutput_[sourceSample + 1];
    }

    pendingOutputHeadFrame_ =
        (pendingOutputHeadFrame_ + framesToCopy) % pendingOutputCapacityFrames_;
    pendingOutputFrames_ -= framesToCopy;
    return framesToCopy;
}

void FrameAdapter::finish(void* userData, ProcessFrame processFrame) noexcept {
    if (inputFrameCount_ == 0) {
        return;
    }
    for (int frame = inputFrameCount_; frame < frameSize_; ++frame) {
        const auto sampleOffset = static_cast<size_t>(frame) * 2;
        inputFrame_[sampleOffset] = 0.0f;
        inputFrame_[sampleOffset + 1] = 0.0f;
    }
    processFrame(userData, inputFrame_.data(), processedFrame_.data());
    enqueueProcessedFrame();
    inputFrameCount_ = 0;
}

void FrameAdapter::reset() noexcept {
    inputFrameCount_ = 0;
    pendingOutputHeadFrame_ = 0;
    pendingOutputFrames_ = 0;
}

}  // namespace flactify::spatial
