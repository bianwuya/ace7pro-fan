#!/usr/bin/env python3
"""Prepare a disposable GitHub runner only. Never handles signing keys."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
COMPILE_SDK = '37.2'
BUILD_TOOLS = '36.0.0'
GRADLE_VERSION = '9.7.1'
GRADLE_SHA256 = 'acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'
XPOSED_URL = 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar'
XPOSED_SHA256 = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'


def download(destination, url, digest, max_bytes):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        with destination.open('rb') as existing:
            if hashlib.file_digest(existing, 'sha256').hexdigest() != digest:
                raise RuntimeError('existing dependency checksum differs: ' + destination.name)
        return
    temporary = destination.with_suffix('.part')
    try:
        size = 0
        checksum = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=90) as source, temporary.open('xb') as out:
            while data := source.read(131072):
                size += len(data)
                if size > max_bytes:
                    raise RuntimeError('dependency download budget')
                checksum.update(data)
                out.write(data)
        if checksum.hexdigest() != digest:
            raise RuntimeError('dependency download checksum mismatch: ' + destination.name)
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


def prepare_gradle():
    tools = ROOT / 'build-tools'
    name = 'gradle-' + GRADLE_VERSION
    installed = tools / name
    if installed.exists() or installed.is_symlink():
        raise RuntimeError('refusing to reuse or replace an unverified Gradle distribution on the runner')
    with tempfile.TemporaryDirectory(prefix='gradle-ci-', dir=tools) as temporary:
        temp = Path(temporary)
        archive = temp / (name + '-bin.zip')
        download(archive, 'https://services.gradle.org/distributions/' + archive.name, GRADLE_SHA256, 200 * 1024 * 1024)
        subprocess.run(['unzip', '-q', archive, '-d', temp], check=True, timeout=120)
        if not os.access(temp / name / 'bin/gradle', os.X_OK):
            raise RuntimeError('verified Gradle distribution lacks an executable launcher')
        (temp / name).rename(installed)
    return installed / 'bin'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-only', action='store_true', help='SDK tools for auditing/signing the same-run APK; no compiler setup')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('CI bootstrap only runs on disposable GitHub runners; local tools are not replaced')
    sdk_root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk_root or not Path(sdk_root).is_dir():
        raise RuntimeError('runner Android SDK missing')
    sdk = Path(sdk_root).resolve()
    manager = shutil.which('sdkmanager')
    if not manager:
        managers = sorted(sdk.glob('cmdline-tools/*/bin/sdkmanager'))
        if not managers:
            raise RuntimeError('runner SDK manager missing; no bootstrap script will be executed')
        manager = str(managers[-1])
    packages = [f'build-tools;{BUILD_TOOLS}']
    if not args.audit_only:
        packages.append(f'platforms;android-{COMPILE_SDK}')
    subprocess.run([manager, f'--sdk_root={sdk}', *packages], input=b'y\n' * 100, check=True, timeout=600)
    tools = sdk / f'build-tools/{BUILD_TOOLS}'
    link(tools / 'aapt2', ROOT / 'build-tools/bin/aapt2')
    link(tools / 'lib/apksigner.jar', ROOT / 'build-tools/lib/apksigner.jar')
    if not (tools / 'zipalign').is_file():
        raise RuntimeError('required zipalign tool missing')
    path_entries = [tools]
    if not args.audit_only:
        download(ROOT / 'build-tools/lib/xposed-api-82.jar', XPOSED_URL, XPOSED_SHA256, 1024 * 1024)
        path_entries.append(prepare_gradle())
    with open(os.environ['GITHUB_PATH'], 'a') as out:
        for entry in path_entries:
            out.write(str(entry) + '\n')
    print(f'Prepared SDK Build Tools {BUILD_TOOLS} for APK alignment and audit.')
    if not args.audit_only:
        print(f'Prepared SDK platform {COMPILE_SDK}, SHA256-pinned Gradle {GRADLE_VERSION} and compileOnly Xposed API82.')
    print('Native tools are runner-specific; no signing material downloaded or generated.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('CI bootstrap failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
