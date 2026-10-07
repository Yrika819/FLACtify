#include "SpatialEngine.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace flactify::spatial {
namespace {

constexpr int kPendingOutputFramesPerBlock = 4;

float normalizedWetMix(int percent) {
    if (percent < 0 || percent > 100) {
        throw std::invalid_argument("HRTF mix percentage must be between 0 and 100");
    }
    return static_cast<float>(percent) / 100.0f;
}

}  // namespace

SpatialEngine::SpatialEngine(
    int sampleRateHz,
    int frameSize,
    int hrtfMixPercent,
    const uint8_t* sofaHrtfData,
    int sofaHrtfDataSize
)
    : frameSize_(frameSize),
      wetMix_(normalizedWetMix(hrtfMixPercent)),
      renderer_(sampleRateHz, frameSize, sofaHrtfData, sofaHrtfDataSize),
      limiter_(sampleRateHz, frameSize),
      adapter_(frameSize, frameSize * kPendingOutputFramesPerBlock),
      outputScratch_(static_cast<size_t>(frameSize * kPendingOutputFramesPerBlock) * 2),
      rendererScratch_(static_cast<size_t>(frameSize) * 2),
      tailScratch_(static_cast<size_t>(frameSize) * 2),
      dryPendingFrame_(static_cast<size_t>(frameSize) * 2) {}

int SpatialEngine::process(
    const uint8_t* input,
    int inputFrames,
    uint8_t* output,
    int outputCapacityFrames,
    PcmEncoding encoding,
    int* consumedFrames,
    int* producedFrames
) noexcept {
    *consumedFrames = 0;
    *producedFrames = 0;
    if (tailDrainStarted_) {
        return -1;
    }

    *producedFrames += drainOutput(output, 0, outputCapacityFrames, encoding);
    while (*consumedFrames < inputFrames && adapter_.canAcceptInputFrame()) {
        const auto inputSampleOffset = static_cast<size_t>(*consumedFrames) * 2;
        const auto bytesPerSample = encoding == PcmEncoding::Pcm16 ? sizeof(int16_t) : sizeof(float);
        const auto* inputFrame = input + inputSampleOffset * bytesPerSample;
        const auto left = PcmConverter::decodeSample(inputFrame, encoding);
        const auto right = PcmConverter::decodeSample(inputFrame + bytesPerSample, encoding);
        adapter_.pushInputFrame(left, right, this, &SpatialEngine::processFrame);
        ++*consumedFrames;

        const auto remainingOutputFrames = outputCapacityFrames - *producedFrames;
        *producedFrames += drainOutput(output, *producedFrames, remainingOutputFrames, encoding);
    }
    return 0;
}

int SpatialEngine::drainTail(
    uint8_t* output,
    int outputCapacityFrames,
    PcmEncoding encoding
) noexcept {
    int producedFrames = 0;
    if (!tailDrainStarted_) {
        producedFrames += drainOutput(output, 0, outputCapacityFrames, encoding);
        if (adapter_.pendingOutputFrames() != 0) {
            return producedFrames;
        }
        adapter_.finish(this, &SpatialEngine::processFrame);
        tailDrainStarted_ = true;
    }

    producedFrames += drainOutput(
        output,
        producedFrames,
        outputCapacityFrames - producedFrames,
        encoding
    );

    while (producedFrames < outputCapacityFrames) {
        if (tailBufferOffsetFrames_ < tailBufferFrames_) {
            const auto framesToCopy = std::min(
                tailBufferFrames_ - tailBufferOffsetFrames_,
                outputCapacityFrames - producedFrames
            );
            const auto bytesPerSample =
                encoding == PcmEncoding::Pcm16 ? sizeof(int16_t) : sizeof(float);
            const auto outputSampleOffset = static_cast<size_t>(producedFrames) * 2;
            auto* destination = output + outputSampleOffset * bytesPerSample;
            for (int frame = 0; frame < framesToCopy; ++frame) {
                const auto sourceSample =
                    static_cast<size_t>(tailBufferOffsetFrames_ + frame) * 2;
                const auto destinationSample = static_cast<size_t>(frame) * 2;
                PcmConverter::encodeSample(
                    tailScratch_[sourceSample],
                    encoding,
                    destination + destinationSample * bytesPerSample
                );
                PcmConverter::encodeSample(
                    tailScratch_[sourceSample + 1],
                    encoding,
                    destination + (destinationSample + 1) * bytesPerSample
                );
            }
            tailBufferOffsetFrames_ += framesToCopy;
            producedFrames += framesToCopy;
            continue;
        }

        if (!steamTailComplete_) {
            std::fill(rendererScratch_.begin(), rendererScratch_.end(), 0.0f);
            const auto hrtfTailFrames =
                renderer_.drainTailFrame(rendererScratch_.data(), &steamTailComplete_);
            if (hrtfTailFrames > 0) {
                limiter_.processFrame(rendererScratch_.data(), tailScratch_.data());
                applyWetMixWithPendingDry(tailScratch_.data());
                tailBufferOffsetFrames_ = 0;
                tailBufferFrames_ = frameSize_;
                continue;
            }
            if (!steamTailComplete_) {
                steamTailComplete_ = true;
            }
        }

        if (steamTailComplete_ && !limiterTailDrained_) {
            limiterTailDrained_ = true;
            if (limiter_.drainFrame(tailScratch_.data())) {
                applyWetMixWithPendingDry(tailScratch_.data());
                tailBufferOffsetFrames_ = 0;
                tailBufferFrames_ = frameSize_;
                continue;
            }
        }
        break;
    }
    return producedFrames;
}

