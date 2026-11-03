#!/usr/bin/env python3
"""Dependency guard for the release runtime classpath.

Reads the output of
    ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath
and reduces it to a sorted list of resolved `group:artifact:version` coordinates.

    python3 ci/dependency_guard.py check  deps.txt ci/release-dependencies.txt
    python3 ci/dependency_guard.py update deps.txt ci/release-dependencies.txt

`check` fails (exit 1) when the list differs from the committed baseline, printing a diff, or when
a forbidden artifact is present (ad mediation adapters, Filament's runtime material compiler).
After an intended dependency change, run `update` and commit the new baseline.
"""
from __future__ import annotations

import difflib
import re
import sys
from pathlib import Path

# Never allowed in the consumer APK, whatever the baseline says.
FORBIDDEN = [
    (re.compile(r"^com\.google\.ads\.mediation:"), "ad mediation adapter (v1 has no mediation)"),
    (re.compile(r"^com\.google\.android\.filament:filamat"), "filamat runtime material compiler (~12 MB)"),
    (re.compile(r"^com\.google\.android\.gms:play-services-ads-lite:"), "deprecated Lite ads SDK"),
]

LINE = re.compile(
    r"^[\s|+\\-]*"  # tree drawing
    r"(?P<group>[A-Za-z0-9_.\-]+):(?P<artifact>[A-Za-z0-9_.\-]+)"
    r"(?::(?P<requested>[^\s]+))?"
    r"(?:\s+->\s+(?P<resolved>[^\s]+))?"
    r"(?P<rest>.*)$"
)


def coordinates(text: str) -> list[str]:
    out: set[str] = set()
    for raw in text.splitlines():
        if not raw.strip() or "project :" in raw:
            continue
        m = LINE.match(raw)
        if not m:
            continue
        rest = m.group("rest") or ""
        # (c) = constraint only, (n) = not resolved: neither is on the classpath.
        if "(c)" in rest or "(n)" in rest:
            continue
        version = m.group("resolved") or m.group("requested")
        if not version or version.startswith("{"):
            continue
        out.add(f"{m.group('group')}:{m.group('artifact')}:{version}")
    return sorted(out)


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[1] not in ("check", "update"):
        print(__doc__, file=sys.stderr)
        return 2
    mode, deps_file, baseline_file = argv[1], Path(argv[2]), Path(argv[3])
    current = coordinates(deps_file.read_text(encoding="utf-8"))
    if not current:
        print("::error::No dependencies parsed; is the input the output of ':app:dependencies'?")
        return 1

    bad = [(c, why) for c in current for rx, why in FORBIDDEN if rx.search(c)]
    for c, why in bad:
        print(f"::error::Forbidden dependency {c}: {why}")

    if mode == "update":
        baseline_file.write_text("\n".join(current) + "\n", encoding="utf-8")
        print(f"Wrote {len(current)} coordinates to {baseline_file}")
        return 1 if bad else 0

    baseline = [l.strip() for l in baseline_file.read_text(encoding="utf-8").splitlines() if l.strip()]
    if current != baseline:
        sys.stdout.writelines(
            difflib.unified_diff([l + "\n" for l in baseline], [l + "\n" for l in current],
                                 fromfile=str(baseline_file), tofile="releaseRuntimeClasspath")
        )
        print("::error::Release dependencies changed. If intended, run "
              "'python3 ci/dependency_guard.py update <deps> ci/release-dependencies.txt' and commit it.")
        return 1
    print(f"Release dependencies match the baseline ({len(current)} coordinates).")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
