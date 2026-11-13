#!/bin/bash
set -euo pipefail
umask 077
if [ ! -x /opt/strata-venv/bin/python ]; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv ca-certificates
    python3 -m venv /opt/strata-venv
fi
if [ ! -f /opt/strata-venv/strata-0.3.0-ready ]; then
    /opt/strata-venv/bin/python -m pip install -r /opt/strata-source/android_runtime/requirements.txt
    touch /opt/strata-venv/strata-0.3.0-ready
fi
cd /opt/strata-source
exec /opt/strata-venv/bin/python -m android_runtime.companion