void SpatialEngine::reset() noexcept {
    adapter_.reset();
    renderer_.reset();
    limiter_.reset();
    tailBufferOffsetFrames_ = 0;
    tailBufferFrames_ = 0;
    tailDrainStarted_ = false;
    steamTailComplete_ = false;
    limiterTailDrained_ = false;
    hasPendingDryFrame_ = false;
    std::fill(dryPendingFrame_.begin(), dryPendingFrame_.end(), 0.0f);
}

void SpatialEngine::processFrame(void* userData, const float* input, float* output) noexcept {
    auto* engine = static_cast<SpatialEngine*>(userData);
    engine->renderer_.processFrame(input, engine->rendererScratch_.data());
    engine->limiter_.processFrame(engine->rendererScratch_.data(), output);
    engine->applyWetMixWithPendingDry(output);
    std::copy(
        input,
        input + static_cast<size_t>(engine->frameSize_) * 2,
        engine->dryPendingFrame_.begin()
    );
    engine->hasPendingDryFrame_ = true;
}

void SpatialEngine::applyWetMixWithPendingDry(float* wetOutput) noexcept {
    const auto dryGain = hasPendingDryFrame_ ? 1.0f - wetMix_ : 0.0f;
    for (int sample = 0; sample < frameSize_ * 2; ++sample) {
        const auto index = static_cast<size_t>(sample);
        const auto drySample = hasPendingDryFrame_ ? dryPendingFrame_[index] : 0.0f;
        wetOutput[index] = drySample * dryGain + wetOutput[index] * wetMix_;
    }
    hasPendingDryFrame_ = false;
}

int SpatialEngine::drainOutput(
    uint8_t* output,
    int outputOffsetFrames,
    int outputCapacityFrames,
    PcmEncoding encoding
) noexcept {
    const auto scratchCapacityFrames = static_cast<int>(outputScratch_.size() / 2);
    const auto frameCount = std::min(
        {outputCapacityFrames, adapter_.pendingOutputFrames(), scratchCapacityFrames}
    );
    if (frameCount <= 0) {
        return 0;
    }

    adapter_.drain(outputScratch_.data(), frameCount);
    const auto bytesPerSample = encoding == PcmEncoding::Pcm16 ? sizeof(int16_t) : sizeof(float);
    const auto outputSampleOffset = static_cast<size_t>(outputOffsetFrames) * 2;
    auto* destination = output + outputSampleOffset * bytesPerSample;
    for (int frame = 0; frame < frameCount; ++frame) {
        const auto sampleOffset = static_cast<size_t>(frame) * 2;
        PcmConverter::encodeSample(
            outputScratch_[sampleOffset],
            encoding,
            destination + sampleOffset * bytesPerSample
        );
        PcmConverter::encodeSample(
            outputScratch_[sampleOffset + 1],
            encoding,
            destination + (sampleOffset + 1) * bytesPerSample
        );
    }
    return frameCount;
}

}  // namespace flactify::spatial
