"""Public, checksum-verified release downloads. No Modal credentials are sent to GitHub."""
import hashlib,json,os,re,shutil,stat,tempfile,urllib.request
from pathlib import Path,PurePosixPath
from urllib.parse import urlparse
from zipfile import ZipFile

REPO='MetaMysteries8/strata'
API=f'https://api.github.com/repos/{REPO}/releases/latest'
COMPANION=(0,3,0)
HOSTS={'api.github.com','github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'}

def allowed(url):
    u=urlparse(url)
    return u.scheme=='https' and u.hostname in HOSTS and not u.username and not u.password and u.port in {None,443}

class Redirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        if not allowed(newurl):raise ValueError('Unexpected release download redirect')
        return super().redirect_request(req,fp,code,msg,headers,newurl)

def read(url,limit):
    if not allowed(url):raise ValueError('Unexpected release host')
    opener=urllib.request.build_opener(Redirects())
    req=urllib.request.Request(url,headers={'Accept':'application/vnd.github+json' if url==API else 'application/octet-stream','User-Agent':'Strata-runtime'})
    with opener.open(req,timeout=30) as response:
        data=response.read(limit+1)
        if len(data)>limit:raise ValueError('Release download exceeds size limit')
        return data

def validate(manifest,tag):
    if not re.fullmatch(r'v\d+\.\d+\.\d+',tag):raise ValueError('Unsupported release tag')
    if manifest.get('schema')!=1 or manifest.get('runtimeProtocol')!=1 or 'v'+manifest.get('version','')!=tag:raise ValueError('Unsupported release manifest')
    minimum=manifest.get('minCompanion','')
    if not re.fullmatch(r'\d+\.\d+\.\d+',minimum) or tuple(map(int,minimum.split('.')))>COMPANION:raise ValueError('Update the phone runtime before deploying this release')
    asset=manifest.get('runtime',{})
    if asset.get('name')!='Strata-Modal-Android.zip' or not isinstance(asset.get('size'),int) or not 0<asset['size']<=64*1024*1024 or not re.fullmatch('[a-f0-9]{64}',asset.get('sha256','')):raise ValueError('Invalid runtime release asset')
    return manifest

def latest():
    release=json.loads(read(API,2*1024*1024))
    if release.get('draft') or release.get('prerelease'):raise ValueError('A stable published release is required')
    tag=release.get('tag_name','')
    if not re.fullmatch(r'v\d+\.\d+\.\d+',tag):raise ValueError('Unsupported release tag')
    manifest=json.loads(read(f'https://github.com/{REPO}/releases/download/{tag}/release-manifest.json',65536))
    return validate(manifest,tag)

def install(root,manifest):
    tag='v'+manifest['version'];validate(manifest,tag)
    releases=root/'releases';releases.mkdir(parents=True,exist_ok=True,mode=0o700)
    target=releases/tag
    expected=manifest['runtime']
    if target.exists():
        marker=target/'verified-sha256'
        if marker.exists() and marker.read_text()==expected['sha256'] and all((target/'strata-modal'/name).is_file() for name in ['modal_app.py','strata_server/api.py','android_runtime/deploy_endpoint.py']):return target/'strata-modal'
        raise ValueError('Release version already staged with different bytes')
    data=read(f'https://github.com/{REPO}/releases/download/{tag}/{expected["name"]}',64*1024*1024)
    if len(data)!=expected['size'] or hashlib.sha256(data).hexdigest()!=expected['sha256']:raise ValueError('Runtime release checksum mismatch')
    temp=Path(tempfile.mkdtemp(prefix='release-',dir=releases))
    archive=temp/'runtime.zip'
    try:
        archive.write_bytes(data)
        with ZipFile(archive) as z:
            entries=z.infolist();seen=set();total=0
            if len(entries)>4096:raise ValueError('Runtime archive has too many entries')
            for entry in entries:
                name=entry.filename;p=PurePosixPath(name)
                total+=entry.file_size
                normalized=p.as_posix()
                if p.is_absolute() or '\\' in name or '..' in p.parts or not p.parts or p.parts[0]!='strata-modal' or normalized in seen or stat.S_ISLNK(entry.external_attr>>16) or entry.file_size>4*1024*1024 or total>128*1024*1024:raise ValueError('Unsafe runtime release archive')
                seen.add(normalized)
                dest=temp.joinpath(*p.parts)
                if entry.is_dir():dest.mkdir(parents=True,exist_ok=True)
                else:
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    with z.open(entry) as source,dest.open('wb') as out:shutil.copyfileobj(source,out)
        for file in ['modal_app.py','strata_server/api.py','android_runtime/deploy_endpoint.py']:
            if not (temp/'strata-modal'/file).is_file():raise ValueError('Incomplete runtime release')
        archive.unlink()
        (temp/'verified-sha256').write_text(expected['sha256'])
        temp.rename(target)
        return target/'strata-modal'
    finally:
        if temp.exists():shutil.rmtree(temp)

def active_source(root,fallback):
    pointer=root/'active-source.json'
    if not pointer.exists():return fallback
    value=json.loads(pointer.read_text())
    path=(root/value['path']).resolve()
    if not path.is_relative_to((root/'releases').resolve()) or not (path/'modal_app.py').is_file():raise ValueError('Invalid saved release source')
    return path
