#!/usr/bin/env python3
"""Checks the native libraries inside an APK for Google Play's 16 KB page-size requirement.

For every lib/<abi>/*.so entry it verifies that:
  1. the entry is stored uncompressed and its data starts on a 16 KB boundary in the zip
     (what `zipalign -c -P 16` checks), so it can be mmapped straight from the APK;
  2. on 64-bit ABIs, every ELF PT_LOAD segment has p_align >= 16 KB (what Android's
     check_elf_alignment.sh checks). 32-bit ABIs never run with 16 KB pages, so their ELF
     alignment is only reported;
  3. no forbidden library is present (e.g. Filament's runtime material compiler, filamat).

Prints a native-library inventory and exits non-zero on any violation. Standard library only.

Usage: check_native_libs.py APK [--forbid SUBSTRING ...] [--summary FILE]
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from dataclasses import dataclass
from typing import BinaryIO

PAGE_16K = 16 * 1024
PT_LOAD = 1
ABIS_64_BIT = {"arm64-v8a", "x86_64"}
LOCAL_HEADER_SIGNATURE = b"PK\x03\x04"
LOCAL_HEADER_SIZE = 30


@dataclass
class LibReport:
    path: str
    abi: str
    size: int
    stored: bool
    zip_aligned: bool
    min_load_align: int | None  # None if the file is not a valid ELF or has no PT_LOAD
    errors: list[str]


def data_offset(apk: BinaryIO, info: zipfile.ZipInfo) -> int:
    """Offset of an entry's data, read from its local header (its extra field can differ
    from the central directory's, so the central directory alone is not enough)."""
    apk.seek(info.header_offset)
    header = apk.read(LOCAL_HEADER_SIZE)
    if header[:4] != LOCAL_HEADER_SIGNATURE:
        raise ValueError(f"bad local header for {info.filename}")
    name_len, extra_len = struct.unpack("<HH", header[26:30])
    return info.header_offset + LOCAL_HEADER_SIZE + name_len + extra_len


def load_alignments(elf: bytes) -> list[int]:
    """p_align of every PT_LOAD segment in an ELF file (32- or 64-bit, either endianness)."""
    if len(elf) < 0x34 or elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class, elf_data = elf[4], elf[5]
    endian = "<" if elf_data == 1 else ">"
    if elf_class == 2:  # ELF64
        phoff, = struct.unpack_from(endian + "Q", elf, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x36)
        type_fmt, align_off, align_fmt = "I", 48, "Q"
    elif elf_class == 1:  # ELF32
        phoff, = struct.unpack_from(endian + "I", elf, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x2A)
        type_fmt, align_off, align_fmt = "I", 28, "I"
    else:
        raise ValueError(f"unknown ELF class {elf_class}")
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + type_fmt, elf, base)
        if p_type == PT_LOAD:
            p_align, = struct.unpack_from(endian + align_fmt, elf, base + align_off)
            aligns.append(p_align)
    return aligns


def check(apk_path: str, forbidden: list[str]) -> list[LibReport]:
    reports = []
    with zipfile.ZipFile(apk_path) as apk, open(apk_path, "rb") as raw:
        for info in apk.infolist():
            name = info.filename
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            abi = name.split("/")[1]
            errors = []
            stored = info.compress_type == zipfile.ZIP_STORED
            offset = data_offset(raw, info)
            zip_aligned = stored and offset % PAGE_16K == 0
            if not stored:
                errors.append("compressed in the APK (needs useLegacyPackaging = false)")
            elif not zip_aligned:
                errors.append(f"zip data offset {offset} is not 16 KB aligned")

            min_align = None
            try:
                aligns = load_alignments(apk.read(name))
                min_align = min(aligns) if aligns else None
            except ValueError as e:
                errors.append(str(e))
            if abi in ABIS_64_BIT and (min_align is None or min_align < PAGE_16K):
                errors.append(f"ELF LOAD segment alignment {min_align} < {PAGE_16K}")

            lib_name = name.rsplit("/", 1)[-1]
            for pattern in forbidden:
                if pattern in lib_name:
                    errors.append(f"forbidden library (matches '{pattern}')")

            reports.append(LibReport(name, abi, info.file_size, stored, zip_aligned, min_align, errors))
    return reports


def fmt_align(align: int | None) -> str:
    if not align:
        return "-"
    return f"2**{align.bit_length() - 1}" if align & (align - 1) == 0 else str(align)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("apk")
    parser.add_argument("--forbid", action="append", default=[], help="fail if a .so name contains this")
    parser.add_argument("--summary", help="append a Markdown table to this file (e.g. $GITHUB_STEP_SUMMARY)")
    args = parser.parse_args()

    reports = check(args.apk, args.forbid)
    rows = [
        f"| `{r.path}` | {r.size / 1024:,.0f} KiB | {'yes' if r.zip_aligned else 'NO'} | {fmt_align(r.min_load_align)} "
        f"| {'OK' if not r.errors else '; '.join(r.errors)} |"
        for r in sorted(reports, key=lambda r: r.path)
    ]
    table = "\n".join([
        "| Native library | Size | Zip 16 KB aligned | Min LOAD align | Result |",
        "|---|---:|:---:|:---:|---|",
        *rows,
    ])
    print(table if reports else "No native libraries found.")
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as out:
            out.write("### Native libraries (16 KB page size)\n\n" + (table if reports else "None.") + "\n\n")

    failures = [r for r in reports if r.errors]
    if failures:
        for r in failures:
            print(f"::error::{r.path}: {'; '.join(r.errors)}")
        return 1
    print(f"All {len(reports)} native libraries pass the 16 KB page-size checks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
