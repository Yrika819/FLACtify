#pragma once

#include <cstdint>

namespace flactify::spatial {

enum class PcmEncoding : int {
    Pcm16 = 0,
    Float32 = 1,
};

class PcmConverter final {
public:
    static float decodeSample(const uint8_t* input, PcmEncoding encoding) noexcept;
    static void encodeSample(float sample, PcmEncoding encoding, uint8_t* output) noexcept;
};

}  // namespace flactify::spatial
