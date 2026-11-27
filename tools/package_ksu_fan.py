#!/usr/bin/env python3
"""Fail-closed, offline KSU fan release builder; never installs or executes inputs.

The JSON schemas under packaging/ksu-fan describe the caller-supplied inputs.
Production requires a controlled local release record and matching device-load
report bytes. Explicit first-control commissioning instead requires a separate
record, build/check reports and an acknowledgement; it never asserts control
load success. Both channels retain all artifact/signature/CRC/preinit checks.
Local records are caller-supplied prerequisites,
NOT authenticated evidence: the caller can edit them. This tool checks their
bindings/hashes, not report truth, device identity, shell semantics or loadability.
Only project.audit_apk (the existing `project.py audit-apk` implementation) runs
external host tools. There is intentionally no unsigned/validation ZIP mode,
installer template generator, kernel build, module loader or network client.
"""
from __future__ import annotations

import argparse
import contextlib
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import sys
import tempfile
from zipfile import BadZipFile, ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

import project

ROOT = Path(__file__).resolve().parents[1]
MODULE_ID = 'horae_fan_spoof'
APK_MEMBER = 'app/ACEFan.apk'
KO_MEMBER = 'kernel/eta_fan_policy.ko'
REPLACEMENT_MEMBER = 'preinit/bin/oplus_fan.replacement'
HELPER_MEMBER = 'preinit/bin/fan-preinit'
PREINIT_CONF = 'preinit/preinit.conf'
PREINIT_COMMAND = 'exec bin/fan-preinit --apply'
STOCK_TARGET = '/vendor_dlkm/lib/modules/oplus_fan.ko'
STOCK_HASH = 'a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd'
MAX_PREINIT = 4 * 1024 * 1024
METADATA_FREE_MIN = 8 * 1024 * 1024
STRATEGIES = {'additive', 'preinit-replace'}
MAX_APK = 64 * 1024 * 1024
MAX_KO = 32 * 1024 * 1024
MAX_JSON = 512 * 1024
MAX_REPORT = 4 * 1024 * 1024
MAX_TEMPLATE = 256 * 1024
MAX_TEMPLATES = 1024 * 1024
MAX_PAYLOAD = 96 * 1024 * 1024
MAX_SECTIONS = 1024
MAX_SYMBOLS = 65536
MAX_VERSIONS = 4096
SHA_RE = re.compile(r'[a-f0-9]{64}')
SYMBOL_RE = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
PLACEHOLDER_RE = re.compile(
    r'(?i)\b(?:TODO|FIXME|TBD|PLACEHOLDER|REPLACE_ME|CHANGEME|UNVERIFIED|DUMMY|STUB)\b'
    r'|\{\{|\}\}|@@|__[A-Z][A-Z0-9_]+__|<(?:fill|replace|your)[^>]*>')
REQUIRED_TEMPLATES = frozenset({
    'module.prop', 'customize.sh', 'service.sh', 'uninstall.sh',
    'META-INF/com/google/android/update-binary',
    'META-INF/com/google/android/updater-script',
})
OPTIONAL_TEMPLATES = frozenset({'post-fs-data.sh', 'boot-completed.sh', 'action.sh', 'watchdog.sh'})
COMMISSIONING_TEMPLATES = frozenset({'fg-debug.sh', 'allow-control'})
FIRST_CONTROL_PROFILE = 'first-control'
FIRST_CONTROL_ACK = 'first-control-unverified-no-auto-install-or-reboot'
HARDWARE_KEYS = frozenset({
    'ro.product.device', 'ro.product.model', 'ro.soc.model', 'ro.board.platform',
})
LIMITS = [
    'Offline structural and hash checks only; no input is installed or loaded.',
    'Device hardware and passing build/check/load reports are caller assertions.',
    'A user-editable local record and matching hashes are not real-device proof.',
    'Template review is caller-supplied; shell behavior is not verified here.',
]

COMMISSIONING_LIMITS = [
    LIMITS[0],
    'FIRST CONTROL commissioning candidate; production runtime is NOT verified.',
    'Candidate control has NOT been tested; build/check are not control-load evidence.',
    'Optional observer evidence is observer-only, never candidate control-load proof.',
    LIMITS[2], LIMITS[3],
    'This builder never installs or reboots; either action requires a separate decision.',
]


class PackageError(Exception):
    """Rejected or incomplete input. No release ZIP may be published."""


@dataclass(frozen=True)
class Inputs:
    apk: Path
    ko: Path
    manifest: Path
    templates: Path
    release_record: Path | None = None
    build_report: Path | None = None
    check_report: Path | None = None
    load_report: Path | None = None
    preinit_helper: Path | None = None
    helper_enabled_manifest: Path | None = None
    commissioning_record: Path | None = None
    observer_report: Path | None = None
    confirm_first_control: str | None = None


@dataclass
class Plan:
    entries: dict[str, bytes]
    manifest: dict
    compatibility: dict


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical_json(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2,
                       allow_nan=False) + '\n').encode('utf-8')


def require(condition, message):
    if not condition:
        raise PackageError(message)


def keys(value, expected, label):
    require(isinstance(value, dict) and set(value) == set(expected),
            f'{label}: required fields are exactly {", ".join(sorted(expected))}')


def text(value, label, maximum=256, empty=False):
    require(isinstance(value, str) and (empty or bool(value.strip())) and len(value) <= maximum,
            f'{label}: missing/oversize text')
    require(all(ord(c) >= 32 and ord(c) != 127 for c in value), f'{label}: control character')
    require(not PLACEHOLDER_RE.search(value), f'{label}: placeholder/unreviewed marker')
    require(value.strip().lower() not in {'unknown', 'none', 'n/a', 'example', 'test', '0'},
            f'{label}: unspecified value')
    return value


def digest(value, label):
    require(isinstance(value, str) and SHA_RE.fullmatch(value) is not None,
            f'{label}: expected lowercase SHA-256')
    require(value != '0' * 64, f'{label}: zero placeholder digest')
    return value


def safe_member(name):
    require(isinstance(name, str) and len(name) <= 192 and bool(name), 'unsafe ZIP/template path')
    p = PurePosixPath(name)
    require(not p.is_absolute() and '\\' not in name and ':' not in name and
            all(part not in {'', '.', '..'} for part in name.split('/')) and
            re.fullmatch(r'[A-Za-z0-9_./-]+', name) is not None,
            'unsafe ZIP/template path')
    return name


def checked_path(path, directory=False):
    """No symlink components, including the explicit template directory itself."""
    path = Path(os.path.abspath(os.path.expanduser(str(path))))
    for component in reversed((path, *path.parents)):
        try:
            info = component.lstat()
        except OSError as exc:
            raise PackageError(f'missing/unreadable input: {path}') from exc
        require(not stat.S_ISLNK(info.st_mode), f'symlink input is forbidden: {component}')
    mode = path.lstat().st_mode
    require(stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode),
            f'input is not a regular {"directory" if directory else "file"}: {path}')
    return path


