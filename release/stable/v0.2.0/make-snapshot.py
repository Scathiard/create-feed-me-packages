"""Create the test.57 source snapshot zip from the tracked build files (working tree state)."""
import hashlib
import os
import zipfile
from pathlib import Path

ROOT = Path(r"D:\claude_sandbox\Create-Feed Me Packages!")
REL = ROOT / "release" / "0.2.0"
ZIP = REL / "source-fe42c7e.zip"

# The exact build/source/test/resource set (public GitHub convention: build files only, no internal docs).
paths = [
    ".gitattributes", ".gitignore", "LICENSE", "README.md", "THIRD_PARTY_NOTICES.md",
    "build.gradle", "gradle.properties", "settings.gradle", "gradlew", "gradlew.bat",
]
for sub in ["gradle"]:
    for p in (ROOT / sub).rglob("*"):
        if p.is_file():
            paths.append(str(p.relative_to(ROOT)))

def walk_src(base):
    for root, dirs, files in os.walk(ROOT / base):
        dirs[:] = [d for d in dirs if d not in ("build",)]
        for f in files:
            if f.endswith(".class") or f == "latest.log":
                continue
            p = Path(root) / f
            rel = str(p.relative_to(ROOT)).replace("\\", "/")
            if rel.endswith("panel.png") or rel.endswith("slot_source.png") or rel.endswith(".mixins.json"):
                yield rel
            elif ".java" in f or f.endswith(".json") or f.endswith(".toml"):
                yield rel

paths += list(walk_src("src"))
paths = sorted(set(paths))

manifest = []
with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in paths:
        src = ROOT / rel
        if not src.is_file():
            continue
        data = src.read_bytes()
        z.writestr(rel, data)
        manifest.append(f"{hashlib.sha256(data).hexdigest()}  {rel}")

with open(REL / "SOURCE_SHA256SUMS.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(manifest) + "\n")

print("ZIP", ZIP)
print("files", len(manifest))
print("bytes", ZIP.stat().st_size)



