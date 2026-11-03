#!/usr/bin/env python3
"""Prepare a disposable GitHub runner only. Downloads public, pinned tools; never handles signing keys."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PINNED = {
    'lib/xposed-api-82.jar': (
        'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar',
        'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'),
    'lib/r8.jar': (
        'https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.6.0-alpha09/r8-9.6.0-alpha09.jar',
        'a0423ca2913347705a6d0a255ee01b007424de722a28bf0dddecf047e0b2bd1f'),
}


def download(destination, url, digest):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
            raise RuntimeError('existing dependency checksum differs: ' + destination.name)
        return
    temporary = destination.with_suffix('.part')
    try:
        size = 0; checksum = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=90) as source, temporary.open('xb') as out:
            while True:
                data = source.read(131072)
                if not data:
                    break
                size += len(data)
                if size > 40 * 1024 * 1024:
                    raise RuntimeError('dependency download budget')
                checksum.update(data); out.write(data)
        if checksum.hexdigest() != digest:
            raise RuntimeError('dependency download checksum mismatch')
        temporary.rename(destination)
    finally:
        temporary.unlink(missing_ok=True)


def link(source, destination):
    if not source.is_file():
        raise RuntimeError('SDK tool missing: ' + str(source))
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() or destination.is_symlink():
        if destination.resolve() != source.resolve():
            raise RuntimeError('refusing to replace local tool: ' + str(destination))
    else:
        destination.symlink_to(source)


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('CI bootstrap only runs on disposable GitHub runners; local tools are not replaced')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    manager = shutil.which('sdkmanager')
    if not manager:
        managers = list(sdk.glob('cmdline-tools/*/bin/sdkmanager'))
        if not managers:
            raise RuntimeError('runner SDK manager missing; no unpinned bootstrap script will be executed')
        manager = str(sorted(managers)[-1])
    subprocess.run([manager, f'--sdk_root={sdk}', 'platforms;android-28', 'platforms;android-35', 'build-tools;35.0.0'],
                   input=b'y\n' * 100, check=True, timeout=600)
    tools = sdk / 'build-tools/35.0.0'
    for api in ('28', '35'):
        link(sdk / f'platforms/android-{api}/android.jar', ROOT / f'build-tools/platforms/android-{api}/android.jar')
    link(tools / 'lib/apksigner.jar', ROOT / 'build-tools/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar')
    # Compatibility path only; the real SDK signer is 35.0.0 and its provenance is recorded below.
    for name, (url, digest) in PINNED.items():
        download(ROOT / 'build-tools' / name, url, digest)
    for tool in ('aapt', 'zipalign'):
        if not (tools / tool).is_file():
            raise RuntimeError('required AAPT1/zipalign tool missing')
    with open(os.environ['GITHUB_PATH'], 'a') as out:
        out.write(str(tools) + '\n')
    print('Prepared SDK platforms 28/35, SDK build-tools 35.0.0, SHA256-pinned R8 9.6.0-alpha09 and Xposed API82.')
    print('Native tools are runner-specific; no signing material downloaded or generated.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('CI bootstrap failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
