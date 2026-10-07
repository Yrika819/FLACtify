#!/usr/bin/env bash
set -euo pipefail

# Generate committed test audio from deterministic mathematical signals.
# Requirements: Bash, Python 3, FFmpeg 9.0.1 (or compatible FFmpeg with the
# lavfi aevalsrc source and FLAC encoder). Run from any directory.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/test/resources/audio"
mkdir -p "$OUT"

encode() {
    local expression="$1" rate="$2" duration="$3" channels="$4" bits="$5" target="$6"
    expression="${expression//,/\\,}"
    ffmpeg -hide_banner -loglevel error -nostdin -y \
        -f lavfi -i "aevalsrc=${expression}:s=${rate}:d=${duration}:c=${channels}" \
        -map_metadata -1 -map_chapters -1 -fflags +bitexact -flags:a +bitexact \
        -sample_fmt "$(if [[ "$bits" == 16 ]]; then printf s16; else printf s32; fi)" \
        -c:a flac -compression_level 8 -bits_per_raw_sample "$bits" "$OUT/$target"
}

# Small fixtures preserve the historical sample counts and coded formats.
encode '0.25*sin(2*PI*440*t)|0.20*sin(2*PI*660*t)' 44100 0.03 stereo 16 flac_44100_16_stereo.flac
encode '0.20*sin(2*PI*440*t)|0.20*sin(2*PI*660*t)' 96000 0.02 stereo 24 flac_96000_24_stereo.flac
encode '0.20*sin(2*PI*440*t)|0.20*sin(2*PI*660*t)' 192000 0.02 stereo 24 flac_192000_24_stereo.flac
encode '0.10*sin(2*PI*220*t)|0.10*sin(2*PI*330*t)|0.10*sin(2*PI*440*t)|0.10*sin(2*PI*550*t)|0.10*sin(2*PI*660*t)|0.10*sin(2*PI*770*t)' 48000 0.02 5.1 24 flac_48000_24_surround6.flac

# Sustained silence remains seekable and decodes to exactly 15 seconds at 192 kHz.
encode '0|0' 192000 15 stereo 24 .sustained-silence.flac
base64 < "$OUT/.sustained-silence.flac" | tr -d '\n' > "$OUT/flac_192000_24_stereo_silence_15s.flac.b64"
printf '\n' >> "$OUT/flac_192000_24_stereo_silence_15s.flac.b64"
rm "$OUT/.sustained-silence.flac"

# The capture test requires one second of tone (192,000 frames at 48 kHz)
# followed by one second of silence in each playlist item.
encode 'if(lt(t,1),0.25*sin(2*PI*440*t),0)|if(lt(t,1),0.20*sin(2*PI*660*t),0)' 48000 2 stereo 16 .tone-then-silence.flac
base64 < "$OUT/.tone-then-silence.flac" | tr -d '\n' > "$OUT/spatial_capture_tone_then_silence_48000_16_stereo.flac.b64"
printf '\n' >> "$OUT/spatial_capture_tone_then_silence_48000_16_stereo.flac.b64"
rm "$OUT/.tone-then-silence.flac"

# Create a valid encoded FLAC with only a STREAMINFO metadata block. Retain the
# audio frames of the 44.1 kHz file while discarding optional encoder metadata.
python3 - "$OUT/flac_44100_16_stereo.flac" "$OUT/flac_streaminfo_only_44100_16_stereo.flac" <<'PY'
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
data = source.read_bytes()
if data[:4] != b"fLaC":
    raise SystemExit("input is not a FLAC stream")
pos = 4
streaminfo = None
while True:
    header = data[pos]
    is_last = bool(header & 0x80)
    block_type = header & 0x7f
    length = int.from_bytes(data[pos + 1:pos + 4], "big")
    block = data[pos:pos + 4 + length]
    pos += 4 + length
    if block_type == 0:
        streaminfo = bytearray(block)
    if is_last:
        break
if streaminfo is None or len(streaminfo) != 38:
    raise SystemExit("missing or malformed STREAMINFO")
streaminfo[0] |= 0x80  # STREAMINFO is the final and only metadata block.
destination.write_bytes(b"fLaC" + streaminfo + data[pos:])
PY