def read_bounded(path, maximum, label):
    path = checked_path(path)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as source:
            before = os.fstat(source.fileno())
            require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum,
                    f'{label}: empty input or size budget exceeded')
            data = source.read(maximum + 1)
            after = os.fstat(source.fileno())
        require(len(data) == before.st_size and len(data) <= maximum and
                (before.st_ino, before.st_dev, before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_ino, after.st_dev, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                f'{label}: input changed during read')
        return data
    except OSError as exc:
        raise PackageError(f'{label}: unable to read regular input') from exc


def decode_text(data, label):
    try:
        result = data.decode('utf-8')
    except UnicodeError as exc:
        raise PackageError(f'{label}: expected UTF-8 text') from exc
    require('\0' not in result and '\r' not in result, f'{label}: NUL/CR is forbidden')
    return result


def parse_json(data, label):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f'{label}: duplicate JSON key')
            result[key] = value
        return result

    def bad_constant(_):
        raise PackageError(f'{label}: non-finite JSON number')

    try:
        result = json.loads(decode_text(data, label), object_pairs_hook=pairs, parse_constant=bad_constant)
    except (ValueError, RecursionError) as exc:
        raise PackageError(f'{label}: invalid JSON') from exc
    require(isinstance(result, dict), f'{label}: expected JSON object')
    return result


def strategy_for(manifest):
    strategy = manifest.get('strategy', 'additive')
    require(isinstance(strategy, str) and strategy in STRATEGIES, 'manifest: unsupported strategy')
    return strategy


def valid_module_name(name, strategy):
    require(strategy in STRATEGIES, 'KO: unsupported strategy')
    return (name == 'oplus_fan' if strategy == 'preinit-replace' else
            bool(re.fullmatch(r'[a-z][a-z0-9_]{0,55}', name)) and name != 'oplus_fan')


def parse_elf64(data, *, strategy='additive', commissioning=False):
    """Bounded ELF64 LE ET_REL parser, not strings(1) or a loader.

    Supports the target's classic 64-byte modversion_info layout only. Extended
    section counts/indices, compressed sections, dynamic linking and dependencies
    on other loadable modules are deliberately unsupported and rejected.
    """
    require(64 <= len(data) <= MAX_KO, 'KO: truncated ELF header or size budget')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    ident, kind, machine, version, entry, phoff, shoff, flags, ehsize, phsize, phnum, shsize, shnum, shstr = header
    require(ident[:7] == b'\x7fELF\x02\x01\x01', 'KO: expected ELF64 little-endian version 1')
    require(kind == 1 and machine == 183 and version == 1, 'KO: expected AArch64 ET_REL')
    require(entry == 0 and flags == 0 and phoff == 0 and phnum == 0 and phsize in {0, 56},
            'KO: executable/program-header/dynamic layout is forbidden')
    require(ehsize == 64 and shsize == 64 and 1 < shnum <= MAX_SECTIONS and 0 < shstr < shnum,
            'KO: section count/layout unsupported or budget exceeded')
    require(shoff >= 64 and shoff % 8 == 0 and shoff + shnum * 64 <= len(data),
            'KO: truncated/invalid section table')
    rows = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * 64) for i in range(shnum)]
    require(not any(rows[0]), 'KO: malformed null section')
    require(rows[shstr][1] == 3 and rows[shstr][5] <= 1024 * 1024,
            'KO: invalid section name string table')

    def region(offset, size, label):
        require(offset <= len(data) and size <= len(data) - offset, f'KO: truncated {label}')
        return data[offset:offset + size]

    def cstring(table, offset, label, maximum=512):
        require(0 <= offset < len(table), f'KO: invalid {label} offset')
        end = table.find(b'\0', offset, min(len(table), offset + maximum + 1))
        require(end >= offset, f'KO: unterminated/oversize {label}')
        try:
            return table[offset:end].decode('ascii')
        except UnicodeError as exc:
            raise PackageError(f'KO: non-ASCII {label}') from exc

    names = region(rows[shstr][4], rows[shstr][5], 'section name table')
    require(names[:1] == b'\0', 'KO: malformed section name table')
    sections = {}
    file_alignment_observations = []
    require(not commissioning or strategy == 'preinit-replace',
            'KO: commissioning layout requires preinit-replace')
    occupied = [(0, 64), (shoff, shoff + shnum * 64)]
    for row in rows[1:]:
        name_at, stype, sflags, address, offset, size, link, info, alignment, entsize = row
        name = cstring(names, name_at, 'section name')
        require(name and name not in sections, 'KO: unnamed/duplicate section')
        require(stype != 0 and address == 0 and not sflags & 0x800,
                'KO: invalid/compressed section unsupported')
        require(stype not in {6, 11} and name not in {'.interp', '.dynamic', '.dynsym', '.dynstr'} and
                not name.startswith('.gnu.version'), 'KO: shared-library/dynamic linking is forbidden')
        require(alignment == 0 or (alignment <= 1024 * 1024 and alignment & (alignment - 1) == 0),
                'KO: invalid section alignment')
        require(link < shnum and size <= 128 * 1024 * 1024, 'KO: section index/memory budget')
        if stype != 8 and size:
            region(offset, size, name)
            if alignment > 1 and offset % alignment:
                # This known ET_REL candidate has two allocated section file
                # offsets that differ from their memory alignment. Preserve the
                # production refusal; first-control must review exact observations.
                require(commissioning and stype == 1 and
                        ((name == '.plt' and sflags == 2) or
                         (name == '.gnu.linkonce.this_module' and sflags == 3)),
                        'KO: misaligned section')
                file_alignment_observations.append(
                    {'section': name, 'offset': offset, 'size': size, 'alignment': alignment})
            require(not any(offset < end and offset + size > start for start, end in occupied),
                    'KO: overlapping file sections')
            occupied.append((offset, offset + size))
        sections[name] = row

    require('.modinfo' in sections and '__versions' in sections and '.symtab' in sections,
            'KO: .modinfo, __versions and .symtab are mandatory')
    mod = sections['.modinfo']
    require(mod[1] == 1 and 0 < mod[5] <= 128 * 1024, 'KO: invalid .modinfo')
    moddata = region(mod[4], mod[5], '.modinfo')
    require(moddata.endswith(b'\0'), 'KO: unterminated .modinfo')
    modinfo = {}
    repeatable = {'alias', 'parm', 'parmtype', 'firmware', 'import_ns'}
    for raw in moddata.split(b'\0'):
        if not raw:
            continue
        try:
            field = raw.decode('utf-8')
        except UnicodeError as exc:
            raise PackageError('KO: invalid .modinfo text') from exc
        key, sep, value = field.partition('=')
        require(sep and SYMBOL_RE.fullmatch(key) is not None, 'KO: malformed .modinfo entry')
        text(value, 'KO .modinfo value', maximum=4096, empty=True)
        previous = modinfo.setdefault(key, [])
        require((value not in previous or (commissioning and key == 'import_ns')) and
                (not previous or key in repeatable),
                f'KO: duplicate .modinfo {key}')
        if key in {'parm', 'parmtype'}:
            require(value.partition(':')[0] not in {v.partition(':')[0] for v in previous},
                    f'KO: duplicate .modinfo {key} parameter')
        previous.append(value)
    for key in ('name', 'vermagic', 'license', 'depends'):
        require(key in modinfo and len(modinfo[key]) == 1, f'KO: missing/singular .modinfo {key}')
    require(valid_module_name(modinfo['name'][0], strategy),
            'KO: invalid/OEM-overriding module name for selected strategy')
    require(modinfo['license'][0].strip() and modinfo['vermagic'][0].strip(), 'KO: empty license/vermagic')
    require(all(not any(values) for key, values in modinfo.items() if key in {'depends', 'softdep', 'weakdep', 'needed'}),
            'KO: external module/shared-library dependencies unsupported')
    require('modversions' in modinfo['vermagic'][0].split(), 'KO: vermagic must enable modversions')

    versions_row = sections['__versions']
    require(versions_row[1] == 1 and versions_row[9] in {0, 64} and
            0 < versions_row[5] <= MAX_VERSIONS * 64 and versions_row[5] % 64 == 0,
            'KO: unsupported/truncated __versions layout')
    versions_data = region(versions_row[4], versions_row[5], '__versions')
    crcs = {}
    for at in range(0, len(versions_data), 64):
        crc = struct.unpack_from('<Q', versions_data, at)[0]
        rawname = versions_data[at + 8:at + 64]
        name = cstring(rawname, 0, 'version symbol', maximum=55)
        require(SYMBOL_RE.fullmatch(name) is not None and name not in crcs and crc <= 0xffffffff and
                not any(rawname[len(name) + 1:]), 'KO: invalid/duplicate symbol CRC record')
        crcs[name] = f'0x{crc:08x}'
    require('module_layout' in crcs, 'KO: missing MODVERSIONS module_layout CRC')

    sym = sections['.symtab']
    require(sym[1] == 2 and sym[9] == 24 and sym[5] % 24 == 0 and 0 < sym[5] <= MAX_SYMBOLS * 24,
            'KO: unsupported/truncated .symtab')
    require(sum(row[1] == 2 for row in rows) == 1, 'KO: duplicate symbol tables')
    strings_row = rows[sym[6]]
    require(strings_row[1] == 3 and 0 < strings_row[5] <= 4 * 1024 * 1024, 'KO: invalid symbol string table')
    strings = region(strings_row[4], strings_row[5], 'symbol string table')
    symdata = region(sym[4], sym[5], '.symtab')
    require(not any(symdata[:24]), 'KO: malformed null symbol')
    imports = set()
    for at in range(24, len(symdata), 24):
        name_at, sinfo, other, section_index, value, size = struct.unpack_from('<IBBHQQ', symdata, at)
        require(section_index < shnum or section_index in {0xfff1, 0xfff2},
                'KO: unsupported/invalid symbol section index')
        name = cstring(strings, name_at, 'symbol name', maximum=4096)
        if section_index == 0 and name:
            require(sinfo >> 4 in {1, 2} and SYMBOL_RE.fullmatch(name) is not None,
                    'KO: unrecognized undefined symbol')
            imports.add(name)
    require(imports <= set(crcs) and set(crcs) <= imports | {'module_layout'},
            'KO: undefined imports and __versions CRC inventory differ')
    return {
        'elf_class': 64, 'byte_order': 'little', 'machine': 'AArch64', 'type': 'ET_REL',
        'name': modinfo['name'][0], 'vermagic': modinfo['vermagic'][0],
        'license': modinfo['license'][0], 'depends': [],
        'symbol_crcs': dict(sorted(crcs.items())), 'undefined_symbols': sorted(imports),
        **({'commissioning_duplicate_import_namespaces': [
            {'namespace': value, 'occurrences': modinfo['import_ns'].count(value)}
            for value in sorted(set(modinfo.get('import_ns', [])))
            if modinfo['import_ns'].count(value) > 1]}
           if commissioning and len(modinfo.get('import_ns', [])) != len(set(modinfo.get('import_ns', []))) else {}),
        **({'commissioning_file_alignment_observations': file_alignment_observations}
           if file_alignment_observations else {}),
        **({'versions_sha256': sha256(versions_data), 'versions_count': len(crcs)}
           if strategy == 'preinit-replace' else {}),
    }


