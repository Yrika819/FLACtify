#include "SteamAudioRenderer.h"

#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

#include "phonon_version.h"

namespace flactify::spatial {
namespace {

bool usesBuiltInHrtf(int sampleRateHz) {
    return sampleRateHz == 24'000 || sampleRateHz == 44'100 || sampleRateHz == 48'000;
}

bool usesSofaHrtf(int sampleRateHz) {
    return sampleRateHz == 88'200 || sampleRateHz == 96'000 ||
        sampleRateHz == 176'400 || sampleRateHz == 192'000;
}

void checkSteamAudio(IPLerror error, const char* operation) {
    if (error != IPL_STATUS_SUCCESS) {
        throw std::runtime_error(
            std::string("Steam Audio 4.8.1 failed to ") + operation +
            " (status " + std::to_string(static_cast<int>(error)) + ")"
        );
    }
}

}  // namespace

SteamAudioRenderer::SteamAudioRenderer(
    int sampleRateHz,
    int frameSize,
    const uint8_t* sofaHrtfData,
    int sofaHrtfDataSize
)
    : frameSize_(frameSize) {
    const auto useSofaHrtf = usesSofaHrtf(sampleRateHz);
    if (!usesBuiltInHrtf(sampleRateHz) && !useSofaHrtf) {
        throw std::invalid_argument("Steam Audio HRTF data is unavailable at this sample rate");
    }
    if (useSofaHrtf && (sofaHrtfData == nullptr || sofaHrtfDataSize <= 0)) {
        throw std::invalid_argument("Pinned CIPIC SOFA data is required at this sample rate");
    }
    if (frameSize_ <= 0) {
        throw std::invalid_argument("Steam Audio frame size must be positive");
    }

    try {
        IPLContextSettings contextSettings{};
        contextSettings.version = STEAMAUDIO_VERSION;
        checkSteamAudio(iplContextCreate(&contextSettings, &context_), "create its context");

        IPLAudioSettings audioSettings{};
        audioSettings.samplingRate = sampleRateHz;
        audioSettings.frameSize = frameSize_;

        IPLHRTFSettings hrtfSettings{};
        hrtfSettings.type = useSofaHrtf ? IPL_HRTFTYPE_SOFA : IPL_HRTFTYPE_DEFAULT;
        // Pixel measurements with Steam Audio 4.8.1 show its rate-adapted SOFA path
        // scales a fixed-input broadband signal approximately with the target rate.
        // Normalize that measured rate gain to the built-in 48 kHz reference.
        constexpr float kSofaReferenceRateHz = 48'000.0f;
        hrtfSettings.volume = useSofaHrtf
            ? kSofaReferenceRateHz / static_cast<float>(sampleRateHz)
            : 1.0f;
        hrtfSettings.normType = IPL_HRTFNORMTYPE_RMS;
        if (useSofaHrtf) {
            hrtfSettings.sofaData = sofaHrtfData;
            hrtfSettings.sofaDataSize = sofaHrtfDataSize;
        }
        checkSteamAudio(
            iplHRTFCreate(context_, &audioSettings, &hrtfSettings, &hrtf_),
            useSofaHrtf ? "create the resampled CIPIC SOFA HRTF" : "create the default HRTF"
        );

        speakerDirections_[0] = {-0.5f, 0.0f, -0.8660254f};
        speakerDirections_[1] = {0.5f, 0.0f, -0.8660254f};

        IPLSpeakerLayout speakerLayout{};
        speakerLayout.type = IPL_SPEAKERLAYOUTTYPE_CUSTOM;
        speakerLayout.numSpeakers = 2;
        speakerLayout.speakers = speakerDirections_;

        IPLVirtualSurroundEffectSettings effectSettings{};
        effectSettings.speakerLayout = speakerLayout;
        effectSettings.hrtf = hrtf_;
        checkSteamAudio(
            iplVirtualSurroundEffectCreate(context_, &audioSettings, &effectSettings, &effect_),
            "create the stereo virtual-surround effect"
        );

        inputLeft_.resize(static_cast<size_t>(frameSize_));
        inputRight_.resize(static_cast<size_t>(frameSize_));
        outputLeft_.resize(static_cast<size_t>(frameSize_));
        outputRight_.resize(static_cast<size_t>(frameSize_));

        inputData_[0] = inputLeft_.data();
        inputData_[1] = inputRight_.data();
        outputData_[0] = outputLeft_.data();
        outputData_[1] = outputRight_.data();

        inputBuffer_.numChannels = 2;
        inputBuffer_.numSamples = frameSize_;
        inputBuffer_.data = inputData_;
        outputBuffer_.numChannels = 2;
        outputBuffer_.numSamples = frameSize_;
        outputBuffer_.data = outputData_;

        effectParams_.hrtf = hrtf_;
    } catch (...) {
        release();
        throw;
    }
}

SteamAudioRenderer::~SteamAudioRenderer() {
    release();
}

void SteamAudioRenderer::processFrame(
    const float* interleavedInput,
    float* interleavedOutput
) noexcept {
    for (int frame = 0; frame < frameSize_; ++frame) {
        const auto sampleOffset = static_cast<size_t>(frame) * 2;
        inputData_[0][frame] = interleavedInput[sampleOffset];
        inputData_[1][frame] = interleavedInput[sampleOffset + 1];
    }

    iplVirtualSurroundEffectApply(effect_, &effectParams_, &inputBuffer_, &outputBuffer_);

    for (int frame = 0; frame < frameSize_; ++frame) {
        const auto sampleOffset = static_cast<size_t>(frame) * 2;
        interleavedOutput[sampleOffset] = outputData_[0][frame];
        interleavedOutput[sampleOffset + 1] = outputData_[1][frame];
    }
}

int SteamAudioRenderer::drainTailFrame(
    float* interleavedOutput,
    bool* tailComplete
) noexcept {
    const auto framesRemaining = iplVirtualSurroundEffectGetTailSize(effect_);
    if (framesRemaining <= 0) {
        *tailComplete = true;
        return 0;
    }

    const auto state = iplVirtualSurroundEffectGetTail(effect_, &outputBuffer_);
    const auto framesToEmit = std::min(framesRemaining, frameSize_);
    for (int frame = 0; frame < framesToEmit; ++frame) {
        const auto sampleOffset = static_cast<size_t>(frame) * 2;
        interleavedOutput[sampleOffset] = outputData_[0][frame];
        interleavedOutput[sampleOffset + 1] = outputData_[1][frame];
    }
    *tailComplete = state == IPL_AUDIOEFFECTSTATE_TAILCOMPLETE;
    return framesToEmit;
}

void SteamAudioRenderer::reset() noexcept {
    if (effect_ != nullptr) {
        iplVirtualSurroundEffectReset(effect_);
    }
}

void SteamAudioRenderer::release() noexcept {
    if (effect_ != nullptr) {
        iplVirtualSurroundEffectRelease(&effect_);
    }
    if (hrtf_ != nullptr) {
        iplHRTFRelease(&hrtf_);
    }
    if (context_ != nullptr) {
        iplContextRelease(&context_);
    }
}

}  // namespace flactify::spatial
