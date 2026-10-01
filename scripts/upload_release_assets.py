#!/usr/bin/env python3
"""Reconstruct verified, already-signed assets from temporary Git blobs; never publish."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


REPOSITORY = "hashbrown3301/signal-zero"
TAG = "apk-v0.2.0"
RELEASE_ID = 400948841
SOURCE_COMMIT = "d1b3f864cde9ed03cc6772164e468edd4b37b98e"
ALLOWED_NAMES = {
    "iTantra-0.2.0-arm64.apk",
    "iTantra-0.2.0-armv7.apk",
    "SHA256SUMS.txt",
    "release-manifest.json",
}
MAX_BLOB_BYTES = 32 * 1024 * 1024
MAX_ASSET_BYTES = 300 * 1024 * 1024
MAX_TOTAL_BYTES = 600 * 1024 * 1024
MANIFEST = Path("docs/releases/apk-v0.2.0-transfer.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def gh_json(endpoint, limit=1024 * 1024):
    # Bound buffered API data, and never print base64 payloads or credentials.
    with subprocess.Popen(
        ["gh", "api", endpoint], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    ) as process:
        data = process.stdout.read(limit + 1)
        if len(data) > limit:
            process.kill()
            process.wait()
            raise ValueError("GitHub API response exceeded its size limit")
        require(process.wait() == 0, f"GitHub API failed for {endpoint}")
    return json.loads(data)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def draft_release():
    # GitHub's by-tag endpoint excludes this draft; use its verified release ID.
    release = gh_json(f"repos/{REPOSITORY}/releases/{RELEASE_ID}")
    require(release.get("tag_name") == TAG and release.get("draft") is True,
            "The expected release must exist and remain a draft")
    require(release.get("target_commitish") == SOURCE_COMMIT,
            "The draft release must target the verified APK source")
    return release


def validate_manifest(manifest):
    require(manifest.get("repository") == REPOSITORY, "Unexpected manifest repository")
    require(manifest.get("release_tag") == TAG, "Unexpected manifest release tag")
    require(manifest.get("source_commit") == SOURCE_COMMIT,
            "Unexpected source commit")
    assets = manifest.get("assets")
    require(isinstance(assets, list) and 1 <= len(assets) <= len(ALLOWED_NAMES),
            "Manifest must contain one to four allowed assets")
    names = set()
    total = 0
    for asset in assets:
        name = asset.get("name")
        require(name in ALLOWED_NAMES and name not in names, "Unexpected or duplicate asset name")
        names.add(name)
        size = asset.get("size")
        require(type(size) is int and 0 < size <= MAX_ASSET_BYTES, "Invalid asset size")
        require(re.fullmatch(r"[0-9a-f]{64}", asset.get("sha256", "")), "Invalid asset SHA-256")
        blobs = asset.get("blobs")
        require(isinstance(blobs, list) and 1 <= len(blobs) <= 4096, "Invalid blob list")
        for blob in blobs:
            require(re.fullmatch(r"[0-9a-f]{40}", blob.get("git_blob_sha", "")),
                    "Invalid Git blob SHA")
            require(type(blob.get("size")) is int and 0 < blob["size"] <= MAX_BLOB_BYTES,
                    "Invalid blob size")
        require(sum(blob["size"] for blob in blobs) == size, "Blob sizes do not match asset size")
        total += size
    require(total <= MAX_TOTAL_BYTES, "Combined asset size exceeds transfer limit")
    return assets


def reconstruct(asset, destination):
    full_hash = hashlib.sha256()
    written = 0
    with destination.open("xb") as stream:
        for blob in asset["blobs"]:
            result = gh_json(
                f"repos/{REPOSITORY}/git/blobs/{blob['git_blob_sha']}",
                limit=MAX_BLOB_BYTES * 3 // 2 + 65536,
            )
            require(result.get("encoding") == "base64" and result.get("size") == blob["size"],
                    "Unexpected Git blob encoding or size")
            require(result.get("sha") == blob["git_blob_sha"], "Unexpected Git blob identity")
            data = base64.b64decode("".join(result["content"].split()), validate=True)
            require(len(data) == blob["size"], "Decoded Git blob size differs")
            git_hash = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            require(git_hash == blob["git_blob_sha"], "Git blob content hash differs")
            stream.write(data)
            full_hash.update(data)
            written += len(data)
    require(written == asset["size"] and full_hash.hexdigest() == asset["sha256"],
            f"Reconstructed asset verification failed: {asset['name']}")


def existing_matches(asset, existing, directory):
    require(existing.get("size") == asset["size"], "Existing release asset size differs")
    digest = existing.get("digest")
    if digest is not None:
        require(digest == "sha256:" + asset["sha256"], "Existing release asset digest differs")
        return
    subprocess.run([
        "gh", "release", "download", TAG, "--repo", REPOSITORY,
        "--pattern", asset["name"], "--dir", str(directory),
    ], check=True)
    require(sha256(directory / asset["name"]) == asset["sha256"],
            "Existing release asset hash differs")


def main():
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Unexpected workflow repository")
    require(os.environ.get("GITHUB_REF") == "refs/heads/Upgradation", "Unexpected workflow branch")
    require(os.environ.get("TAG") == TAG, "Unexpected requested release tag")
    require(MANIFEST.stat().st_size <= 1024 * 1024, "Transfer manifest is too large")
    assets = validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")))
    draft_release()
    with tempfile.TemporaryDirectory(prefix="itantra-release-", dir=os.environ.get("RUNNER_TEMP")) as temp:
        directory = Path(temp)
        for asset in assets:
            # Recheck visibility before each write; never create, publish or replace an asset.
            release = draft_release()
            matches = [item for item in release["assets"] if item["name"] == asset["name"]]
            require(len(matches) <= 1, "Duplicate remote asset names")
            if matches:
                existing_matches(asset, matches[0], directory)
                print(f"Already verified: {asset['name']}")
                continue
            path = directory / asset["name"]
            reconstruct(asset, path)
            draft_release()
            subprocess.run([
                "gh", "release", "upload", TAG, str(path), "--repo", REPOSITORY,
            ], check=True)
            print(f"Uploaded verified asset: {asset['name']} ({asset['size']} bytes)")
        draft_release()


if __name__ == "__main__":
    main()
