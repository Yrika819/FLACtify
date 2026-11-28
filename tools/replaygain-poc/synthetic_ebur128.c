/*
 * Synthetic-only libebur128 probe.  This is intentionally not an Android source file:
 * it links the host's libebur128 and generates an in-memory stereo sine wave.  No input
 * file is opened, decoded, or written.
 *
 * The declarations mirror libebur128's stable public ABI.  A system development package
 * with ebur128.h is not required for this small measurement probe; the run script links
 * the installed SONAME directly when available.
 */
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct ebur128_state ebur128_state;

enum {
    EBUR128_MODE_M = 1,
    EBUR128_MODE_S = 2,
    EBUR128_MODE_I = 4,
    EBUR128_MODE_SAMPLE_PEAK = 16,
    EBUR128_MODE_TRUE_PEAK = 32,
};

extern ebur128_state *ebur128_init(unsigned int channels, unsigned long sample_rate, int mode);
extern void ebur128_destroy(ebur128_state **st);
extern int ebur128_add_frames_float(ebur128_state *st, const float *src, size_t frames);
extern int ebur128_loudness_global(ebur128_state *st, double *loudness);
extern int ebur128_sample_peak(ebur128_state *st, unsigned int channel_number, double *out);
extern int ebur128_true_peak(ebur128_state *st, unsigned int channel_number, double *out);

static double dbfs(double linear) {
    return linear > 0.0 ? 20.0 * log10(linear) : -INFINITY;
}

int main(void) {
    const unsigned int channels = 2;
    const unsigned long rate = 48000;
    const unsigned long frames = rate * 10; /* >400 ms gating window, deterministic 10 s test */
    const double amplitude = 0.5;
    const double frequency = 1000.0;
    const double pi = acos(-1.0);
    float *pcm = calloc(frames * channels, sizeof(*pcm));
    if (!pcm) {
        fputs("allocation failed\n", stderr);
        return 1;
    }
    for (unsigned long frame = 0; frame < frames; ++frame) {
        float sample = (float)(amplitude * sin(2.0 * pi * frequency * frame / rate));
        pcm[frame * channels] = sample;
        pcm[frame * channels + 1] = sample;
    }

    /* I depends on the momentary/short-term windows, so request both base modes too. */
    ebur128_state *state = ebur128_init(channels, rate,
        EBUR128_MODE_M | EBUR128_MODE_S | EBUR128_MODE_I |
        EBUR128_MODE_SAMPLE_PEAK | EBUR128_MODE_TRUE_PEAK);
    if (!state) {
        free(pcm);
        fputs("ebur128_init failed\n", stderr);
        return 1;
    }
    int add_status = ebur128_add_frames_float(state, pcm, frames);
    double loudness = 0.0;
    int loudness_status = ebur128_loudness_global(state, &loudness);
    double sample_peak = 0.0;
    double true_peak = 0.0;
    int sample_peak_status = ebur128_sample_peak(state, 0, &sample_peak);
    int true_peak_status = ebur128_true_peak(state, 0, &true_peak);
    printf("frames=%lu sample_rate=%lu amplitude=%.6f\n", frames, rate, amplitude);
    printf("add_frames_status=%d loudness_status=%d integrated_lufs=%.6f\n",
        add_status, loudness_status, loudness);
    printf("sample_peak_linear=%.9f sample_peak_dbfs=%.6f\n",
        sample_peak, dbfs(sample_peak));
    printf("true_peak_linear=%.9f true_peak_dbfs=%.6f\n",
        true_peak, dbfs(true_peak));
    printf("sample_peak_status=%d true_peak_status=%d\n", sample_peak_status, true_peak_status);

    ebur128_destroy(&state);
    free(pcm);
    return add_status == 0 && loudness_status == 0 ? 0 : 1;
}

