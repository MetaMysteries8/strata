#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v termux-wake-lock >/dev/null; then
    termux-wake-lock || true
    trap 'termux-wake-unlock >/dev/null 2>&1 || true' EXIT
fi
pkg install -y proot-distro
if ! proot-distro login strata-modal -- /bin/true >/dev/null 2>&1; then
    help_text="$(proot-distro install --help 2>&1 || true)"
    if [[ "$help_text" == *--name* ]]; then
        proot-distro install ubuntu:24.04 --name strata-modal
    else
        proot-distro install --override-alias strata-modal ubuntu
    fi
fi
proot-distro login strata-modal --bind "$source_dir:/opt/strata-source" -- /bin/bash /opt/strata-source/android_runtime/start-linux.sh
