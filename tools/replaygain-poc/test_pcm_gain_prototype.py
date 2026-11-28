import math
import time
import unittest

from pcm_gain_prototype import PcmFormat, PcmGainPrototype, StreamNotActivated, spatial_or_direct_bypass


class PcmGainPrototypeTest(unittest.TestCase):
    def setUp(self):
        self.stage = PcmGainPrototype(fade_frames=0)
        self.stage.configure(PcmFormat(48000, 2))

    def test_unity_is_transparent(self):
        self.stage.activate("a", 0.0)
        source = (-0.75, 0.25, 0.5, -0.1)
        self.assertEqual(self.stage.process(source, "a").samples, source)

    def test_gain_and_peak_ceiling(self):
        self.stage.activate("a", 6.020599913, peak_linear=0.5)
        result = self.stage.process((0.5, -0.5), "a").samples
        self.assertAlmostEqual(max(map(abs, result)), 1.0, places=6)

    def test_nonfinite_and_misaligned_pcm_are_rejected(self):
        self.stage.activate("a", 0.0)
        with self.assertRaises(ValueError):
            self.stage.process((math.nan, 0.0), "a")
        with self.assertRaises(ValueError):
            self.stage.process((0.0,), "a")

    def test_format_change_and_stream_transition_cannot_reuse_old_gain(self):
        self.stage.activate("a", -6.0)
        self.assertEqual(self.stage.process((1.0, 1.0), "a").samples, (10 ** (-6 / 20),) * 2)
        self.stage.configure(PcmFormat(96000, 1))
        with self.assertRaises(StreamNotActivated):
            self.stage.process((1.0,), "b")
        self.stage.activate("b", 0.0)
        self.assertEqual(self.stage.process((1.0,), "b").samples, (1.0,))

    def test_transition_fades_from_silence_to_new_gain(self):
        stage = PcmGainPrototype(fade_frames=4)
        stage.configure(PcmFormat(48000, 1))
        stage.activate("b", 0.0)
        values = stage.process((1.0, 1.0, 1.0, 1.0), "b").samples
        self.assertEqual(values, (0.25, 0.5, 0.75, 1.0))

    def test_spatial_and_direct_paths_are_explicit_bypass(self):
        self.assertEqual(spatial_or_direct_bypass(spatial_active=True, direct_or_offload=False), "SPATIAL_OUTPUT_BOUNDARY_NOT_IDENTITY")
        self.assertEqual(spatial_or_direct_bypass(spatial_active=False, direct_or_offload=True), "DIRECT_OR_OFFLOAD_MAY_BYPASS_PCM")
        self.assertIsNone(spatial_or_direct_bypass(spatial_active=False, direct_or_offload=False))

    def test_synthetic_cpu_measurement(self):
        self.stage.activate("a", -3.0)
        source = tuple(0.25 if index % 2 else -0.25 for index in range(48000 * 2))
        started = time.perf_counter()
        result = self.stage.process(source, "a")
        elapsed = time.perf_counter() - started
        self.assertEqual(len(result.samples), len(source))
        self.assertLess(elapsed, 2.0, f"synthetic 2s CPU probe took {elapsed:.3f}s")


if __name__ == "__main__":
    unittest.main()

