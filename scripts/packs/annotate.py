"""Turn the end of a log file into a GitHub Actions error annotation (readable on the public run page).

    python scripts/packs/annotate.py "build ta:listen" build-ta-listen.log
"""

import sys
from pathlib import Path

title, log = sys.argv[1], Path(sys.argv[2])
lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else ["(no log)"]
tail = "\n".join(lines[-40:])[-3500:]
# Workflow commands need %, CR and LF escaped.
message = tail.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title={title}::{message}")
