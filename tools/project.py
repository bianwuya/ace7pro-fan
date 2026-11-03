#!/usr/bin/env python3
"""Local project lifecycle. No device commands, network requests or secret printing."""
from __future__ import annotations

import argparse
import ast
import contextlib
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
MAX_BLOB = 4 * 1024 * 1024
TEXT_SUFFIXES = {'.java', '.py', '.sh', '.json', '.xml', '.md', '.yml', '.yaml', '.txt', '.html', '.toml', '.properties'}
SECRET_PATTERNS = (
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    re.compile(rb'\b(?:ghp_|github_pat_|sk-(?:proj-)?)[A-Za-z0-9_-]{24,}\b'),
    re.compile(rb'(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|storepass|keypass)\s*[:=]\s*[\x22\x27][A-Za-z0-9/+=_.-]{16,}[\x22\x27]'),
)


class ProjectError(Exception):
    pass


def run(args, root=ROOT, capture=False):
    result = subprocess.run([str(a) for a in args], cwd=root,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        # Tools used here must never receive password values as arguments.
        detail = result.stderr.decode('utf-8', 'replace').strip() if capture else ''
        raise ProjectError(f'{Path(str(args[0])).name} failed ({result.returncode})' + (f': {detail[:1000]}' if detail else ''))
    return result.stdout if capture else b''


def git(*args, root=ROOT):
    return run(['git', *args], root, capture=True)


class Snapshot:
    """Read the actual Git index for hooks; worktree mode also includes new, non-ignored files."""
    def __init__(self, root=ROOT, staged=False):
        self.root, self.staged = Path(root), staged
        self.objects = {}
        self.modes = {}
        for entry in git('ls-files', '--stage', '-z', root=self.root).split(b'\0'):
            if not entry:
                continue
            metadata, path = entry.split(b'\t', 1)
            mode, oid, stage = metadata.decode().split()
            name = path.decode('utf-8')
            if stage != '0':
                raise ProjectError(f'unresolved merge conflict: {name}')
            self.objects[name], self.modes[name] = oid, mode
        self.paths = set(self.objects)
        if not staged:
            new = git('ls-files', '--others', '--exclude-standard', '-z', root=self.root)
            self.paths.update(p.decode('utf-8') for p in new.split(b'\0') if p)
            self.paths = {p for p in self.paths if (self.root / p).exists() or (self.root / p).is_symlink()}

    def read(self, name):
        if name not in self.paths:
            raise ProjectError(f'missing file: {name}')
        if self.staged:
            oid = self.objects[name]
            size = int(git('cat-file', '-s', oid, root=self.root))
            if size > MAX_BLOB:
                raise ProjectError(f'blob exceeds inspection budget: {name}')
            return git('cat-file', 'blob', oid, root=self.root)
        path = self.root / name
        if path.is_symlink():
            raise ProjectError(f'symlink is not permitted in managed source: {name}')
        if path.stat().st_size > MAX_BLOB:
            raise ProjectError(f'file exceeds inspection budget: {name}')
        return path.read_bytes()


def forbidden_path(name):
    p = PurePosixPath(name)
    parts = [x.lower() for x in p.parts]
    if p.is_absolute() or '..' in parts:
        return True
    if any(x in {'build', 'build-tools', 'dist', 'private', 'local', '__pycache__', '.git', '.venv'} for x in parts):
        return True
    base = p.name.lower()
    if base == '.env' or base.startswith('.env.') or base in {'pass.txt', 'credentials.json', 'secrets.json'}:
        return True
    if base.endswith(('.apk', '.aab', '.dex', '.class', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key',
                      '.db', '.db-wal', '.db-shm', '.sqlite', '.sqlite3', '.log', '.pyc', '.zip', '.tar.gz', '.tgz')):
        return True
    if name == 'wake-diagnostic/samples.txt':
        return True
    # Preserve the already-tracked historical source snapshot; don't add new private rollback trees.
    if parts and parts[0] == 'releases' and len(parts) > 1 and '-before-' in parts[1]:
        return parts[1] != '0.1.0-before-wakefix'
    return False


def contains_secret(data):
    return any(p.search(data) for p in SECRET_PATTERNS)


def metadata(read, config):
    """Versions remain in BuildInfo + Manifest; project.json is not another version source."""
    manifest = ET.fromstring(read('AndroidManifest.xml'))
    build = read('src/dev/eta/acefan/BuildInfo.java').decode('utf-8')
    values = {}
    for key in ('APP_VERSION', 'HOOK_VERSION'):
        matches = re.findall(r'\b' + key + r'\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?)"\s*;', build)
        if len(matches) != 1:
            raise ProjectError(f'expected one valid {key}')
        values[key.lower()] = matches[0]
    if manifest.get('package') != config['package_name']:
        raise ProjectError('unexpected package name')
    if manifest.get(ANDROID + 'versionName') != values['app_version']:
        raise ProjectError('Manifest versionName differs from APP_VERSION')
    values['version_code'] = int(manifest.get(ANDROID + 'versionCode', '0'))
    if values['version_code'] <= 0:
        raise ProjectError('versionCode must be positive')
    sdk = manifest.find('uses-sdk')
    for name, key in (('minSdkVersion', 'min_sdk'), ('targetSdkVersion', 'target_sdk')):
        if sdk is None or int(sdk.get(ANDROID + name, '0')) != config[key]:
            raise ProjectError(f'unexpected {name}')
    permissions = sorted(e.get(ANDROID + 'name') for e in manifest if e.tag.startswith('uses-permission'))
    if permissions != sorted(config['allowed_permissions']):
        raise ProjectError('requested permissions differ from project contract')
    application = manifest.find('application')
    if application is None:
        raise ProjectError('missing application')
    metas = {e.get(ANDROID + 'name'): e.get(ANDROID + 'value') for e in application.findall('meta-data')}
    if metas.get('xposedmodule') != 'true' or int(metas.get('xposedminversion', '0')) != config['xposed_min_api']:
        raise ProjectError('Xposed module metadata differs from contract')
    if read('assets/xposed_init').decode().strip() != config['xposed_entry']:
        raise ProjectError('unexpected Xposed entry')
    arrays = ET.fromstring(read('res/values/arrays.xml'))
    scopes = arrays.findall("string-array[@name='xposed_scope']")
    if len(scopes) != 1 or [(i.text or '').strip() for i in scopes[0].findall('item')] != config['xposed_scope']:
        raise ProjectError('scope differs from target-only contract')
    return values


def config_for(snapshot):
    config = json.loads(snapshot.read('project.json'))
    if config.get('schema_version') != 1:
        raise ProjectError('unsupported project.json schema')
    if not re.fullmatch(r'[a-f0-9]{64}', config.get('signer_certificate_sha256', '')):
        raise ProjectError('invalid public signing certificate digest')
    return config


def link_errors(name, text, paths):
    errors = []
    for target in re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
        parsed = urlsplit(target.strip('<>'))
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), unquote(parsed.path)))
        if resolved == '..' or resolved.startswith('../') or resolved.startswith('/'):
            errors.append(f'{name}: link leaves repository: {target}')
        elif resolved not in paths and not any(p.startswith(resolved.rstrip('/') + '/') for p in paths):
            errors.append(f'{name}: broken local link: {target}')
    return errors


