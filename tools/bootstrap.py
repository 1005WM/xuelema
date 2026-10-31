"""Download project-local, official build tools; does not accept Android SDK licenses."""
from pathlib import Path
import hashlib
import shutil
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
import os
import time

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(url, target, checksum=None):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if checksum:
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == checksum: return target
        with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=40) as response:
            pinned_url = response.geturl()
            total = int(response.headers['Content-Length'])
        offset = target.stat().st_size if target.exists() else 0
        if offset > total: target.write_bytes(b''); offset = 0
        print('Downloading verified archive in resumable chunks:', pinned_url, flush=True)
        with target.open('ab') as out:
            while offset < total:
                end = min(offset + 8 * 1024 * 1024, total) - 1
                for attempt in range(4):
                    try:
                        request = urllib.request.Request(pinned_url, headers={'Range':f'bytes={offset}-{end}'})
                        with urllib.request.urlopen(request, timeout=60) as response:
                            if response.status != 206 or not response.headers.get('Content-Range','').startswith(f'bytes {offset}-'):
                                raise RuntimeError('Server did not honor download range')
                            data = response.read()
                        if len(data) != end - offset + 1: raise RuntimeError('Truncated download range')
                        break
                    except Exception:
                        if attempt == 3: raise
                        time.sleep(1)
                out.write(data); out.flush(); offset += len(data)
                print(f'Archive {offset * 100 // total}%', flush=True)
        if hashlib.sha256(target.read_bytes()).hexdigest() != checksum: raise RuntimeError('Checksum mismatch: ' + str(target))
        return target
    if not target.exists():
        print('Downloading', url, flush=True)
        with urllib.request.urlopen(url, timeout=90) as src, target.open('wb') as out:
            shutil.copyfileobj(src, out)
    if checksum and hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + str(target))
    return target

base = 'https://raw.githubusercontent.com/gradle/gradle/v9.4.1/'
for name in ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar']:
    fetch(base + name, ROOT / name)
checksum = urllib.request.urlopen('https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256', timeout=30).read().decode().strip()
(ROOT / 'gradle/wrapper/gradle-wrapper.properties').write_text(
    'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip\n'
    'networkTimeout=120000\nvalidateDistributionUrl=true\n'
    'zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n'
    'distributionSha256Sum=' + checksum + '\n', encoding='utf-8')

java_home = os.environ.get('JAVA_HOME')
if not (TOOLS / 'jdk/bin/jlink.exe').exists() and (not java_home or not (Path(java_home) / 'bin/jlink.exe').exists()):
    url = 'https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip'
    checksum = urllib.request.urlopen(url.replace('/latest/', '/latest_sha256/'), timeout=40).read().decode().split()[0]
    pkg = {'link':url, 'name':url.rsplit('/',1)[1], 'checksum':checksum}
    jdkzip = fetch(pkg['link'], TOOLS / pkg['name'], pkg['checksum'])
    if not (TOOLS / 'jdk').exists():
        with zipfile.ZipFile(jdkzip) as z:
            prefix = z.namelist()[0].split('/')[0]
            z.extractall(TOOLS)
        (TOOLS / prefix).rename(TOOLS / 'jdk')

repo = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml', timeout=45).read())
packages = repo.findall('remotePackage')
cmd = next(p for p in packages if p.attrib['path'] == 'cmdline-tools;latest')
archive = next(a for a in cmd.findall('archives/archive') if a.findtext('host-os') == 'windows')
url = archive.findtext('complete/url')
sdkzip = fetch('https://dl.google.com/android/repository/' + url, TOOLS / url)
target = TOOLS / 'android-sdk/cmdline-tools/latest'
if not target.exists():
    with zipfile.ZipFile(sdkzip) as z:
        z.extractall(TOOLS / 'sdk-extract')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(TOOLS / 'sdk-extract/cmdline-tools'), str(target))
print('Tools ready. Android packages require the user to review SDK licenses.', flush=True)