def inspect_preinit_helper(data):
    """Parse, never run, a static unstripped helper and its immutable C manifest.

    The supported fan_manifest ABI is the helper source's 72-byte AArch64 struct.
    Requiring its named, read-only symbol deliberately rejects stripped/unknown
    builds instead of guessing from strings. This proves structural identity,
    not that arbitrary machine code implements the reviewed helper semantics.
    """
    require(64 <= len(data) < MAX_PREINIT, 'helper: truncated ELF or preinit size budget')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    ident, kind, machine, version, entry, phoff, shoff, flags, ehsize, phsize, phnum, shsize, shnum, shstr = header
    require(ident[:7] == b'\x7fELF\x02\x01\x01' and kind == 2 and machine == 183 and version == 1,
            'helper: expected ELF64 LE AArch64 EXEC')
    require(flags == 0 and ehsize == 64 and phsize == 56 and 0 < phnum <= 64 and
            phoff >= 64 and phoff + phnum * 56 <= len(data), 'helper: invalid/truncated program table')
    require(shsize == 64 and 1 < shnum <= MAX_SECTIONS and 0 < shstr < shnum and
            shoff >= 64 and shoff % 8 == 0 and shoff + shnum * 64 <= len(data),
            'helper: unstripped bounded section table is required')

    def region(offset, size, label):
        require(offset <= len(data) and size <= len(data) - offset, f'helper: truncated {label}')
        return data[offset:offset + size]

    def cstring(table, offset, label, maximum=512):
        require(0 <= offset < len(table), f'helper: invalid {label} offset')
        end = table.find(b'\0', offset, min(len(table), offset + maximum + 1))
        require(end >= offset, f'helper: unterminated/oversize {label}')
        try:
            value = table[offset:end].decode('ascii')
        except UnicodeError as exc:
            raise PackageError(f'helper: non-ASCII {label}') from exc
        require(all(32 <= ord(c) < 127 for c in value), f'helper: invalid {label} text')
        return value

    loads = []
    for i in range(phnum):
        ptype, pflags, offset, address, physical, size, memory_size, alignment = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + i * 56)
        require(ptype not in {2, 3}, 'helper: dynamic/INTERP/NEEDED linking is forbidden')
        region(offset, size, 'program segment')
        require(size <= memory_size <= 16 * 1024 * 1024 and address + memory_size < 2**64,
                'helper: program memory budget/overflow')
        if ptype == 1:
            require(pflags & 4 and not pflags & ~7 and pflags & 3 != 3 and
                    (alignment in {0, 1} or alignment <= 1024 * 1024 and
                     alignment & (alignment - 1) == 0 and address % alignment == offset % alignment),
                    'helper: unsafe/invalid LOAD segment')
            require(not any(address < old_address + old_size and address + memory_size > old_address
                            for _, old_address, _, old_size, _ in loads), 'helper: overlapping LOAD mappings')
            loads.append((offset, address, size, memory_size, pflags))
    require(loads and any(address <= entry < address + size and pflags & 1
                          for offset, address, size, memory_size, pflags in loads),
            'helper: entry is not file-backed executable code')

    def mapped(address, size, label):
        matches = [(offset + address - start, pflags) for offset, start, available, _, pflags in loads
                   if start <= address and address + size <= start + available]
        require(len(matches) == 1 and not matches[0][1] & 2, f'helper: {label} is not uniquely read-only/file-backed')
        return matches[0][0]

    rows = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * 64) for i in range(shnum)]
    require(not any(rows[0]) and rows[shstr][1] == 3 and 0 < rows[shstr][5] <= 1024 * 1024,
            'helper: invalid null/name section')
    names = region(rows[shstr][4], rows[shstr][5], 'section names')
    sections = {}
    for i, row in enumerate(rows[1:], 1):
        name_at, stype, sflags, address, offset, size, link, info, alignment, entsize = row
        name = cstring(names, name_at, 'section name')
        require(name and name not in sections and stype not in {6, 11} and not sflags & 0x800 and
                name not in {'.interp', '.dynamic', '.dynsym', '.dynstr'},
                'helper: duplicate/compressed/dynamic/NEEDED section forbidden')
        require(link < shnum and size <= 16 * 1024 * 1024, 'helper: section index/size budget')
        if stype != 8:
            region(offset, size, 'section')
        sections[name] = (i, row)
    require('.symtab' in sections and sum(row[1] == 2 for row in rows) == 1,
            'helper: unstripped .symtab with fan_release_manifest is required')
    sym = sections['.symtab'][1]
    require(sym[1] == 2 and sym[9] == 24 and 0 < sym[5] <= MAX_SYMBOLS * 24 and sym[5] % 24 == 0,
            'helper: invalid symbol table')
    strings_row = rows[sym[6]]
    require(strings_row[1] == 3 and 0 < strings_row[5] < MAX_PREINIT, 'helper: invalid symbol strings')
    strings = region(strings_row[4], strings_row[5], 'symbol strings')
    symbols = region(sym[4], sym[5], 'symbols')
    found = []
    for at in range(0, len(symbols), 24):
        name_at, sinfo, other, index, address, size = struct.unpack_from('<IBBHQQ', symbols, at)
        if cstring(strings, name_at, 'symbol name', maximum=4096) == 'fan_release_manifest':
            require(sinfo & 15 == 1 and 0 < index < shnum and size == 72,
                    'helper: unsupported fan_release_manifest object ABI')
            section = rows[index]
            require(section[1] == 1 and section[2] & 2 and not section[2] & 5 and
                    section[3] <= address and address + size <= section[3] + section[5],
                    'helper: fan_release_manifest is not in read-only allocated data')
            at_file = mapped(address, size, 'fan_release_manifest')
            require(at_file == section[4] + address - section[3], 'helper: manifest section/LOAD mapping mismatch')
            found.append(region(at_file, size, 'fan_release_manifest'))
    require(len(found) == 1, 'helper: exactly one immutable fan_release_manifest symbol is required')
    raw = found[0]
    present, filename_at, sha_at, size, release_at, kmi_at, magic_at, versions_at, count = struct.unpack('<I4x7QI4x', raw)
    require(present == 1, 'helper: disabled development manifest is not releasable')
    require(not any(raw[4:8]) and not any(raw[68:72]), 'helper: unsupported manifest struct padding')

    def pointer_string(address, label, maximum):
        offset = mapped(address, 1, label)
        value = cstring(data, offset, label, maximum=maximum)
        mapped(address, len(value) + 1, label)
        return value

    enabled = {
        'schema': 1, 'present': present,
        'filename': pointer_string(filename_at, 'manifest filename', 64),
        'sha256': pointer_string(sha_at, 'manifest sha256', 64), 'size': size,
        'kernel_release': pointer_string(release_at, 'manifest kernel_release', 127),
        'kmi': pointer_string(kmi_at, 'manifest kmi', 95),
        'vermagic': pointer_string(magic_at, 'manifest vermagic', 255),
        'versions_sha256': pointer_string(versions_at, 'manifest versions_sha256', 64),
        'versions_count': count,
    }
    return {'elf_class': 64, 'byte_order': 'little', 'machine': 'AArch64', 'type': 'EXEC',
            'static_no_interp_no_needed': True, 'embedded_manifest': enabled}


