#include <jni.h>

#include <cstdint>
#include <exception>
#include <limits>
#include <string>

#include "SpatialEngine.h"

namespace {

using flactify::spatial::PcmEncoding;
using flactify::spatial::SpatialEngine;

bool isSupportedHrtfRate(jint sampleRateHz) {
    switch (sampleRateHz) {
        case 24'000:
        case 44'100:
        case 48'000:
        case 88'200:
        case 96'000:
        case 176'400:
        case 192'000:
            return true;
        default:
            return false;
    }
}

bool requiresSofaHrtf(jint sampleRateHz) {
    return sampleRateHz == 88'200 || sampleRateHz == 96'000 ||
        sampleRateHz == 176'400 || sampleRateHz == 192'000;
}

void throwJava(JNIEnv* env, const char* className, const char* message) {
    auto exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

SpatialEngine* engineFor(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        throwJava(env, "java/lang/IllegalStateException", "Spatial engine handle is closed");
        return nullptr;
    }
    return reinterpret_cast<SpatialEngine*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_flactify_audio_spatial_NativeSpatialEngine_nativeCreate(
    JNIEnv* env,
    jobject,
    jint sampleRateHz,
    jint frameSize,
    jint hrtfMixPercent,
    jobject sofaHrtfDataBuffer,
    jint sofaHrtfDataSize
) {
    if (hrtfMixPercent < 0 || hrtfMixPercent > 100) {
        throwJava(env, "java/lang/IllegalArgumentException", "HRTF mix percentage must be between 0 and 100");
        return 0;
    }
    if (!isSupportedHrtfRate(sampleRateHz)) {
        return 0;
    }
    if (frameSize <= 0 || frameSize > 2'048) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid spatial frame size");
        return 0;
    }

    const auto* sofaHrtfData = static_cast<const uint8_t*>(nullptr);
    if (requiresSofaHrtf(sampleRateHz)) {
        if (sofaHrtfDataBuffer == nullptr || sofaHrtfDataSize <= 0) {
            throwJava(env, "java/lang/IllegalArgumentException", "Pinned CIPIC SOFA data is required");
            return 0;
        }
        const auto capacity = env->GetDirectBufferCapacity(sofaHrtfDataBuffer);
        sofaHrtfData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(sofaHrtfDataBuffer));
        if (sofaHrtfData == nullptr || capacity < sofaHrtfDataSize) {
            throwJava(env, "java/lang/IllegalArgumentException", "CIPIC SOFA data must be a sufficiently large direct buffer");
            return 0;
        }
    }

    try {
        return static_cast<jlong>(reinterpret_cast<intptr_t>(
            new SpatialEngine(
                sampleRateHz,
                frameSize,
                hrtfMixPercent,
                sofaHrtfData,
                sofaHrtfDataSize
            )
        ));
    } catch (const std::exception& exception) {
        throwJava(env, "java/lang/IllegalStateException", exception.what());
        return 0;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown spatial engine initialization error");
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_flactify_audio_spatial_NativeSpatialEngine_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject inputBuffer,
    jint inputPosition,
    jint inputFrames,
    jobject outputBuffer,
    jint outputPosition,
    jint outputFrames,
    jint encodingValue
) {
    auto* engine = engineFor(env, handle);
    if (engine == nullptr) {
        return 0;
    }

    if (inputPosition < 0 || outputPosition < 0 || inputFrames < 0 || outputFrames < 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "Negative spatial buffer range");
        return 0;
    }
    if (encodingValue < 0 || encodingValue > 1) {
        throwJava(env, "java/lang/IllegalArgumentException", "Unsupported spatial PCM encoding");
        return 0;
    }

    auto* inputAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* outputAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    const auto inputCapacity = env->GetDirectBufferCapacity(inputBuffer);
    const auto outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    if (inputAddress == nullptr || outputAddress == nullptr || inputCapacity < 0 || outputCapacity < 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "Spatial PCM buffers must be direct");
        return 0;
    }

    const auto bytesPerFrame = encodingValue == 0 ? 4LL : 8LL;
    const auto inputBytes = static_cast<int64_t>(inputFrames) * bytesPerFrame;
    const auto outputBytes = static_cast<int64_t>(outputFrames) * bytesPerFrame;
    if (inputPosition + inputBytes > inputCapacity || outputPosition + outputBytes > outputCapacity) {
        throwJava(env, "java/lang/IllegalArgumentException", "Spatial PCM buffer range exceeds capacity");
        return 0;
    }

    auto* inputStart = inputAddress + inputPosition;
    auto* outputStart = outputAddress + outputPosition;
    const auto inputBegin = reinterpret_cast<uintptr_t>(inputStart);
    const auto inputEnd = inputBegin + static_cast<uintptr_t>(inputBytes);
    const auto outputBegin = reinterpret_cast<uintptr_t>(outputStart);
    const auto outputEnd = outputBegin + static_cast<uintptr_t>(outputBytes);
    if (inputBegin < outputEnd && outputBegin < inputEnd) {
        throwJava(env, "java/lang/IllegalArgumentException", "Spatial PCM input and output must not overlap");
        return 0;
    }

    int consumedFrames = 0;
    int producedFrames = 0;
    const auto result = engine->process(
        inputStart,
        inputFrames,
        outputStart,
        outputFrames,
        static_cast<PcmEncoding>(encodingValue),
        &consumedFrames,
        &producedFrames
    );
    if (result != 0) {
        throwJava(env, "java/lang/IllegalStateException", "Spatial engine processing failed");
        return 0;
    }

    return (static_cast<jlong>(static_cast<uint32_t>(consumedFrames)) << 32) |
        static_cast<jlong>(static_cast<uint32_t>(producedFrames));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_flactify_audio_spatial_NativeSpatialEngine_nativeDrainTail(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject outputBuffer,
    jint outputPosition,
    jint outputFrames,
    jint encodingValue
) {
    auto* engine = engineFor(env, handle);
    if (engine == nullptr) {
        return 0;
    }
    if (outputPosition < 0 || outputFrames < 0 || encodingValue < 0 || encodingValue > 1) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid spatial tail buffer range");
        return 0;
    }

    auto* outputAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    const auto outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    if (outputAddress == nullptr || outputCapacity < 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "Spatial tail buffer must be direct");
        return 0;
    }

    const auto bytesPerFrame = encodingValue == 0 ? 4LL : 8LL;
    const auto outputBytes = static_cast<int64_t>(outputFrames) * bytesPerFrame;
    if (outputPosition + outputBytes > outputCapacity) {
        throwJava(env, "java/lang/IllegalArgumentException", "Spatial tail buffer range exceeds capacity");
        return 0;
    }

    return engine->drainTail(
        outputAddress + outputPosition,
        outputFrames,
        static_cast<PcmEncoding>(encodingValue)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_flactify_audio_spatial_NativeSpatialEngine_nativeReset(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    if (auto* engine = engineFor(env, handle)) {
        engine->reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_flactify_audio_spatial_NativeSpatialEngine_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) {
        return;
    }
    auto* engine = reinterpret_cast<SpatialEngine*>(static_cast<intptr_t>(handle));
    delete engine;
}
