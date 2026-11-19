#!/usr/bin/env python3
"""Validate built first-party artifacts; archive committed source and write SHA256SUMS."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def package(root):
    if git(root, "status", "--porcelain", "--untracked-files=normal").strip():
        raise ValueError("Commit the reviewed release files first; the working tree must be clean")
    match = re.search(r"version\s*=\s*'([^']+)'", (root / "build.gradle").read_text())
    if not match or not re.fullmatch(r"[0-9A-Za-z.-]+", match[1]):
        raise ValueError("Cannot identify a safe project version")
    version = match[1]
    dist = root / "dist"
    components = {
        f"vss-cdn-cilent-neoforge-1.21.1-{version}.jar": ("client/mods", "vss_cdn_client"),
        f"vss-cdn-server-neoforge-1.21.1-{version}.jar": ("server/mods", "vss_cdn_server"),
        f"vss-cdn-bridge-{version}.jar": ("velocity/plugins", "vss-cdn-bridge"),
    }
    for name, (_, identity) in components.items():
        with ZipFile(dist / name) as jar:
            if identity == "vss-cdn-bridge":
                info = json.loads(jar.read("velocity-plugin.json"))
                if info["id"] != identity or info["version"] != version:
                    raise ValueError("Bridge identity/version mismatch")
            else:
                mod = jar.read("META-INF/neoforge.mods.toml").decode()
                if not re.search(r'modId\s*=\s*"' + identity + '"', mod):
                    raise ValueError("NeoForge mod identity mismatch: " + name)
                if not re.search(r'version\s*=\s*"' + re.escape(version) + '"', mod):
                    raise ValueError("NeoForge version mismatch: " + name)
            if "cn/mctogether/vsscdn/common/ColumnKey.class" not in jar.namelist():
                raise ValueError("Missing embedded common classes: " + name)
            if "LICENSE" not in jar.namelist():
                raise ValueError("Missing first-party license: " + name)
            if identity == "vss_cdn_client":
                mixins = json.loads(jar.read("vsscdn-client.mixins.json"))
                if set(mixins["client"]) != {"ClientLevelMixin", "VssReceiveMixin", "VssBudgetMixin"}:
                    raise ValueError("Client must only observe level changes and adapt VSS transport")
                retired = {"VoxyScope", "VoxyIdentityMixin", "VssCacheMixin", "ClientCacheIdentity"}
                for entry in jar.namelist():
                    if not entry.endswith(".class"):
                        continue
                    if Path(entry).stem in retired:
                        raise ValueError("Retired world/cache manager in client: " + entry)
                    body = jar.read(entry)
                    if b"me/cortex/voxy" in body or b"me.cortex.voxy" in body:
                        raise ValueError("Client must leave Voxy world/render management native: " + entry)
    install_name = f"vss-cdn-{version}-install.zip"
    with ZipFile(dist / install_name) as archive:
        jar_paths = {n for n in archive.namelist() if n.endswith(".jar")}
        expected = {folder + "/" + name for name, (folder, _) in components.items()}
        if jar_paths != expected:
            raise ValueError("Installer must contain exactly the three first-party JARs")
        for name, (folder, _) in components.items():
            if archive.read(folder + "/" + name) != (dist / name).read_bytes():
                raise ValueError("Installer contains a different JAR: " + name)
        documents = ["README.md", "VERIFICATION.md", "CHANGELOG.md", "CONTRIBUTING.md", "LICENSE",
                     "tools/prepare_youer_vss.py"]
        documents += [str(p.relative_to(root)) for folder in ["docs", "examples"]
                      for p in (root / folder).rglob("*") if p.is_file()]
        for path in documents:
            if archive.read(path) != (root / path).read_bytes():
                raise ValueError("Rebuild distZip after changing documentation: " + path)
    tracked = git(root, "ls-tree", "-r", "--name-only", "HEAD").decode().splitlines()
    for path in tracked:
        if re.search(r"(^|/)(\.local|\.dev-libs|\.gradle-home|dist|build)/", path):
            raise ValueError("Generated/local data must not be tracked: " + path)
        if path.endswith((".pem", ".key")) or path.rsplit("/", 1)[-1] == "credentials.csv":
            raise ValueError("Credential-like file must not be in a release: " + path)
    source_name = f"vss-cdn-{version}-source.zip"
    source = git(root, "archive", "--format=zip", f"--prefix=vss-cdn-{version}/", "HEAD")
    (dist / source_name).write_bytes(source)
    names = sorted([*components, install_name, source_name])
    sums = "".join(hashlib.sha256((dist / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in names)
    (dist / "SHA256SUMS").write_text(sums)
    print(json.dumps({"version": version, "commit": git(root, "rev-parse", "HEAD").decode().strip(),
                      "artifacts": names + ["SHA256SUMS"], "validation": "passed"}, indent=2))


if __name__ == "__main__":
    package(Path(__file__).resolve().parents[1])