def check_preinit_inputs(inputs, compatibility, elf, ko_bytes):
    require(inputs.preinit_helper is not None and inputs.helper_enabled_manifest is not None,
            'preinit: explicit --preinit-helper and --helper-enabled-manifest are required')
    helper = read_bounded(inputs.preinit_helper, MAX_PREINIT - 1, 'helper')
    spec = compatibility['preinit']
    require(sha256(helper) == spec['helper_sha256'], 'preinit: helper SHA-256 mismatch')
    observed = inspect_preinit_helper(helper)
    enabled_bytes = read_bounded(inputs.helper_enabled_manifest, MAX_JSON, 'helper enabled manifest')
    require(sha256(enabled_bytes) == spec['helper_enabled_manifest_digest'],
            'preinit: helper_enabled_manifest_digest mismatch')
    enabled = parse_json(enabled_bytes, 'helper enabled manifest')
    fields = {'schema', 'present', 'filename', 'sha256', 'size', 'kernel_release', 'kmi',
              'vermagic', 'versions_sha256', 'versions_count'}
    keys(enabled, fields, 'helper enabled manifest')
    require(type(enabled['schema']) is int and enabled['schema'] == 1 and
            type(enabled['present']) is int and enabled['present'] == 1,
            'preinit: helper enabled manifest requires integer schema=1, present=1 (not an attestation)')
    release = compatibility['target_kernel_release']
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-android(\d+)-(\d+)-[A-Za-z0-9_.+\-]+', release)
    require(match is not None and len(release) < 128, 'preinit: exact kernel release is outside helper KMI parser')
    major, minor, patch, android, generation = map(int, match.groups())
    require(major <= 100 and minor <= 100 and patch <= 0xffffffff and 1 <= android <= 100 and generation <= 1000,
            'preinit: kernel release is outside helper KMI range')
    expected = {
        'schema': 1, 'present': 1, 'filename': PurePosixPath(REPLACEMENT_MEMBER).name,
        'sha256': sha256(ko_bytes), 'size': len(ko_bytes), 'kernel_release': release,
        'kmi': f'{major}.{minor}-android{android}-{generation}',
        'vermagic': compatibility['expected_vermagic'],
        'versions_sha256': elf['versions_sha256'], 'versions_count': elf['versions_count'],
    }
    for key in ('size', 'versions_count'):
        require(type(enabled[key]) is int, f'preinit: helper manifest {key} must be an integer')
    require(' modversions ' in expected['vermagic'] and ' aarch64' in expected['vermagic'] and
            len(expected['vermagic']) < 256, 'preinit: vermagic is outside helper contract')
    require(enabled == expected, 'preinit: enabled manifest differs from replacement/kernel/CRC identity')
    require(observed['embedded_manifest'] == enabled,
            'preinit: compiled helper manifest differs from supplied enabled manifest')
    return helper, observed


def channel_for(inputs):
    requested = (inputs.commissioning_record is not None or inputs.observer_report is not None or
                 inputs.confirm_first_control is not None)
    if not requested:
        return 'release'
    require(inputs.commissioning_record is not None and
            inputs.confirm_first_control == FIRST_CONTROL_ACK,
            'commissioning: --commissioning-record and --confirm-first-control are both required')
    require(inputs.release_record is None and inputs.load_report is None,
            'commissioning: release-record/load-report are forbidden; observer is not control-load evidence')
    return 'commissioning'


def template_names(strategy, *, commissioning=False):
    require(strategy in STRATEGIES, 'templates: unsupported strategy')
    require(not commissioning or strategy == 'preinit-replace',
            'commissioning: first-control requires explicit preinit-replace strategy')
    required = REQUIRED_TEMPLATES | ({PREINIT_CONF} if strategy == 'preinit-replace' else set())
    if commissioning:
        required |= COMMISSIONING_TEMPLATES
    return required, required | OPTIONAL_TEMPLATES


