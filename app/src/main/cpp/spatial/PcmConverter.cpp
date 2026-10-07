#include "PcmConverter.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace flactify::spatial {

float PcmConverter::decodeSample(const uint8_t* input, PcmEncoding encoding) noexcept {
    if (encoding == PcmEncoding::Pcm16) {
        int16_t sample = 0;
        std::memcpy(&sample, input, sizeof(sample));
        return static_cast<float>(sample) / 32768.0f;
    }

    float sample = 0.0f;
    std::memcpy(&sample, input, sizeof(sample));
    return std::isfinite(sample) ? sample : 0.0f;
}

void PcmConverter::encodeSample(float sample, PcmEncoding encoding, uint8_t* output) noexcept {
    if (!std::isfinite(sample)) {
        sample = 0.0f;
    }

    if (encoding == PcmEncoding::Pcm16) {
        const auto scaled = std::lrintf(sample * 32768.0f);
        const auto clipped = std::clamp<long>(scaled, -32768L, 32767L);
        const auto pcm16 = static_cast<int16_t>(clipped);
        std::memcpy(output, &pcm16, sizeof(pcm16));
        return;
    }

    std::memcpy(output, &sample, sizeof(sample));
}

}  // namespace flactify::spatial
