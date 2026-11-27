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

encode_compressed_fixture() {
    local frequency="$1" codec="$2" bitrate="$3" target="$4" extension="$5"
    ffmpeg -hide_banner -loglevel error -nostdin -y \
        -f lavfi -i "sine=frequency=${frequency}:duration=0.25" \
        -map_metadata -1 -map_chapters -1 -fflags +bitexact -flags:a +bitexact \
        -c:a "$codec" -b:a "$bitrate" "$OUT/$target.$extension"
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

# Short compressed fixtures exercise audio sniffing and scanner metadata fallback.
picture_block="$(python3 - <<'PY'
import base64
import struct
import zlib

def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)

png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(b"\x00\xff\x20\x20\xff\x00\xff\x20\x20\xff"))
png += chunk(b"IEND", b"")
picture = struct.pack(">I", 3)
picture += struct.pack(">I", len(b"image/png")) + b"image/png"
picture += struct.pack(">I", 0)  # description
picture += struct.pack(">IIIII", 2, 2, 24, 0, len(png)) + png
print(base64.b64encode(picture).decode("ascii"))
PY
)"
ffmpeg -hide_banner -loglevel error -nostdin -y \
    -f lavfi -i 'sine=frequency=440:duration=0.4' \
    -map_metadata -1 -metadata title='Synthetic Opus' -metadata artist='FLACtify Test' \
    -metadata album='Synthetic Regression' -metadata track='1' \
    -metadata lyrics='[00:00.00]Synthetic Opus lyric' \
    -metadata "METADATA_BLOCK_PICTURE=$picture_block" \
    -c:a libopus -b:a 64k "$OUT/opus_tagged.opus"
unset picture_block
encode_compressed_fixture 523 libopus 48k opus_untagged opus
cp "$OUT/opus_untagged.opus" "$OUT/opus_as_ogg.ogg"
cp "$OUT/opus_untagged.opus" "$OUT/opus_as_m4a.m4a"
encode_compressed_fixture 660 aac 64k aac m4a
encode_compressed_fixture 784 libmp3lame 64k synthetic mp3
ffmpeg -hide_banner -loglevel error -nostdin -y \
    -f lavfi -i 'sine=frequency=880:duration=0.25' -map_metadata -1 \
    -c:a pcm_s16le "$OUT/synthetic.wav"
printf 'broken audio fixture' > "$OUT/corrupt.opus"