def validate_compatibility(value, *, commissioning=False):
    strategy = strategy_for(value)
    fields = {'schema', 'target_kernel_release', 'expected_vermagic', 'expected_symbol_crcs',
              'certsha', 'device', 'artifacts', 'templates'}
    if 'strategy' in value:
        fields.add('strategy')
    if strategy == 'preinit-replace':
        fields.add('preinit')
    if commissioning:
        fields.add('commissioning_profile')
        require(strategy == 'preinit-replace' and
                value.get('commissioning_profile') == FIRST_CONTROL_PROFILE,
                'commissioning: explicit preinit-replace and commissioning_profile=first-control are required')
    keys(value, fields, 'compatibility manifest')
    require(type(value['schema']) is int and value['schema'] == 1, 'compatibility manifest: schema must be 1')
    release = text(value['target_kernel_release'], 'target_kernel_release', 192)
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', release) is not None,
            'target_kernel_release: invalid release')
    magic = text(value['expected_vermagic'], 'expected_vermagic', 512)
    require('modversions' in magic.split() and
            re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', magic.split()[0]) is not None and
            (commissioning or magic.split()[0] == release),
            'expected_vermagic must bind target release and modversions')
    # Real OEM uname and module vermagic can differ. Only first-control may
    # declare both exact identities; its separate record must review the mismatch.
    # Production retains the original equality gate, and helper/KO bytes still
    # independently bind both values in check_preinit_inputs.
    crcs = value['expected_symbol_crcs']
    require(isinstance(crcs, dict) and 0 < len(crcs) <= MAX_VERSIONS and 'module_layout' in crcs,
            'expected_symbol_crcs: missing module_layout or inventory budget')
    for name, crc in crcs.items():
        require(SYMBOL_RE.fullmatch(name) is not None and isinstance(crc, str) and
                re.fullmatch(r'0x[a-f0-9]{8}', crc) is not None,
                'expected_symbol_crcs: symbol name or CRC format (0x + 8 lowercase hex)')
    digest(value['certsha'], 'certsha')
    keys(value['device'], {'family', 'hardware'}, 'device')
    require(value['device']['family'] == 'ACE7', 'device.family must explicitly be ACE7')
    keys(value['device']['hardware'], HARDWARE_KEYS, 'device.hardware')
    for key, item in value['device']['hardware'].items():
        text(item, f'device.hardware.{key}', 192)
    keys(value['artifacts'], {'apk', 'ko'}, 'artifacts')
    keys(value['artifacts']['apk'], {'sha256', 'package_name'}, 'artifacts.apk')
    keys(value['artifacts']['ko'], {'sha256', 'name', 'license'}, 'artifacts.ko')
    for key in ('apk', 'ko'):
        digest(value['artifacts'][key]['sha256'], f'artifacts.{key}.sha256')
    text(value['artifacts']['apk']['package_name'], 'APK package_name')
    name = text(value['artifacts']['ko']['name'], 'KO name', 56)
    require(valid_module_name(name, strategy), 'KO name: invalid/OEM-overriding module name for selected strategy')
    text(value['artifacts']['ko']['license'], 'KO license', 128)
    if strategy == 'preinit-replace':
        preinit = value['preinit']
        keys(preinit, {'target', 'stock_hash', 'helper_sha256', 'helper_enabled_manifest_digest', 'command'}, 'preinit')
        require(preinit['target'] == STOCK_TARGET, 'preinit: target must be the fixed stock driver path')
        require(preinit['stock_hash'] == STOCK_HASH, 'preinit: stock_hash differs from pinned OEM original')
        require(value['artifacts']['ko']['sha256'] != STOCK_HASH, 'preinit: stock original is not a policy replacement')
        digest(preinit['helper_sha256'], 'preinit helper_sha256')
        digest(preinit['helper_enabled_manifest_digest'], 'preinit helper_enabled_manifest_digest')
        require(preinit['command'] == PREINIT_COMMAND, 'preinit: only the single literal helper --apply action is allowed')
    template_hashes = value['templates']
    required, allowed = template_names(strategy, commissioning=commissioning)
    require(isinstance(template_hashes, dict) and required <= set(template_hashes) and
            set(template_hashes) <= allowed,
            'templates: incomplete/unknown template inventory')
    for name, item in template_hashes.items():
        safe_member(name)
        digest(item, f'template {name}')
    return value


def read_templates(directory, *, strategy='additive', commissioning=False):
    required, allowed = template_names(strategy, commissioning=commissioning)
    directory = checked_path(directory, directory=True)
    result = {}
    count = 0
    for base, dirs, files in os.walk(directory, followlinks=False):
        for entry in sorted(dirs + files):
            count += 1
            require(count <= 32, 'templates: entry budget exceeded')
            p = Path(base) / entry
            require(not p.is_symlink(), 'templates: symlink is forbidden')
            name = p.relative_to(directory).as_posix()
            safe_member(name)
            if p.is_dir():
                require(any(n.startswith(name + '/') for n in allowed),
                        'templates: unexpected directory')
                continue
            require(name in allowed, 'templates: unexpected file (no keys/logs/binaries)')
            data = read_bounded(p, MAX_TEMPLATE, 'template')
            content = decode_text(data, 'template')
            require(content.endswith('\n') and not PLACEHOLDER_RE.search(content),
                    'templates: missing final newline or placeholder/unreviewed marker')
            require(not project.contains_secret(data), 'templates: possible secret (value omitted)')
            if strategy == 'preinit-replace':
                require(not re.search(r'\b(?:insmod|modprobe)\b', content),
                        'preinit: replacement templates must not request a second driver load')
            if name.endswith('.sh') or name.endswith('/update-binary'):
                require(content.splitlines()[0] in {'#!/system/bin/sh', '#!/sbin/sh', '#!/bin/sh'},
                        'templates: shell script must have explicit Android-compatible shebang')
                commands = [line.strip() for line in content.splitlines() if line.strip() and not line.lstrip().startswith('#')]
                commands = [line for line in commands if not re.fullmatch(r':|true|exit(?:\s+0)?|set\s+-[A-Za-z]+', line)]
                require(commands, 'templates: empty/no-op script is forbidden')
            elif name.endswith('/updater-script'):
                require(content.strip() == '#MAGISK', 'templates: updater-script must be the #MAGISK marker')
            elif name == PREINIT_CONF:
                require(content == PREINIT_COMMAND + '\n', 'templates: preinit.conf must contain only the literal single helper action')
            elif name == 'allow-control':
                require(content == FIRST_CONTROL_PROFILE + '\n',
                        'commissioning: allow-control must contain exactly first-control plus LF')
            result[name] = data
            require(sum(map(len, result.values())) <= MAX_TEMPLATES, 'templates: total size budget exceeded')
    require(required <= set(result), 'templates: complete reviewed installer/lifecycle scripts are required')
    return result


def validate_module_prop(data, version):
    props = {}
    for line in decode_text(data, 'module.prop').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        require(sep and key == key.strip() and key not in props, 'module.prop: malformed/duplicate field')
        props[key] = value
    keys(props, {'id', 'name', 'version', 'versionCode', 'author', 'description'}, 'module.prop')
    require(props['id'] == MODULE_ID, 'module.prop: upgrade id must remain horae_fan_spoof')
    require(props['name'] == '风扇控制' and props['description'] == '风扇控制与状态显示。',
            'module.prop: keep the short user-facing name/description')
    for key, value in props.items():
        text(value, f'module.prop.{key}', 192)
    require(props['version'] == version['app_version'] and props['versionCode'] == str(version['version_code']),
            'module.prop: version/versionCode must match current audited source/APK')


