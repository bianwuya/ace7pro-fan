#!/usr/bin/env python3
"""Explicit CI packaging/signing/draft commands. No key upload, tag creation or final publishing."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import posixpath
from urllib.parse import urlsplit, quote
import shutil
import subprocess
import sys
import tempfile

import project

ROOT = project.ROOT
REPOSITORY = 'bianwuya/ace7pro-fan'
HEADINGS = ('新增', '修复', '验证与限制')


def notes_text(text, version):
    if not text.startswith('# ' + version + '\n'):
        raise project.ProjectError('release notes heading must exactly match APP_VERSION')
    sections = {}
    current = None
    for line in text.splitlines():
        if line.startswith('## '):
            current = line[3:].strip()
            if current in sections:
                raise project.ProjectError('duplicate release notes section')
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    for title in HEADINGS:
        section = '\n'.join(sections.get(title, [])).strip()
        bullets = [s[2:].strip() for s in section.splitlines() if s.startswith('- ')]
        if not bullets or any(not b for b in bullets):
            raise project.ProjectError('release notes need nonempty bullets in: ' + title)
        if re.search(r'(?i)\bTODO\b|\bTBD\b|待填写|稍后补充|请填写|占位', section):
            raise project.ProjectError('release notes contain placeholders in: ' + title)
    return text.strip() + '\n'


def plan(root=ROOT, tag=None, verify_refs=True):
    snap = project.Snapshot(root)
    cfg = project.config_for(snap)
    version = project.metadata(snap.read, cfg)
    name = f'releases/{version["app_version"]}/NOTES.md'
    text = notes_text(snap.read(name).decode('utf-8'), version['app_version'])
    expected = 'v' + version['app_version']
    if tag is not None:
        if tag != expected:
            raise project.ProjectError('tag must equal vAPP_VERSION: ' + expected)
        if verify_refs:
            head = project.git('rev-parse', 'HEAD', root=root).decode().strip()
            tagged = project.git('rev-parse', f'refs/tags/{tag}^{{commit}}', root=root).decode().strip()
            if tagged != head:
                raise project.ProjectError('tag does not point at the exact checked-out commit')
            project.git('merge-base', '--is-ancestor', head, 'refs/remotes/origin/main', root=root)
            for prior in project.git('tag', '--list', 'v*', root=root).decode().splitlines():
                if prior == tag:
                    continue
                result = subprocess.run(['git', 'show', f'{prior}:AndroidManifest.xml'], cwd=root,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    raise project.ProjectError('previous version tag has no readable Manifest: ' + prior)
                xml = project.ET.fromstring(result.stdout)
                code = int(xml.get(project.ANDROID + 'versionCode', '0'))
                if code >= version['version_code']:
                    raise project.ProjectError('versionCode must exceed every prior version tag: ' + prior)
    return cfg, version, text


def render_notes(text, version, commit):
    # Source-relative links must remain usable when the same text becomes a Release body/artifact.
    base = 'releases/' + version['app_version']
    def replace(match):
        target = match.group(1)
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or not parsed.path:
            return match.group(0)
        path = posixpath.normpath(posixpath.join(base, parsed.path))
        if path.startswith('../') or path.startswith('/'):
            raise project.ProjectError('release note link leaves the repository')
        url = 'https://github.com/' + REPOSITORY + '/blob/' + commit + '/' + quote(path)
        if parsed.fragment:
            url += '#' + parsed.fragment
        return '](' + url + ')'
    return re.sub(r'\]\(([^\s)]+)\)', replace, text)


def selected_tag():
    return os.environ.get('GITHUB_REF_NAME') if os.environ.get('GITHUB_REF_TYPE') == 'tag' else None


def new_output(name):
    output = ROOT / name
    if output.exists():
        raise project.ProjectError('output already exists; refusing to replace artifacts: ' + name)
    output.mkdir(parents=True)
    return output


def checksums(output):
    files = sorted(p for p in output.iterdir() if p.is_file() and p.name != 'SHA256SUMS.txt')
    (output / 'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in files))


def package_unsigned():
    cfg, version, notes = plan(tag=selected_tag())
    apk = ROOT / 'build' / (cfg['artifact_prefix'] + version['app_version'] + '-unsigned.apk')
    audit = project.audit_apk(apk, unsigned=True)
    output = new_output('dist/ci')
    shutil.copy2(apk, output / apk.name)
    commit = project.git('rev-parse', 'HEAD').decode().strip()
    (output / 'RELEASE_NOTES.md').write_text(render_notes(notes, version, commit))
    report = {'schema_version': 1, **version, **audit, 'source_commit': project.git('rev-parse', 'HEAD').decode().strip(),
              'created_utc': datetime.now(timezone.utc).isoformat(), 'signed': False,
              'purpose': 'unsigned validation artifact; not installable as an update',
              'oem_reference_test': 'SKIPPED in cloud; no private OEM sample uploaded',
              'toolchain': {'runner': 'ubuntu-24.04' if os.environ.get('GITHUB_ACTIONS') == 'true' else 'local',
                            'java': subprocess.run(['java', '-version'], stdout=subprocess.PIPE, stderr=subprocess.PIPE).stderr.decode().splitlines()[0],
                            'sdk_build_tools': '35.0.0' if os.environ.get('GITHUB_ACTIONS') == 'true' else 'local layout',
                            'compile_platform': 35, 'resource_platform': 28, 'r8': '9.6.0-alpha09', 'xposed': 82},
              'validation_scope': 'offline only; no phone, ROM or physical fan validation'}
    (output / 'BUILD_INFO.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    checksums(output)
    print('Packaged unsigned CI artifact with release notes and exact source/sha256 evidence (no secrets).')


def verify_unsigned_input(folder, cfg, version):
    expected = cfg['artifact_prefix'] + version['app_version'] + '-unsigned.apk'
    if set(p.name for p in folder.iterdir()) != {expected, 'BUILD_INFO.json', 'RELEASE_NOTES.md', 'SHA256SUMS.txt'}:
        raise project.ProjectError('unexpected unsigned artifact contents')
    for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        if '/' in name or name not in {expected, 'BUILD_INFO.json', 'RELEASE_NOTES.md'}:
            raise project.ProjectError('unsafe checksum entry')
        if hashlib.sha256((folder / name).read_bytes()).hexdigest() != digest:
            raise project.ProjectError('downloaded artifact checksum mismatch')
    report = json.loads((folder / 'BUILD_INFO.json').read_text())
    if report['source_commit'] != project.git('rev-parse', 'HEAD').decode().strip() or report.get('signed') is not False:
        raise project.ProjectError('downloaded artifact is not from this exact unsigned build')
    if report.get('app_version') != version['app_version']:
        raise project.ProjectError('artifact version mismatch')
    apk = folder / expected
    if hashlib.sha256(apk.read_bytes()).hexdigest() != report.get('sha256'):
        raise project.ProjectError('unsigned APK differs from build report')
    project.audit_apk(apk, unsigned=True)
    return apk, report


def require_ci_tag():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY or selected_tag() is None:
        raise project.ProjectError('sign/draft allowed only for a version tag in the authorized GitHub repository')


def sign():
    # Consume and remove them before plan/audit can spawn git/Java/native tool children.
    encoded = os.environ.pop('ACEFAN_KEYSTORE_B64', '')
    password = os.environ.pop('ACEFAN_STORE_PASSWORD', '')
    require_ci_tag()
    cfg, version, notes = plan(tag=selected_tag())
    if not encoded or not password:
        raise project.ProjectError('signing Secrets are not configured; refusing unsigned/debug fallback or replacement keys')
    if '\n' in password or '\r' in password or len(encoded) > 65536:
        raise project.ProjectError('invalid signing Secret format (contents not printed)')
    unsigned, report = verify_unsigned_input(ROOT / 'dist/ci', cfg, version)
    output = new_output('dist/signed')
    temporary_root = Path(os.environ['RUNNER_TEMP']) / 'acefan-signing'
    temporary_root.mkdir(mode=0o700, exist_ok=False)
    try:
        try:
            key = base64.b64decode(encoded, validate=True)
        except ValueError:
            raise project.ProjectError('invalid encoded signing key (contents not printed)') from None
        keyfile = temporary_root / 'release.jks'
        passfile = temporary_root / 'pass.txt'
        for path, data in ((keyfile, key), (passfile, password.encode())):
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(data)
        apk = output / (cfg['artifact_prefix'] + version['app_version'] + '.apk')
        signer = ROOT / 'build-tools/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar'
        project.run(['java', '-cp', signer, 'com.android.apksigner.ApkSignerTool', 'sign',
                     '--ks', keyfile, '--ks-key-alias', 'acefan', '--ks-pass', 'file:' + str(passfile),
                     '--out', apk, unsigned])
        audit = project.audit_apk(apk)
        # The public certificate is pinned by project.json. A wrong key prevents all artifact upload/release steps.
        report.update(audit)
        report.update({'signed': True, 'tag': selected_tag(), 'unsigned_sha256': hashlib.sha256(unsigned.read_bytes()).hexdigest(),
                       'purpose': 'same-identity signed draft candidate; physical validation still required'})
        (output / 'BUILD_INFO.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        footer = '\n## 构建信息\n\n- 源码提交：`' + report['source_commit'] + '`\n- APK SHA256：`' + audit['sha256'] + '`\n'
        footer += '- 本包通过自动化离线构建/签名审计；私有OEM样本测试在云端跳过，不代表多系统或风扇物理效果验收。\n'
        (output / 'RELEASE_NOTES.md').write_text(render_notes(notes, version, report['source_commit']) + footer)
        # The v4 sidecar belongs to the former unsigned/path names and is not a useful Release asset.
        for sidecar in output.glob('*.idsig'):
            sidecar.unlink()
        checksums(output)
        if any(p.suffix == '.idsig' for p in output.iterdir()):
            raise project.ProjectError('unexpected signature sidecar in signed bundle')
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)
    print('Signed with the pinned original identity; key files removed. Draft candidate only, no publication.')


def draft():
    require_ci_tag()
    cfg, version, _ = plan(tag=selected_tag())
    folder = ROOT / 'dist/signed'
    apk = folder / (cfg['artifact_prefix'] + version['app_version'] + '.apk')
    project.audit_apk(apk)
    report = json.loads((folder / 'BUILD_INFO.json').read_text())
    if report.get('source_commit') != project.git('rev-parse', 'HEAD').decode().strip() or report.get('signed') is not True:
        raise project.ProjectError('signed report is not for this checked-out source')
    existing = subprocess.run(['gh', 'release', 'view', selected_tag(), '--repo', REPOSITORY], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if existing.returncode == 0:
        raise project.ProjectError('Release already exists; refusing to overwrite its notes or assets')
    if b'not found' not in existing.stderr.lower():
        raise project.ProjectError('cannot establish absence of existing Release; refusing mutation')
    args = ['gh', 'release', 'create', selected_tag(), '--repo', REPOSITORY, '--verify-tag', '--draft',
            '--title', 'ACE7pro 风扇助手 ' + version['app_version'], '--notes-file', folder / 'RELEASE_NOTES.md']
    # A descriptive suffix such as -compat is not a pre-release; only explicit pre-release stems are.
    if re.search(r'(?i)(?:^|[-.])(?:alpha|beta|rc|pre)(?:[.\d]|$)', version['app_version']):
        args.append('--prerelease')
    args += [folder / name for name in (apk.name, 'BUILD_INFO.json', 'SHA256SUMS.txt', 'RELEASE_NOTES.md')]
    project.run(args)
    print('Created a DRAFT Release. User must review and explicitly Publish; repository visibility was not changed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'package-unsigned', 'sign', 'draft'])
    parser.add_argument('--tag')
    args = parser.parse_args()
    if args.action == 'plan':
        _, version, _ = plan(tag=args.tag or selected_tag())
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
                out.write('version=' + version['app_version'] + '\n')
        print('Release plan valid:', version['app_version'], '; required 新增/修复/验证与限制 notes present.')
    elif args.action == 'package-unsigned':
        package_unsigned()
    elif args.action == 'sign':
        sign()
    else:
        draft()


if __name__ == '__main__':
    try:
        main()
    except (project.ProjectError, OSError, ValueError, KeyError) as exc:
        print('Cloud release failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
