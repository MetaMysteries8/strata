"""Recreate bundled assets; the native engine is pinned by commit and SHA-256."""
import hashlib,json,re,shutil,subprocess,urllib.request
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED

ROOT=Path(__file__).resolve().parents[1]
BRUSH_URL='https://raw.githubusercontent.com/SuleMareVientu/opensplat-android/1ea9bbb829d0a4d4a5e4783d3fec1eeb71834d90/samples/android-demo/libs/brush-aar-release.aar'
BRUSH_SHA='b86218bda0c3408328211ffde471c6543add711f9cba8f11419dd48978031ef1'

def runtime_zip(path):
    source=ROOT/'server'
    excluded={'tests','__pycache__','.pytest_cache','.venv','strata-data'}
    with ZipFile(path,'w',ZIP_DEFLATED) as z:
        for file in sorted(source.rglob('*')):
            rel=file.relative_to(source)
            if file.is_file() and not any(p in excluded for p in rel.parts) and file.name not in {'.env','runtime.key','modal.toml','connection.json','deployment.json'} and file.suffix not in {'.pyc','.key','.log'}:
                z.write(file,Path('strata-modal')/rel)

def main():
    version=json.loads((ROOT/'release.json').read_text())
    if not re.fullmatch(r'\d+\.\d+\.\d+',version['versionName']) or not isinstance(version['versionCode'],int) or version['versionCode']<=0:raise ValueError('Invalid release version')
    (ROOT/'server/strata_server/version.py').write_text('VERSION='+repr(version['versionName'])+'\n')
    html=ROOT/'app/src/main/assets/app.html'
    html.write_text(re.sub(r'Strata Preview \d+\.\d+\.\d+','Strata Preview '+version['versionName'],html.read_text()))
    file=ROOT/'app/libs/brush.aar';file.parent.mkdir(parents=True,exist_ok=True)
    if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=BRUSH_SHA:
        temp=file.with_suffix('.pending')
        try:
            with urllib.request.urlopen(BRUSH_URL,timeout=120) as response,temp.open('wb') as out:shutil.copyfileobj(response,out)
            if hashlib.sha256(temp.read_bytes()).hexdigest()!=BRUSH_SHA:raise ValueError('Native engine checksum mismatch')
            temp.replace(file)
        finally:temp.unlink(missing_ok=True)
    runtime_zip(ROOT/'app/src/main/assets/modal-runtime.zip')
    print('Native engine verified and runtime ZIP prepared.')

if __name__=='__main__':main()