def inspect_apk_container(data):
    """Bound APK metadata before invoking existing project signing/DEX audit."""
    try:
        with ZipFile(io.BytesIO(data)) as archive:
            members = archive.infolist()
            require(0 < len(members) <= 20000, 'APK: entry budget exceeded')
            require(len({m.filename for m in members}) == len(members), 'APK: duplicate entries')
            total = 0
            for member in members:
                name = member.filename[:-1] if member.is_dir() else member.filename
                safe_member(name)
                require(not stat.S_ISLNK(member.external_attr >> 16) and not member.flag_bits & 1 and
                        member.compress_type in {ZIP_STORED, ZIP_DEFLATED}, 'APK: symlink/encrypted/unsupported entry')
                lower = name.lower()
                require(not lower.endswith(('.key', '.pem', '.jks', '.keystore', '.p12', '.pfx', '.log', '.ko')),
                        'APK: signing key/log/kernel payload is forbidden')
                require(not re.search(r'(?:^|/)(?:oplus|oneplus|horae)[^/]*\.(?:so|bin|apk|dex|jar)$', lower),
                        'APK: OEM binary payload is forbidden')
                total += member.file_size
                require(member.file_size <= MAX_APK and total <= 192 * 1024 * 1024,
                        'APK: decompression size budget exceeded')
    except (BadZipFile, ValueError, NotImplementedError) as exc:
        raise PackageError('APK: invalid ZIP container') from exc


def project_state():
    snapshot = project.Snapshot(ROOT)
    config = project.config_for(snapshot)
    return config, project.metadata(snapshot.read, config)


def prepare_base(inputs):
    commissioning = channel_for(inputs) == 'commissioning'
    # Fail before invoking host APK tools when no actual kernel module is present.
    require(Path(inputs.ko).suffix == '.ko', 'KO: an explicit .ko input is required')
    ko_bytes = read_bounded(inputs.ko, MAX_KO, 'KO')
    compatibility = validate_compatibility(
        parse_json(read_bounded(inputs.manifest, MAX_JSON, 'manifest'), 'manifest'),
        commissioning=commissioning)
    strategy = strategy_for(compatibility)
    if strategy == 'preinit-replace':
        require(sha256(ko_bytes) != STOCK_HASH, 'preinit: stock original is not a policy replacement')
    else:
        require(inputs.preinit_helper is None and inputs.helper_enabled_manifest is None,
                'additive: preinit helper inputs are forbidden')
    elf = parse_elf64(ko_bytes, strategy=strategy, commissioning=commissioning)
    artifacts = compatibility['artifacts']
    require(sha256(ko_bytes) == artifacts['ko']['sha256'], 'KO: SHA-256 mismatch')
    for key in ('name', 'license'):
        require(elf[key] == artifacts['ko'][key], f'KO: {key} differs from manifest')
    require(elf['vermagic'] == compatibility['expected_vermagic'], 'KO: vermagic differs from manifest')
    require(elf['symbol_crcs'] == compatibility['expected_symbol_crcs'], 'KO: symbol CRCs differ from manifest')
    require(Path(inputs.apk).suffix == '.apk', 'APK: an explicit signed .apk input is required')
    apk_path = checked_path(inputs.apk)
    apk_bytes = read_bounded(apk_path, MAX_APK, 'APK')
    require(sha256(apk_bytes) == artifacts['apk']['sha256'], 'APK: SHA-256 mismatch')
    inspect_apk_container(apk_bytes)
    templates = read_templates(inputs.templates, strategy=strategy, commissioning=commissioning)
    require({name: sha256(data) for name, data in templates.items()} == compatibility['templates'],
            'templates: SHA-256 inventory mismatch')
    ko_member = REPLACEMENT_MEMBER if strategy == 'preinit-replace' else KO_MEMBER
    extra_entries, helper_observed = {}, None
    if strategy == 'preinit-replace':
        helper, helper_observed = check_preinit_inputs(inputs, compatibility, elf, ko_bytes)
        extra_entries[HELPER_MEMBER] = helper
        require(len(helper) + len(ko_bytes) + sum(len(data) for name, data in templates.items()
                                                if name.startswith('preinit/')) < MAX_PREINIT,
                'preinit: helper + replacement + config must total less than 4 MiB')
    config, version = project_state()
    require(artifacts['apk']['package_name'] == config['package_name'] == 'dev.eta.acefan',
            'APK: manifest package differs from pinned project package')
    require(compatibility['certsha'] == config['signer_certificate_sha256'],
            'APK: manifest certificate differs from pinned project certificate')
    validate_module_prop(templates['module.prop'], version)
    # Same audit function as `python3 tools/project.py audit-apk APK`, never unsigned.
    with contextlib.redirect_stdout(io.StringIO()):
        audit = project.audit_apk(apk_path, root=ROOT, cfg=config, version=version, unsigned=False)
    require(audit['sha256'] == sha256(apk_bytes), 'APK: file changed during signature audit')
    require(audit['signer_certificate_sha256'] == compatibility['certsha'], 'APK: audited certificate mismatch')
    require(project_state() == (config, version), 'APK: project contract/version changed during audit')
    entries = {APK_MEMBER: apk_bytes, ko_member: ko_bytes, **templates, **extra_entries}
    manifest = {
        'schema': 1, 'kind': 'ksu-fan-release', 'module_id': MODULE_ID,
        'compatibility': compatibility,
        'compatibility_sha256': sha256(canonical_json(compatibility)),
        'apk_audit': {
            'implementation': 'tools/project.py audit-apk',
            'package_name': config['package_name'], 'version': version['app_version'],
            'version_code': version['version_code'], 'certsha': audit['signer_certificate_sha256'],
        },
        'elf': elf,
        'payloads': {name: {'sha256': sha256(data), 'size': len(data)} for name, data in sorted(entries.items())},
        'authority_limits': LIMITS,
    }
    if commissioning:
        manifest.update(kind='ksu-fan-commissioning', commissioning_profile=FIRST_CONTROL_PROFILE,
                        production_runtime_verified=False, candidate_control_tested=False,
                        automatic_install=False, automatic_reboot=False,
                        authority_limits=COMMISSIONING_LIMITS)
    if 'strategy' in compatibility:
        manifest['strategy'] = strategy
    if helper_observed is not None:
        manifest['preinit_helper'] = helper_observed
        manifest['preinit_runtime_requirements'] = {
            'metadata_min_free_bytes': METADATA_FREE_MIN,
            'metadata_space_check': 'Required in reviewed on-device installer; NOT verified offline.',
            'return_code_review': 'Helper --apply success and KSU nonzero-exec handling require lifecycle review.',
            'load_path': 'Stock loader loads the replacement at its fixed target; no second insmod.',
        }
    return Plan(entries, manifest, compatibility)


