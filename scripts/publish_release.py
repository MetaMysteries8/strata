"""Publish one immutable version after CI checks; retries never overwrite an existing release."""
import json,os,subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
v=json.loads((root/'release.json').read_text())
tag='v'+v['versionName'];repo=os.environ['GITHUB_REPOSITORY']
existing=subprocess.run(['gh','release','view',tag,'--repo',repo,'--json','isDraft'],capture_output=True,text=True)
draft=existing.returncode==0 and json.loads(existing.stdout)['isDraft']
if existing.returncode==0 and not draft:
    print(f'{tag} already exists; release assets left unchanged.')
    raise SystemExit(0)
notes=root/'dist/release-notes.md'
notes.write_text(f"Strata Preview {v['versionName']}\n\nDownload the APK on Android. It retains the Preview signing identity. Settings → App updates checks this repository for later releases.\n\nFor phone-only Modal deployment, use the runtime ZIP once; later use Update server from GitHub in the app. Modal account credentials stay in Termux.\n\nServer changes require explicit deployment and can incur charges. Review active jobs first. Native scanning remains experimental. See README and VERIFICATION for tested scope.\n")
args=['gh','release','create',tag,'--repo',repo,'--target',os.environ['GITHUB_SHA'],'--draft','--title',f"Strata Preview {v['versionName']}",'--notes-file',str(notes)]
args.extend(str(p) for p in sorted((root/'dist').iterdir()) if p.is_file() and p!=notes)
if draft:
    subprocess.run(['gh','release','upload',tag,'--repo',repo,'--clobber',*[str(p) for p in sorted((root/'dist').iterdir()) if p.is_file() and p!=notes]],check=True)
else:subprocess.run(args,check=True)
subprocess.run(['gh','release','edit',tag,'--repo',repo,'--draft=false','--latest'],check=True)
