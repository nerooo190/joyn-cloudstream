#!/usr/bin/env python3
"""Assemble and validate the files Cloudstream downloads from the builds branch."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


def prepare(repository: str, output: Path, source_sha: str = "local") -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository muss als OWNER/REPO angegeben werden.")
    if repository.startswith("YOUR_USERNAME/"):
        raise ValueError("Bitte einen echten Repository-Namen übergeben.")
    root = Path(__file__).resolve().parents[1]
    plugin = root / "JoynProvider/build/JoynProvider.cs3"
    entries = json.loads((root / "build/plugins.json").read_text())
    if len(entries) != 1 or entries[0]["internalName"] != "JoynProvider":
        raise ValueError("Unerwarteter Plugin-Index.")
    entry = entries[0]
    payload = plugin.read_bytes()
    if not payload:
        raise ValueError("Leeres Plugin.")
    if entry.get("fileSize") != len(payload):
        raise ValueError("Plugin-Größe stimmt nicht mit dem Index überein.")
    checksum = "sha256-" + hashlib.sha256(payload).hexdigest()
    if entry.get("fileHash") not in (None, checksum):
        raise ValueError("Plugin-Prüfsumme stimmt nicht mit dem Index überein.")
    prefix = f"https://raw.githubusercontent.com/{repository}/builds"
    expected_url = f"{prefix}/JoynProvider.cs3"
    if entry["url"] != expected_url:
        raise ValueError(
            f"Plugin verweist auf {entry['url']!r}. Neu bauen mit "
            f"./gradlew -Prepository={repository} make makePluginsJson"
        )
    manifest = {
        "name": "Joyn Mediathek – experimentell",
        "description": "Kostenlose Joyn-Inhalte (DE). Wiedergabe-Signierung noch offen.",
        "manifestVersion": 1,
        "pluginLists": [f"{prefix}/plugins.json"],
    }
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(plugin, output / plugin.name)
    (output / "plugins.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n")
    (output / "repo.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (output / "BUILD_INFO.json").write_text(json.dumps({
        "repository": repository,
        "sourceCommit": source_sha,
        "pluginVersion": entry["version"],
        "sha256": checksum,
        "playbackSigningImplemented": False,
        "androidPlaybackVerified": False,
    }, indent=2) + "\n")
    print(f"Repository erstellt: {output}")
    print(f"In Cloudstream hinzufügen: {prefix}/repo.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="GitHub OWNER/REPO")
    parser.add_argument("--output", type=Path, default=Path("build/repository"))
    parser.add_argument("--source-sha", default="local")
    args = parser.parse_args()
    prepare(args.repository, args.output, args.source_sha)