def check_release_record(inputs, plan):
    require(channel_for(inputs) == 'release', 'release: commissioning inputs cannot satisfy release gates')
    require('commissioning_profile' not in plan.compatibility,
            'release: commissioning profile cannot satisfy release gates')
    required = {
        'release_record': inputs.release_record, 'build_report': inputs.build_report,
        'check_report': inputs.check_report, 'load_report': inputs.load_report,
    }
    missing = [name for name, path in required.items() if path is None]
    require(not missing, 'release blocked: missing controlled local release record/reports: ' + ', '.join(missing))
    record_bytes = read_bounded(inputs.release_record, MAX_JSON, 'release record')
    record = parse_json(record_bytes, 'release record')
    replacement = strategy_for(plan.compatibility) == 'preinit-replace'
    record_fields = {'schema', 'kind', 'compatibility_sha256', 'template_review', 'reports'}
    if replacement:
        record_fields.add('preinit_helper')
    keys(record, record_fields, 'release record')
    require(type(record['schema']) is int and record['schema'] == 1 and record['kind'] == 'controlled-local-release',
            'release record: expected schema=1, kind=controlled-local-release')
    require(digest(record['compatibility_sha256'], 'release record compatibility_sha256') ==
            plan.manifest['compatibility_sha256'], 'release record: compatibility/artifact/template binding mismatch')
    review = record['template_review']
    keys(review, {'reviewer', 'result'}, 'template review')
    text(review['reviewer'], 'template reviewer', 192)
    require(review['result'] == 'pass', 'release blocked: template review has not passed')
    if replacement:
        helper_review = record['preinit_helper']
        keys(helper_review, {'helper_sha256', 'helper_enabled_manifest_digest', 'result'}, 'preinit helper release review')
        require(helper_review['result'] == 'pass', 'release blocked: preinit helper review has not passed')
        for key in ('helper_sha256', 'helper_enabled_manifest_digest'):
            require(digest(helper_review[key], f'preinit helper review {key}') == plan.compatibility['preinit'][key],
                    'release blocked: preinit helper review identity mismatch')
    keys(record['reports'], {'build', 'check', 'device_load'}, 'release reports')
    paths = {'build': inputs.build_report, 'check': inputs.check_report, 'device_load': inputs.load_report}
    hashes = {}
    for name, path in paths.items():
        item = record['reports'][name]
        keys(item, {'sha256', 'result'}, f'{name} report record')
        require(item['result'] == 'pass', f'release blocked: {name} report has not passed')
        report = read_bounded(path, MAX_REPORT, f'{name} report')
        content = decode_text(report, f'{name} report')
        require(len(content.strip()) >= 32 and not PLACEHOLDER_RE.search(content),
                f'{name} report: empty/placeholder/unreviewed report')
        hashes[name] = sha256(report)
        require(digest(item['sha256'], f'{name} report sha256') == hashes[name], f'{name} report: SHA-256 mismatch')
    require(len(set(hashes.values())) == 3, 'release reports: separate build/check/device-load reports required')
    # Raw records/logs/host paths are deliberately NOT placed in the ZIP.
    return {
        'record_sha256': sha256(record_bytes), 'record_kind': record['kind'],
        'report_sha256': hashes, 'template_review': review,
        'interpretation': 'Caller-supplied local release decision; hashes are not authenticated device proof.',
        **({'preinit_helper': record['preinit_helper']} if replacement else {}),
    }


def check_commissioning_record(inputs, plan):
    require(channel_for(inputs) == 'commissioning', 'commissioning: explicit channel is required')
    require(strategy_for(plan.compatibility) == 'preinit-replace' and
            plan.compatibility.get('commissioning_profile') == FIRST_CONTROL_PROFILE,
            'commissioning: first-control requires the preinit-replace compatibility profile')
    require(inputs.build_report is not None and inputs.check_report is not None,
            'commissioning blocked: separate build-report and check-report are required')
    record_bytes = read_bounded(inputs.commissioning_record, MAX_JSON, 'commissioning record')
    record = parse_json(record_bytes, 'commissioning record')
    fields = {'schema', 'kind', 'profile', 'compatibility_sha256', 'template_review',
              'preinit_helper', 'reports', 'production_runtime_verified',
              'candidate_control_tested', 'acknowledgement'}
    if inputs.observer_report is not None:
        fields.add('observer_evidence')
    mismatch = (plan.compatibility['expected_vermagic'].split()[0] !=
                plan.compatibility['target_kernel_release'])
    if mismatch:
        fields.add('vermagic_release_review')
    candidate_layout = {
        'file_alignment': plan.manifest['elf'].get('commissioning_file_alignment_observations', []),
        'duplicate_import_namespaces': plan.manifest['elf'].get('commissioning_duplicate_import_namespaces', []),
    }
    has_layout_observations = any(candidate_layout.values())
    if has_layout_observations:
        fields.add('elf_candidate_layout_review')
    keys(record, fields, 'commissioning record')
    require(type(record['schema']) is int and record['schema'] == 1 and
            record['kind'] == 'controlled-local-commissioning' and record['profile'] == FIRST_CONTROL_PROFILE,
            'commissioning record: expected schema=1, kind=controlled-local-commissioning, profile=first-control')
    require(record['production_runtime_verified'] is False and record['candidate_control_tested'] is False,
            'commissioning record: production_runtime_verified and candidate_control_tested must both be false')
    require(record['acknowledgement'] == FIRST_CONTROL_ACK,
            'commissioning record: explicit unverified first-control/no-auto-install-or-reboot acknowledgement required')
    require(digest(record['compatibility_sha256'], 'commissioning compatibility_sha256') ==
            plan.manifest['compatibility_sha256'], 'commissioning record: compatibility binding mismatch')
    review = record['template_review']
    keys(review, {'reviewer', 'result'}, 'commissioning template review')
    text(review['reviewer'], 'commissioning template reviewer', 192)
    require(review['result'] == 'pass', 'commissioning blocked: template review has not passed')
    helper_review = record['preinit_helper']
    keys(helper_review, {'helper_sha256', 'helper_enabled_manifest_digest', 'result'},
         'commissioning preinit helper review')
    require(helper_review['result'] == 'pass', 'commissioning blocked: preinit helper review has not passed')
    for key in ('helper_sha256', 'helper_enabled_manifest_digest'):
        require(digest(helper_review[key], f'commissioning helper {key}') == plan.compatibility['preinit'][key],
                'commissioning blocked: preinit helper review identity mismatch')
    if mismatch:
        release_review = record['vermagic_release_review']
        keys(release_review, {'target_kernel_release', 'expected_vermagic', 'reason', 'result'},
             'commissioning vermagic release review')
        require(release_review['result'] == 'pass' and
                release_review['target_kernel_release'] == plan.compatibility['target_kernel_release'] and
                release_review['expected_vermagic'] == plan.compatibility['expected_vermagic'],
                'commissioning: exact target uname/vermagic mismatch review required')
        text(release_review['reason'], 'commissioning vermagic release review reason', 1024)
    if has_layout_observations:
        layout_review = record['elf_candidate_layout_review']
        keys(layout_review, {'file_alignment', 'duplicate_import_namespaces', 'reason', 'result'},
             'commissioning ELF candidate layout review')
        require(layout_review['result'] == 'pass' and
                canonical_json({key: layout_review[key] for key in candidate_layout}) ==
                canonical_json(candidate_layout),
                'commissioning: exact ELF candidate layout observations review required')
        text(layout_review['reason'], 'commissioning ELF candidate layout review reason', 1024)
    keys(record['reports'], {'build', 'check'}, 'commissioning reports (no device_load)')
    paths = {'build': inputs.build_report, 'check': inputs.check_report}
    declared = dict(record['reports'])
    for name, item in declared.items():
        keys(item, {'sha256', 'result'}, f'commissioning {name} report record')
        require(item['result'] == 'pass', f'commissioning blocked: {name} report has not passed')
    if inputs.observer_report is not None:
        observer = record['observer_evidence']
        keys(observer, {'sha256', 'scope'}, 'observer evidence')
        require(observer['scope'] == 'observer-only-not-control-load',
                'commissioning: observer scope must be observer-only-not-control-load')
        paths['observer'] = inputs.observer_report
        declared['observer'] = observer
    hashes = {}
    for name, path in paths.items():
        report = read_bounded(path, MAX_REPORT, f'commissioning {name} report')
        content = decode_text(report, f'commissioning {name} report')
        # Honest UNVERIFIED caveats are expected here, not a release-gate bypass.
        markers = re.sub(r'(?i)\bUNVERIFIED\b', 'not-yet-accepted', content)
        require(len(content.strip()) >= 32 and not PLACEHOLDER_RE.search(markers),
                f'commissioning {name} report: empty/placeholder/unreviewed report')
        hashes[name] = sha256(report)
        require(digest(declared[name]['sha256'], f'commissioning {name} sha256') == hashes[name],
                f'commissioning {name} report: SHA-256 mismatch')
    require(len(set(hashes.values())) == len(paths),
            'commissioning reports: build/check/optional-observer must be separate report bytes')
    return {
        'record_sha256': sha256(record_bytes), 'record_kind': record['kind'], 'profile': record['profile'],
        'report_sha256': hashes, 'template_review': review, 'preinit_helper': helper_review,
        'production_runtime_verified': False, 'candidate_control_tested': False,
        'acknowledgement': record['acknowledgement'],
        'interpretation': 'First-control commissioning only; no candidate control load or production runtime acceptance.',
        **({'observer_evidence': record['observer_evidence']} if inputs.observer_report is not None else {}),
        **({'vermagic_release_review': record['vermagic_release_review']} if mismatch else {}),
        **({'elf_candidate_layout_review': record['elf_candidate_layout_review']} if has_layout_observations else {}),
    }


