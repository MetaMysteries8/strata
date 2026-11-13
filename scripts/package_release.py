import hashlib,json,os,re,shutil,subprocess
from pathlib import Path
from prepare import ROOT,runtime_zip

SIGNER='a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'

def main():
    version=json.loads((ROOT/'release.json').read_text())
    tools=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    built=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(built)],text=True)
    if f"versionCode='{version['versionCode']}'" not in badging or f"versionName='{version['versionName']}'" not in badging or "package: name='dev.doxi.strata.preview'" not in badging:raise ValueError('APK version/package does not match release.json')
    signing=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(built)],text=True)
    if SIGNER not in signing:raise ValueError('Unexpected APK signing certificate')
    output=ROOT/'dist';output.mkdir(exist_ok=True)
    apk=output/f"Strata-Preview-{version['versionName']}.apk"
    shutil.copyfile(built,apk)
    runtime=output/'Strata-Modal-Android.zip';runtime_zip(runtime)
    def asset(file):return dict(name=file.name,size=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    manifest=dict(schema=1,version=version['versionName'],versionCode=version['versionCode'],runtimeProtocol=1,minCompanion='0.3.0',signerSHA256=SIGNER,apk=asset(apk),runtime=asset(runtime))
    (output/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (output/'SHA256SUMS').write_text(''.join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(output.iterdir()) if p.is_file() and p.name!='SHA256SUMS'))
    print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
