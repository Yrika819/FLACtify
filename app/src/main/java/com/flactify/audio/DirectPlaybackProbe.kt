package com.flactify.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioProfile
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Thin wrapper over the API 33+ direct-playback queries.
 *
 * Every Android call lives here; the interpretation of the results lives in
 * [DirectPlaybackClassifier] so it stays unit-testable on the JVM.
 *
 * Important: this class reports *capability only*. It cannot tell whether FLACtify is currently
 * using a direct path, because the platform offers no public API that answers that. Nothing in
 * the UI may claim "direct playback active" or "bit-perfect" on the strength of this probe.
 */
class DirectPlaybackProbe(private val context: Context) {

    /** True when the platform is new enough to answer the question at all. */
    val isPlatformSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Probes direct playback for [sampleRateHz] / [channelCount].
     *
     * Both parameters are required. A direct-playback answer is only meaningful for a concrete
     * (rate, channel layout) pair, so when either is unknown the probe is skipped and the
     * capability is reported as unknown rather than assumed to be stereo at some default rate.
     *
     * Blocking; call from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun probe(sampleRateHz: Int?, channelCount: Int?): DirectPlaybackCapability {
        // Guarded by an explicit SDK_INT check so that API 26-32 devices take the unknown path
        // and never touch a method that does not exist on their platform.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DirectPlaybackCapability.unknown("Android 13未満のため照会不可")
        }
        if (sampleRateHz == null || channelCount == null) {
            return DirectPlaybackCapability.unknown("サンプリングレートまたはチャンネル数が不明")
        }
        // A channel count with no canonical output layout must not be probed as stereo, or the
        // displayed condition would not describe the AudioFormat actually sent to AudioPolicy.
        val channelMask = DirectPlaybackClassifier.channelMaskFor(channelCount)
            ?: return DirectPlaybackCapability.unknown("$channelCount ch に対応する標準出力レイアウトなし")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DirectPlaybackCapability.unknown("AudioManagerを取得できません")
        return probeOnApi33(audioManager, sampleRateHz, channelCount, channelMask)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probeOnApi33(
        audioManager: AudioManager,
        sampleRateHz: Int,
        channelCount: Int,
        channelMask: Int
    ): DirectPlaybackCapability {
        val attributes = musicAttributes()
        val results = LinkedHashMap<PcmEncoding, Int?>(PcmEncoding.PROBED.size)

        for (encoding in PcmEncoding.PROBED) {
            results[encoding] = try {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding.audioFormatEncoding)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build()
                // Static on AudioManager; queried per encoding at the real playback format
                // rather than inferred from getDirectProfilesForAttributes.
                AudioManager.getDirectPlaybackSupport(format, attributes)
            } catch (e: Exception) {
                // A rejected format (unsupported rate/channel combination) throws rather than
                // returning NOT_SUPPORTED. Record "not asked" instead of guessing.
                null
            }
        }

        return DirectPlaybackClassifier.capability(
            probedAtSampleRateHz = sampleRateHz,
            probedAtChannelCount = channelCount,
            probedAtChannelMask = channelMask,
            results = results,
            advertisedProfiles = readAdvertisedProfiles(audioManager, attributes)
        )
    }

    /**
     * Reads `getDirectProfilesForAttributes`. Diagnostics only - never used to decide support,
     * because the advertised list and the runtime answer disagree on real devices and the
     * runtime probe is the one that reflects AudioPolicy's actual policy.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readAdvertisedProfiles(
        audioManager: AudioManager,
        attributes: AudioAttributes
    ): List<DirectProfile> = try {
        audioManager.getDirectProfilesForAttributes(attributes)
            .flatMap { profile -> profile.toDirectProfiles() }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        fun musicAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /**
         * Extracts the (encoding, rate) pairs from an advertised profile.
         *
         * `AudioProfile` does not extend `AudioFormat` - profiles are read through
         * `getFormat()` / `getSampleRates()`, and only PCM encapsulation matters for a direct
         * PCM decision. Anything else is dropped rather than recorded as an unknown encoding.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        fun AudioProfile.toDirectProfiles(): List<DirectProfile> {
            if (encapsulationType != AudioProfile.AUDIO_ENCAPSULATION_TYPE_PCM) return emptyList()
            val encoding = PcmEncoding.fromAudioFormatEncoding(format)
            if (encoding == PcmEncoding.UNKNOWN) return emptyList()
            val rates: IntArray = sampleRates ?: return emptyList()
            return rates.filter { it > 0 }.map { DirectProfile(encoding, it) }
        }
    }
}
