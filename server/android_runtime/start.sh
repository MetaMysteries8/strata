#!/data/data/com.termux/files/usr/bin/bash
# Restart an installed environment without downloading or reinstalling Ubuntu.
set -euo pipefail
umask 077
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v proot-distro >/dev/null; then
    echo 'One-time setup is missing. Run the setup command from Strata first.' >&2
    exit 1
fi
if ! proot-distro login strata-modal -- /bin/test -x /opt/strata-venv/bin/python; then
    echo 'The Strata Ubuntu/Python environment is not ready. Run one-time setup to repair it.' >&2
    exit 1
fi
if command -v termux-wake-lock >/dev/null; then
    termux-wake-lock || true
    trap 'termux-wake-unlock >/dev/null 2>&1 || true' EXIT
fi
proot-distro login strata-modal --bind "$source_dir:/opt/strata-source" -- /bin/bash /opt/strata-source/android_runtime/start-linux.sh