def finalize_plan(plan, inputs):
    if channel_for(inputs) == 'commissioning':
        plan.manifest['local_commissioning_record'] = check_commissioning_record(inputs, plan)
    else:
        plan.manifest['local_release_record'] = check_release_record(inputs, plan)
    plan.entries['manifest.json'] = canonical_json(plan.manifest)
    plan.entries['SHA256SUMS'] = ''.join(
        f'{sha256(data)}  {name}\n' for name, data in sorted(plan.entries.items())).encode('ascii')
    require(sum(map(len, plan.entries.values())) <= MAX_PAYLOAD, 'release: total payload budget exceeded')
    return plan


def release_plan(inputs):
    require(channel_for(inputs) == 'release', 'release: commissioning inputs cannot satisfy release gates')
    return finalize_plan(prepare_base(inputs), inputs)


def commissioning_plan(inputs):
    require(channel_for(inputs) == 'commissioning', 'commissioning: explicit channel is required')
    return finalize_plan(prepare_base(inputs), inputs)


def executable_member(name):
    return name.endswith('.sh') or name.endswith('/update-binary') or name == HELPER_MEMBER


def write_zip(plan, output):
    """Validate first; never overwrite; clean any failed publication we created."""
    output = Path(os.path.abspath(os.path.expanduser(str(output))))
    require(output.suffix == '.zip', 'output must be an explicit .zip path')
    checked_path(output.parent, directory=True)
    require(not os.path.lexists(output), 'output already exists (nothing overwritten)')
    require(sum(map(len, plan.entries.values())) <= MAX_PAYLOAD, 'release: total payload budget exceeded')
    created = False
    temporary = None
    try:
        fd, temporary = tempfile.mkstemp(prefix='.ksu-fan-', suffix='.tmp', dir=output.parent)
        with os.fdopen(fd, 'w+b') as stream:
            with ZipFile(stream, 'w', compression=ZIP_DEFLATED, compresslevel=9, allowZip64=False) as archive:
                for name, data in sorted(plan.entries.items()):
                    safe_member(name)
                    info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    info.create_system = 3
                    mode = 0o755 if executable_member(name) else 0o644
                    info.external_attr = (stat.S_IFREG | mode) << 16
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)
            stream.flush()
            os.fsync(stream.fileno())
            stream.seek(0)
            # Validation and compression finish before the final path is opened.
            # O_EXCL protects prior work even if a destination appears meanwhile.
            # Copy (rather than a hard link) also supports Android shared storage.
            out_fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
            created = True
            with os.fdopen(out_fd, 'wb') as dest:
                while chunk := stream.read(1024 * 1024):
                    dest.write(chunk)
                dest.flush()
                os.fsync(dest.fileno())
    except BaseException as exc:
        # Also remove a partially copied final path on a cooperative interrupt.
        if created:
            output.unlink(missing_ok=True)
        if isinstance(exc, (OSError, ValueError, RuntimeError)):
            raise PackageError('release ZIP write failed; no failed output retained') from exc
        raise
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)
    return output


def build(inputs, output):
    plan = commissioning_plan(inputs) if channel_for(inputs) == 'commissioning' else release_plan(inputs)
    return write_zip(plan, output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True, help='explicit existing signed APK')
    parser.add_argument('--ko', type=Path, required=True, help='explicit existing genuine kernel module; no synthesis/build')
    parser.add_argument('--manifest', type=Path, required=True, help='schema=1 compatibility JSON')
    parser.add_argument('--templates', type=Path, required=True, help='complete caller-reviewed template directory')
    parser.add_argument('--release-record', type=Path, help='controlled local release decision JSON (not authenticated proof)')
    parser.add_argument('--build-report', type=Path)
    parser.add_argument('--check-report', type=Path)
    parser.add_argument('--load-report', type=Path, help='report of actual target-device loading, supplied by caller')
    parser.add_argument('--commissioning-record', type=Path,
                        help='separate first-control decision JSON; NEVER a production release record')
    parser.add_argument('--confirm-first-control', action='store_const', const=FIRST_CONTROL_ACK,
                        help='acknowledge untested first control; no automatic install or reboot')
    parser.add_argument('--observer-report', type=Path,
                        help='optional observer-only evidence; forbidden as candidate control-load proof')
    parser.add_argument('--preinit-helper', type=Path, help='explicit static unstripped AArch64 helper for preinit-replace only')
    parser.add_argument('--helper-enabled-manifest', type=Path,
                        help='explicit JSON mirror of the immutable helper manifest, cross-checked against ELF')
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--inspect', action='store_true', help='JSON diagnostics only, never creates a ZIP')
    mode.add_argument('--output', type=Path,
                      help='new ZIP path; all release gates apply unless explicit first-control commissioning')
    args = parser.parse_args(argv)
    inputs = Inputs(**{key: getattr(args, key) for key in Inputs.__dataclass_fields__})
    commissioning = inputs.commissioning_record is not None
    diagnostics = {'mode': 'inspect' if args.inspect else 'release', 'release_ready': False,
                   'authority_limits': LIMITS, 'errors': []}
    if commissioning:
        diagnostics.update(mode='commissioning-inspect' if args.inspect else 'commissioning',
                           commissioning_ready=False, production_runtime_verified=False,
                           candidate_control_tested=False, authority_limits=COMMISSIONING_LIMITS)
    try:
        plan = prepare_base(inputs)
        diagnostics['observed'] = plan.manifest
        finalize_plan(plan, inputs)
        diagnostics['commissioning_ready' if commissioning else 'release_ready'] = True
        if not args.inspect:
            written = write_zip(plan, args.output)
            if commissioning:
                print(f'PASS offline FIRST CONTROL commissioning packaging: {written}')
                print('production_runtime_verified=false; candidate_control_tested=false; no install or reboot performed.')
            else:
                print(f'PASS offline release packaging: {written}')
                print('Local reports are caller-supplied; this run does not establish device loadability.')
            return 0
    except (PackageError, project.ProjectError, OSError, ValueError, KeyError, TypeError, struct.error) as exc:
        diagnostics['errors'].append(str(exc))
        if not args.inspect:
            print(f'REFUSED: {exc}', file=sys.stderr)
            return 1
    print(canonical_json(diagnostics).decode('utf-8'), end='')
    return 0 if diagnostics.get('commissioning_ready' if commissioning else 'release_ready') else 1


if __name__ == '__main__':
    sys.exit(main())
