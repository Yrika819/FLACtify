#!/usr/bin/env python3
"""Check APK native-library ZIP alignment and ELF PT_LOAD alignment for 16 KiB pages."""

import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024
PT_LOAD = 1


def load_segment_alignments(elf: bytes) -> list[int]:
    if len(elf) < 64 or elf[:4] != b"\x7fELF":
        raise ValueError("invalid ELF header")
    elf_class = elf[4]
    byte_order = elf[5]
    if byte_order != 1:
        raise ValueError("only little-endian Android ELF files are supported")

    if elf_class == 2:
        phoff = struct.unpack_from("<Q", elf, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", elf, 54)
        minimum_size = 56
        fmt = "<IIQQQQQQ"
        align_index = 7
    elif elf_class == 1:
        phoff = struct.unpack_from("<I", elf, 28)[0]
        phentsize, phnum = struct.unpack_from("<HH", elf, 42)
        minimum_size = 32
        fmt = "<IIIIIIII"
        align_index = 7
    else:
        raise ValueError(f"unsupported ELF class: {elf_class}")

    if phentsize < minimum_size:
        raise ValueError("invalid ELF program-header size")

    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + minimum_size > len(elf):
            raise ValueError("truncated ELF program-header table")
        header = struct.unpack_from(fmt, elf, offset)
        if header[0] == PT_LOAD:
            alignments.append(header[align_index])
    if not alignments:
        raise ValueError("ELF contains no PT_LOAD segments")
    return alignments


def main(apk_path: str) -> int:
    failures = []
    try:
        with zipfile.ZipFile(apk_path) as apk:
            libraries = [name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")]
            if not libraries:
                raise ValueError("APK contains no native libraries")
            for name in libraries:
                try:
                    alignments = load_segment_alignments(apk.read(name))
                    bad = [value for value in alignments if value < PAGE_SIZE]
                    if bad:
                        failures.append(f"{name}: PT_LOAD alignment below 16 KiB: {bad}")
                    else:
                        print(f"PASS {name}: PT_LOAD alignments {alignments}")
                except (ValueError, struct.error) as error:
                    failures.append(f"{name}: {error}")
    except (OSError, zipfile.BadZipFile, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {len(libraries)} native libraries meet {PAGE_SIZE}-byte ELF alignment")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} APK", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
