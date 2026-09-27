"""Write index.json (the pack catalogue) for the packs just built, merged with an existing index.

    python scripts/packs/make_index.py dist/packs [old_index.json] > dist/index.json
"""

import hashlib
import json
import sys
from pathlib import Path


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    dist = Path(sys.argv[1])
    old = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    index = json.loads(old.read_text(encoding="utf-8")) if old and old.exists() else {"format": 1, "packs": {}}

    for pack_json in sorted(dist.glob("*/pack.json")):
        pack = json.loads(pack_json.read_text(encoding="utf-8"))
        zip_path = dist / f"{pack['id']}.zip"
        index["packs"][pack["id"]] = {
            "lang": pack["lang"],
            "packet_code": pack["packet_code"],
            "name": pack["name"],
            "native": pack["native"],
            "kind": pack["kind"],
            "engine": pack["engine"]["type"],
            "size": pack["size"],
            "zip": zip_path.name,
            "zip_size": zip_path.stat().st_size,
            "zip_sha256": hashlib.sha256(zip_path.read_bytes()).hexdigest(),
            "licences": sorted({s.get("licence", "") for s in pack["sources"]}),
            "built": pack["built"],
        }
    print(json.dumps(index, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
