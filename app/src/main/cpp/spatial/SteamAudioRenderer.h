#pragma once

#include <cstdint>
#include <vector>

#include "phonon.h"

namespace flactify::spatial {

class SteamAudioRenderer final {
public:
    SteamAudioRenderer(
        int sampleRateHz,
        int frameSize,
        const uint8_t* sofaHrtfData,
        int sofaHrtfDataSize
    );
    ~SteamAudioRenderer();

    SteamAudioRenderer(const SteamAudioRenderer&) = delete;
    SteamAudioRenderer& operator=(const SteamAudioRenderer&) = delete;

    void processFrame(const float* interleavedInput, float* interleavedOutput) noexcept;
    int drainTailFrame(float* interleavedOutput, bool* tailComplete) noexcept;
    void reset() noexcept;

private:
    void release() noexcept;

    int frameSize_;
    IPLContext context_ = nullptr;
    IPLHRTF hrtf_ = nullptr;
    IPLVirtualSurroundEffect effect_ = nullptr;
    IPLVector3 speakerDirections_[2]{};
    std::vector<IPLfloat32> inputLeft_;
    std::vector<IPLfloat32> inputRight_;
    std::vector<IPLfloat32> outputLeft_;
    std::vector<IPLfloat32> outputRight_;
    IPLfloat32* inputData_[2]{};
    IPLfloat32* outputData_[2]{};
    IPLAudioBuffer inputBuffer_{};
    IPLAudioBuffer outputBuffer_{};
    IPLVirtualSurroundEffectParams effectParams_{};
};

}  // namespace flactify::spatial
