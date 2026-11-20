"""Build the Go module proxy files for the local release candidate."""

import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

MODULE = "github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
VERSION = "v0.1.0-rc.1"
CANDIDATE_DATE = "2026-11-20T00:00:00Z"
ROOT = Path(__file__).resolve().parents[1]


def main():
    escaped = "".join("!" + char.lower() if char.isupper() else char for char in MODULE)
    target = ROOT / "dist" / "goproxy" / escaped / "@v"
    target.mkdir(parents=True, exist_ok=True)

    sources = [
        ROOT / "go.mod",
        ROOT / "go.sum",
        ROOT / "README.md",
        ROOT / "testdata" / "sdk-event.json",
        Path(__file__).resolve(),
        *ROOT.glob("*.go"),
        *ROOT.glob("examples/*/*.go"),
    ]
    (target / f"{VERSION}.mod").write_bytes((ROOT / "go.mod").read_bytes())
    (target / f"{VERSION}.info").write_text(
        json.dumps({"Version": VERSION, "Time": CANDIDATE_DATE}) + "\n", encoding="ascii"
    )
    (target / "list").write_text(VERSION + "\n", encoding="ascii")
    with ZipFile(target / f"{VERSION}.zip", "w") as archive:
        for source in sorted(sources):
            name = f"{MODULE}@{VERSION}/{source.relative_to(ROOT).as_posix()}"
            entry = ZipInfo(name)
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, source.read_bytes())
    print(target)


if __name__ == "__main__":
    main()
