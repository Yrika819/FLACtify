import math
import unittest

from replaygain_math import (
    clipping_safe_gain_db,
    parse_gain_db,
    parse_peak,
    parse_r128_track_gain,
    parse_tags,
    requested_gain_db,
)


class ReplayGainMathTest(unittest.TestCase):
    def test_parse_gain_and_peak_units(self):
        self.assertEqual(parse_gain_db(" -7.23 dB "), -7.23)
        self.assertEqual(parse_gain_db("+2"), 2.0)
        self.assertEqual(parse_peak("1.25"), 1.25)
        self.assertIsNone(parse_gain_db("-7 LUFS"))
        self.assertIsNone(parse_peak("0.5 dB"))

    def test_malformed_nonfinite_and_unreasonable_values_are_unknown(self):
        for value in ("nan", "inf", "-inf", "121", "-121"):
            self.assertIsNone(parse_gain_db(value))
        for value in ("nan", "inf", "-1", "0.5dB"):
            self.assertIsNone(parse_peak(value))

    def test_r128_is_signed_q7_8_and_not_replaygain_text(self):
        self.assertEqual(parse_r128_track_gain("256"), 1.0)
        self.assertEqual(parse_r128_track_gain("-589"), -589 / 256)
        self.assertIsNone(parse_r128_track_gain("1.0"))
        self.assertIsNone(parse_r128_track_gain("+2 dB"))

    def test_tag_names_are_case_insensitive_and_album_selection_is_explicit(self):
        tags = parse_tags(
            {
                "replaygain_track_gain": "-7.0 dB",
                "REPLAYGAIN_ALBUM_GAIN": "-5.0 dB",
                "REPLAYGAIN_TRACK_PEAK": "0.8",
            }
        )
        self.assertEqual(requested_gain_db(tags), -7.0)
        self.assertEqual(requested_gain_db(tags, album=True), -5.0)
        self.assertEqual(tags.track_peak, 0.8)
        self.assertIsNone(tags.album_peak)

    def test_true_peak_guard_wins_and_only_clamps_boost(self):
        # A -6.02 dBFS true peak gives exactly 6.02 dB of headroom.
        safe = clipping_safe_gain_db(6.0, sample_peak=0.5, true_peak=2 ** -0.5)
        self.assertAlmostEqual(safe, 3.0102999566, places=8)
        self.assertEqual(clipping_safe_gain_db(-10.0, sample_peak=0.5), -10.0)
        self.assertEqual(clipping_safe_gain_db(3.0, sample_peak=None), 3.0)

    def test_invalid_requested_gain_is_not_silently_accepted(self):
        with self.assertRaises(ValueError):
            clipping_safe_gain_db(math.inf)


if __name__ == "__main__":
    unittest.main()

