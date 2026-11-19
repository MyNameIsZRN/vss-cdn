#!/usr/bin/env python3
"""Remove VSS's bundled zstd only when the specified Youer library supplies it.

VSS business classes and its licenses remain byte-for-byte unchanged. The input
is never edited. This is needed for the tested Youer launcher JPMS collision.
"""
import argparse
import json
import re
from pathlib import Path
from zipfile import ZipFile


def prepare(vss: Path, platform: Path, output: Path) -> int:
    if output.resolve() in (vss.resolve(), platform.resolve()) or output.exists():
        raise ValueError("Output must be a new file; inputs are never overwritten")
    with ZipFile(platform) as library:
        if "com/github/luben/zstd/Zstd.class" not in library.namelist():
            raise ValueError("The specified Youer library does not supply zstd-jni")
        manifest = library.read("META-INF/MANIFEST.MF").decode()
        if "Bundle-SymbolicName: com.github.luben.zstd-jni" not in manifest or "module-info.class" not in library.namelist():
            raise ValueError("Unexpected zstd module identity")
        if "Implementation-Version: 1.5.7-8" not in manifest:
            raise ValueError("This preparation is verified with Youer's zstd-jni 1.5.7-8 only")
    metadata = "META-INF/jarjar/metadata.json"
    with ZipFile(vss) as source:
        mod = source.read("META-INF/neoforge.mods.toml").decode()
        if not re.search(r'modId\s*=\s*"lss"', mod) or not re.search(r'version\s*=\s*"0\.22\.0"', mod):
            raise ValueError("This compatibility preparation supports official VSS NeoForge 0.22.0 only")
        data = json.loads(source.read(metadata))
        removed = {j["path"] for j in data["jars"] if j["identifier"] == {"group": "com.github.luben", "artifact": "zstd-jni"}}
        if not removed:
            raise ValueError("No bundled zstd dependency found; no preparation needed")
        data["jars"] = [j for j in data["jars"] if j["path"] not in removed]
        output.parent.mkdir(parents=True, exist_ok=True)
        try:
            with ZipFile(output, "x") as target:
                for entry in source.infolist():
                    if entry.filename in removed:
                        continue
                    body = json.dumps(data, indent=2).encode() if entry.filename == metadata else source.read(entry)
                    target.writestr(entry, body)
                target.comment = source.comment
        except BaseException:
            output.unlink(missing_ok=True)
            raise
    return len(removed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vss", type=Path, required=True)
    parser.add_argument("--youer-zstd", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    count = prepare(args.vss, args.youer_zstd, args.output)
    print(f"Prepared {args.output}: removed {count} bundled zstd dependency; all VSS classes retained")
