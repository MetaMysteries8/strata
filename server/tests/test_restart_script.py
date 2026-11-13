import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest


@pytest.mark.parametrize('installed', [True, False])
def test_restart_uses_existing_environment_without_package_installation(tmp_path, installed):
    source = tmp_path / 'source with spaces' / 'android_runtime'
    source.mkdir(parents=True)
    script = source / 'start.sh'
    shutil.copyfile(Path(__file__).parents[1] / 'android_runtime/start.sh', script)
    binary = tmp_path / 'bin'
    binary.mkdir()
    log = tmp_path / 'calls.jsonl'
    fake = '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
with Path(os.environ['CALL_LOG']).open('a') as out:out.write(json.dumps([Path(sys.argv[0]).name,*sys.argv[1:]])+'\\n')
if Path(sys.argv[0]).name=='pkg':sys.exit(99)
if '/bin/test' in sys.argv and os.environ['INSTALLED']=='0':sys.exit(1)
'''
    for name in ('proot-distro', 'termux-wake-lock', 'termux-wake-unlock', 'pkg'):
        p = binary / name
        p.write_text(fake)
        p.chmod(0o755)
    env = {**os.environ, 'PATH': str(binary)+os.pathsep+os.environ['PATH'], 'CALL_LOG': str(log), 'INSTALLED': '1' if installed else '0'}
    result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=10)
    calls = [json.loads(line) for line in log.read_text().splitlines()]
    assert not any(c[0] == 'pkg' for c in calls)
    if installed:
        assert result.returncode == 0, result.stderr
        assert [c[0] for c in calls] == ['proot-distro', 'termux-wake-lock', 'proot-distro', 'termux-wake-unlock']
        assert str(source.parent)+':/opt/strata-source' in calls[2]
    else:
        assert result.returncode == 1 and 'one-time setup' in result.stderr.lower()
        assert len(calls) == 1