def check(snapshot, quiet=False):
    errors = []
    try:
        cfg = config_for(snapshot)
        version = metadata(snapshot.read, cfg)
        for required in cfg['required_files']:
            if required not in snapshot.paths:
                errors.append(f'missing required project file: {required}')
    except (ProjectError, ValueError, KeyError, ET.ParseError) as exc:
        errors.append(str(exc)); cfg = {}; version = {}
    for name in sorted(snapshot.paths):
        if forbidden_path(name):
            errors.append(f'private/generated artifact must not be tracked: {name}')
            continue
        if snapshot.modes.get(name) in {'120000', '160000'}:
            errors.append(f'symlink/submodule is not covered by source policy: {name}')
            continue
        try:
            data = snapshot.read(name)
            if contains_secret(data):
                errors.append(f'possible secret in {name} (value omitted)')
            is_text = PurePosixPath(name).suffix in TEXT_SUFFIXES or name.startswith('.githooks/') or name == '.gitignore'
            if not is_text:
                continue
            text = data.decode('utf-8')
            if name.endswith('.py'):
                ast.parse(text, filename=name)
            if name.endswith('.sh') or name.startswith('.githooks/'):
                result = subprocess.run(['sh', '-n'], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    errors.append(f'shell syntax error in {name}')
            if name.endswith('.json'):
                json.loads(text)
            if name.endswith('.xml'):
                ET.fromstring(text)
            managed_doc = name in {'README.md', 'AGENTS.md', 'COMPATIBILITY.md', 'CHANGELOG.md', 'TASKS.md'} or name.startswith('knowledge/')
            if managed_doc and name.endswith('.md'):
                errors.extend(link_errors(name, text, snapshot.paths))
            if name == 'README.md' and version and version['app_version'] not in text.splitlines()[0]:
                errors.append('README heading must identify current APP_VERSION')
        except (ProjectError, OSError, UnicodeError, SyntaxError, ValueError, ET.ParseError) as exc:
            errors.append(f'{name}: {type(exc).__name__} while checking source')
    diff = subprocess.run(['git', 'diff', '--check', *(['--cached'] if snapshot.staged else [])],
                          cwd=snapshot.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if diff.returncode:
        errors.append('git diff --check failed; inspect whitespace/conflict markers')
    if errors:
        for error in errors:
            print('FAIL:', error, file=sys.stderr)
        raise ProjectError(f'{len(errors)} project check(s) failed')
    if not quiet:
        mode = 'staged index' if snapshot.staged else 'worktree'
        print(f'PASS check ({mode}): {len(snapshot.paths)} files; metadata, source isolation, secret patterns, syntax, local documentation links')
        print('NOTE: pattern scanning is not a full secret audit; static checks do not certify fan safety.')
    return cfg, version


def doctor(root=ROOT, require_build=False):
    required = ['git', 'python3', 'java', 'javac', 'jar', 'aapt', 'zipalign', 'sh']
    dependencies = ['build-tools/lib/xposed-api-82.jar', 'build-tools/lib/r8.jar',
                    'build-tools/platforms/android-35/android.jar', 'build-tools/platforms/android-28/android.jar',
                    'build-tools/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar']
    missing = []
    for name in required:
        found = shutil.which(name)
        print(('OK   ' if found else 'MISS ') + name)
        if not found:
            missing.append(name)
    for name in dependencies:
        found = (root / name).is_file()
        print(('OK   ' if found else 'MISS ') + name)
        if not found:
            missing.append(name)
    signing_missing = []
    for name in ['build-tools/keys/pilot.jks', 'build-tools/keys/pass.txt']:
        found = (root / name).is_file()
        print(('OK   ' if found else 'MISS ') + name + ' (presence only; not read or printed)')
        if not found:
            signing_missing.append(name)
    reference = (root / 'build/compat-fixtures/reference.apk').is_file()
    print('OEM reference: ' + ('available locally; not distributable' if reference else 'absent; real-OEM test will be SKIPPED'))
    if missing and require_build:
        raise ProjectError('missing build prerequisites; no installation/download attempted')
    return not missing and not signing_missing


def test(root=ROOT):
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py', '-v'], root)
    run(['sh', 'tests/run.sh'], root)


def verify(root=ROOT):
    cfg, version = check(Snapshot(root))
    doctor(root, require_build=True)
    test(root)
    build = root / 'build'; build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='project-compile-', dir=build) as temporary:
        sources = sorted(str(p.relative_to(root)) for p in (root / 'src').rglob('*.java'))
        run(['javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-classpath',
             'build-tools/platforms/android-35/android.jar:build-tools/lib/xposed-api-82.jar',
             '-d', temporary, *sources], root)
    print('PASS verify: static gates + management/Java tests + all Android sources compile. No installation or physical validation.')
    return cfg, version


def dex_defined_classes(data):
    """Parse only DEX class definitions; merely referencing Xposed API is expected."""
    if len(data) < 112 or data[:4] != b'dex\n':
        raise ProjectError('invalid DEX in APK')
    def u32(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ProjectError('DEX table bounds')
        return struct.unpack_from('<I', data, offset)[0]
    if u32(32) != len(data):
        raise ProjectError('DEX file size mismatch')
    def table(count_at, offset_at, width):
        count, offset = u32(count_at), u32(offset_at)
        if count > 1000000 or offset + count * width > len(data):
            raise ProjectError('DEX inspection budget/bounds')
        return count, offset
    sc, so = table(56, 60, 4)
    strings = []
    for i in range(sc):
        at = u32(so + i * 4)
        for j in range(5):
            if at >= len(data):
                raise ProjectError('DEX string bounds')
            b = data[at]; at += 1
            if not b & 128:
                break
        else:
            raise ProjectError('DEX string length overflow')
        end = data.find(b'\0', at, min(len(data), at + 1024 * 1024))
        if end < 0:
            raise ProjectError('DEX string terminator/budget')
        strings.append(data[at:end].decode('utf-8', 'replace'))
    tc, to = table(64, 68, 4)
    indices = [u32(to + 4 * i) for i in range(tc)]
    if any(i >= len(strings) for i in indices):
        raise ProjectError('DEX type index')
    types = [strings[i] for i in indices]
    count, offset = table(96, 100, 32)
    definitions = [u32(offset + 32 * i) for i in range(count)]
    if any(i >= len(types) for i in definitions):
        raise ProjectError('DEX class index')
    return [types[i] for i in definitions]


def audit_zip(apk):
    with ZipFile(apk) as archive:
        if archive.testzip():
            raise ProjectError('APK ZIP integrity failure')
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ProjectError('duplicate APK entries')
        if archive.read('assets/xposed_init').strip() != b'dev.eta.acefan.FanHook':
            raise ProjectError('APK Xposed entry mismatch')
        definitions, dex_count = [], 0
        for item in archive.infolist():
            if item.filename.startswith('/') or '..' in PurePosixPath(item.filename).parts:
                raise ProjectError('unsafe APK entry path')
            if item.filename.endswith(('.jks', '.keystore', '.pem', '.key', '.apk', '.py')) or item.filename.startswith(('build-tools/', 'tests/', 'private/')):
                raise ProjectError('private/test payload in APK')
            if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', item.filename):
                if item.file_size > 64 * 1024 * 1024:
                    raise ProjectError('DEX audit budget')
                definitions.extend(dex_defined_classes(archive.read(item)))
                dex_count += 1
        if not dex_count:
            raise ProjectError('APK has no code')
        if any(n.startswith(('Lde/robv/android/xposed/', 'Lio/github/libxposed/')) for n in definitions):
            raise ProjectError('Xposed API classes were bundled instead of compileOnly')


def audit_apk(apk, root=ROOT, cfg=None, version=None, unsigned=False):
    if cfg is None or version is None:
        snap = Snapshot(root); cfg = config_for(snap); version = metadata(snap.read, cfg)
    apk = Path(apk).resolve()
    audit_zip(apk)
    signer = root / 'build-tools/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar'
    certificate = None
    command = ['java', '-cp', signer, 'com.android.apksigner.ApkSignerTool',
               'verify', '--verbose', '--print-certs', apk]
    if unsigned:
        result = subprocess.run([str(a) for a in command], cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode == 0:
            raise ProjectError('expected an unsigned validation APK, found a signed APK')
        if b'Missing META-INF/MANIFEST.MF' not in result.stderr + result.stdout and b'No signatures' not in result.stderr + result.stdout:
            raise ProjectError('unsigned APK verification failed for a reason other than missing signatures')
    else:
        output = run(command, root, capture=True).decode('utf-8', 'replace')
        certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', output)
        if len(certs) != 1 or certs[0].lower() != cfg['signer_certificate_sha256']:
            raise ProjectError('APK signing identity differs from pinned public certificate')
        if 'Verified using v3 scheme (APK Signature Scheme v3): true' not in output:
            raise ProjectError('expected Android9+ v3 signature')
        certificate = certs[0].lower()
    run(['zipalign', '-c', '4', apk], root)
    badging = run(['aapt', 'dump', 'badging', apk], root, capture=True).decode('utf-8', 'replace')
    found = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not found or found.groups() != (cfg['package_name'], str(version['version_code']), version['app_version']):
        raise ProjectError('APK package/version differs from source')
    for key, expected in [('sdkVersion', cfg['min_sdk']), ('targetSdkVersion', cfg['target_sdk'])]:
        found = re.search(rf"^{key}:'([^']+)'", badging, re.MULTILINE)
        if not found or int(found.group(1)) != expected:
            raise ProjectError(f'APK {key} differs from contract')
    permissions = re.findall(r"^uses-permission(?:-[^:]+)?: name='([^']+)'", badging, re.MULTILINE)
    if sorted(permissions) != sorted(cfg['allowed_permissions']):
        raise ProjectError('APK permissions differ from contract')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    kind = 'UNSIGNED (not installable)' if unsigned else 'pinned signer/v3'
    print(f'PASS APK audit: {kind}, package/version, alignment, permission/entry isolation; sha256={digest}')
    return {'sha256': digest, 'signer_certificate_sha256': certificate}


def dirty(root=ROOT):
    return bool(git('status', '--porcelain', '-z', root=root))


def release(output, root=ROOT):
    if dirty(root):
        raise ProjectError('release requires a clean committed worktree; inspect changes, do not reset them')
    output = Path(output).expanduser().resolve()
    try:
        inside = output.relative_to(root.resolve())
    except ValueError:
        inside = None
    if inside is not None and (not inside.parts or inside.parts[0] != 'dist'):
        raise ProjectError('inside this repository, release output must be under ignored dist/')
    if output.exists() or output.is_symlink():
        raise ProjectError('release output already exists; choose a new directory (nothing overwritten)')
    cfg, version = verify(root)
    commit = git('rev-parse', 'HEAD', root=root).decode().strip()
    if dirty(root):
        raise ProjectError('tracked/new files changed during verification')
    run(['sh', 'build.sh'], root)
    apk = root / 'build' / (cfg['artifact_prefix'] + version['app_version'] + '.apk')
    audit = audit_apk(apk, root, cfg, version)
    if dirty(root) or git('rev-parse', 'HEAD', root=root).decode().strip() != commit:
        raise ProjectError('source changed during build; do not publish this run')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.acefan-release-', dir=output.parent) as temporary:
        stage = Path(temporary)
        shutil.copy2(apk, stage / apk.name)
        for name in ('README.md', 'COMPATIBILITY.md', 'CHANGELOG.md'):
            shutil.copy2(root / name, stage / name)
        run(['git', 'archive', '--format=zip', f'--output={stage / "source.zip"}', commit], root)
        with ZipFile(stage / 'source.zip') as archive:
            if any(forbidden_path(n) for n in archive.namelist() if not n.endswith('/')):
                raise ProjectError('source archive includes a private/generated file')
        report = {'schema_version': 1, 'project_id': cfg['project_id'], 'source_commit': commit,
                  'created_utc': datetime.now(timezone.utc).isoformat(), 'package_name': cfg['package_name'],
                  **version, **audit, 'automated_checks': ['source/index policy', 'management tests', 'Java regression tests',
                  'all Android sources compiled', 'D8/resources/signature/alignment', 'APK/source archive audit'],
                  'oem_reference_available': (root / 'build/compat-fixtures/reference.apk').is_file(),
                  'validation_scope': 'offline only; this package is NOT certified for physical fan effects or other ROM/framework combinations',
                  'installed_by_this_command': False}
        (stage / 'BUILD_INFO.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        (stage / 'VALIDATION.txt').write_text('Local verification package; no installation, tagging, pushing or device operations.\n'
            'All automated gates above passed. Check BUILD_INFO.json for exact commit and OEM-reference availability.\n'
            'OEM-reference absence means that test was SKIPPED, not passed. Synthetic fixtures are not extra firmware tests.\n'
            'Physical validation remains manual; see knowledge/STATE.md and TASKS.md inside source.zip.\n')
        sums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in sorted(stage.iterdir()))
        (stage / 'SHA256SUMS.txt').write_text(sums)
        # Move only to a new target. Do not merge with or overwrite an earlier distribution.
        if output.exists() or output.is_symlink():
            raise ProjectError('output appeared during build; refusing overwrite')
        os.rename(stage, output)
    print(f'Created local verification bundle: {output}')
    print('No git tag/push, install, service kill or reboot was performed.')


def install_hooks(root=ROOT):
    result = subprocess.run(['git', 'config', '--get', 'core.hooksPath'], cwd=root, stdout=subprocess.PIPE)
    current = result.stdout.decode().strip()
    if current and current != '.githooks':
        raise ProjectError('an existing core.hooksPath is configured; refusing to replace it')
    hooks = git('rev-parse', '--git-path', 'hooks', root=root).decode().strip()
    old = (root / hooks / 'pre-commit') if not Path(hooks).is_absolute() else Path(hooks) / 'pre-commit'
    if not current and old.exists():
        raise ProjectError('custom pre-commit already exists; integrate manually instead of replacing it')
    (root / '.githooks/pre-commit').chmod(0o755)
    run(['git', 'config', '--local', 'core.hooksPath', '.githooks'], root)
    print('Enabled local staged-only pre-commit check (.githooks). No global Git configuration changed.')


def status(root=ROOT):
    snap = Snapshot(root); cfg = config_for(snap); v = metadata(snap.read, cfg)
    print(f'{cfg["project_id"]}: App {v["app_version"]} / Hook {v["hook_version"]} / versionCode {v["version_code"]}')
    print('branch=' + git('branch', '--show-current', root=root).decode().strip())
    print('commit=' + git('rev-parse', '--short', 'HEAD', root=root).decode().strip())
    print('worktree=' + ('dirty (preserve changes)' if dirty(root) else 'clean'))
    print('remote_count=' + str(len(git('remote', root=root).decode().splitlines())) + ' (URLs not printed)')
    print('Device state is NOT queried. Read knowledge/STATE.md with its evidence timestamp.')


@contextlib.contextmanager
def build_lock(root=ROOT):
    # Process-scoped lock; no daemon, no polling or persistent service.
    import fcntl
    (root / 'build').mkdir(exist_ok=True)
    with (root / 'build/.project.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ProjectError('another management test/build is running') from None
        yield


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('status', 'doctor', 'test', 'verify', 'install-hooks'):
        sub.add_parser(name)
    p = sub.add_parser('check'); p.add_argument('--staged', action='store_true')
    p = sub.add_parser('release'); p.add_argument('--output', required=True)
    p = sub.add_parser('audit-apk'); p.add_argument('apk'); p.add_argument('--unsigned', action='store_true')
    args = parser.parse_args()
    try:
        if args.command in ('test', 'verify', 'release'):
            with build_lock():
                if args.command == 'test':
                    check(Snapshot()); test()
                elif args.command == 'verify':
                    verify()
                else:
                    release(args.output)
        elif args.command == 'check':
            check(Snapshot(staged=args.staged))
        elif args.command == 'doctor':
            return 0 if doctor() else 1
        elif args.command == 'status':
            status()
        elif args.command == 'audit-apk':
            audit_apk(args.apk, unsigned=args.unsigned)
        else:
            install_hooks()
        return 0
    except (ProjectError, OSError, ValueError, KeyError, ET.ParseError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
