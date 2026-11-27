#!/usr/bin/env python3
"""Fail-closed, OFFLINE AArch64 ET_REL preservation audit. No module loading.

Uses Python's standard library, ld.lld and AArch64 GNU objcopy. Writes analysis
.o files, never a .ko or installer. The OEM input is immutable and hash pinned.
This is an object-preservation gate, NOT an ABI or runtime-compatibility gate.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ANALYSIS = ROOT / 'build/kernel-replace-audit/driver-relink'
OEM = ROOT / 'build/direct-node-audit/oplus_fan.ko'
OEM_SHA256 = 'a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd'
RENAMES = {name: 'eta_original_' + name for name in
           ('oplus_fan_probe', 'level_store', 'state_store', 'oplus_fan_attrs_group')}
EHDR = struct.Struct('<16sHHIQQQIHHHHHH')
SHDR = struct.Struct('<IIQQQQIIQQ')
SYM = struct.Struct('<IBBHQQ')
RELA = struct.Struct('<QQq')
SIG_MAGIC = b'~Module signature appended~\n'
META = {'.symtab', '.strtab', '.shstrtab'}


class AuditError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise AuditError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def jwrite(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')


def cstring(data, offset):
    require(0 <= offset < len(data), 'string offset outside table')
    end = data.find(b'\0', offset)
    require(end >= 0, 'unterminated ELF string')
    try:
        return data[offset:end].decode('utf-8')
    except UnicodeDecodeError as error:
        raise AuditError('non-UTF8 ELF identifier') from error


class Elf:
    """Small bounded ELF64-LE ET_REL reader; unsupported forms fail closed."""
    def __init__(self, data, label='<bytes>'):
        self.data = bytes(data)
        self.label = str(label)
        require(64 <= len(data) <= 128 * 1024 * 1024, 'ELF size outside audit bound')
        h = EHDR.unpack_from(data)
        ident, kind, machine, version, entry, phoff, shoff, flags, ehsize, phsize, phnum, shsize, shnum, shstr = h
        require(ident[:7] == b'\x7fELF\x02\x01\x01', 'need ELF64 little endian version 1')
        require(kind == 1 and machine == 183 and version == 1, 'need AArch64 ET_REL')
        require(ehsize == 64 and shsize == 64 and phnum == 0 and phoff == 0,
                'unexpected ELF header or program headers')
        require(0 < shnum < 0xff00 and 0 < shstr < shnum, 'extended/invalid section numbering')
        self.header = {'ident': ident.hex(), 'type': kind, 'machine': machine,
                       'version': version, 'entry': entry, 'flags': flags,
                       'ehsize': ehsize, 'phentsize': phsize}
        self.shoff = shoff
        self.sections = []
        self.bounds(shoff, shnum * 64)
        for i in range(shnum):
            row = SHDR.unpack_from(data, shoff + i * 64)
            keys = ('name_offset', 'type', 'flags', 'address', 'offset', 'size',
                    'link', 'info', 'align', 'entsize')
            s = dict(zip(keys, row))
            s['index'] = i
            require(s['align'] == 0 or s['align'] & (s['align'] - 1) == 0,
                    'section alignment is not a power of two')
            if s['type'] != 8:
                self.bounds(s['offset'], s['size'])
            require(s['type'] != 9, 'SHT_REL is unsupported; require explicit-addend RELA')
            self.sections.append(s)
        self.by_name = {}
        names = self.payload(self.sections[shstr])
        for s in self.sections:
            s['name'] = cstring(names, s['name_offset'])
            require(s['name'] not in self.by_name, 'duplicate section names unsupported')
            self.by_name[s['name']] = s
        tables = [s for s in self.sections if s['type'] == 2]
        require(len(tables) == 1, 'need exactly one full SYMTAB')
        self.symtab = tables[0]
        require(self.symtab['entsize'] == 24 and self.symtab['size'] % 24 == 0,
                'invalid SYMTAB entry size')
        strings = self.section_index(self.symtab['link'])
        require(strings['type'] == 3, 'SYMTAB must link a string table')
        names = self.payload(strings)
        self.symbols = []
        for i in range(self.symtab['size'] // 24):
            v = SYM.unpack_from(data, self.symtab['offset'] + i * 24)
            no, info, other, index, value, size = v
            require(index < len(self.sections) or index in (0xfff1, 0xfff2),
                    'unsupported extended symbol section index')
            s = {'index': i, 'name': cstring(names, no), 'type': info & 15,
                 'bind': info >> 4, 'other': other, 'shndx': index,
                 'value': value, 'size': size}
            if 0 < index < len(self.sections) and s['type'] in (1, 2):
                require(value + size <= self.sections[index]['size'],
                        'defined FUNC/OBJECT extends outside section')
            self.symbols.append(s)
        require(self.symtab['info'] <= len(self.symbols), 'bad SYMTAB local boundary')
        require(all((s['bind'] == 0) == (s['index'] < self.symtab['info']) for s in self.symbols),
                'SYMTAB local bindings do not match sh_info boundary')
        self.relocations = []
        for sec in self.sections:
            if sec['type'] != 4:
                continue
            require(sec['link'] == self.symtab['index'] and sec['entsize'] == 24
                    and sec['size'] % 24 == 0, 'unsupported RELA table')
            source = self.section_index(sec['info'])
            for i in range(sec['size'] // 24):
                pos = sec['offset'] + i * 24
                offset, info, addend = RELA.unpack_from(data, pos)
                symbol_index = info >> 32
                require(symbol_index < len(self.symbols), 'RELA symbol index outside SYMTAB')
                require(offset < source['size'], 'RELA offset outside source section')
                self.relocations.append({'section': source['name'], 'offset': offset,
                    'type': info & 0xffffffff, 'symbol_index': symbol_index,
                    'addend': addend, 'table': sec['name'], 'table_offset': pos})

    @classmethod
    def read(cls, path):
        return cls(Path(path).read_bytes(), path)

    def bounds(self, offset, size):
        require(0 <= offset <= len(self.data) and 0 <= size <= len(self.data) - offset,
                'ELF range outside file')

    def section_index(self, index):
        require(0 <= index < len(self.sections), 'section index outside ELF')
        return self.sections[index]

    def payload(self, section):
        require(section['type'] != 8, 'NOBITS has no stored payload')
        return self.data[section['offset']:section['offset'] + section['size']]

    def unique_symbol(self, name, symbol_type=None, defined=True):
        rows = [s for s in self.symbols if s['name'] == name
                and (symbol_type is None or s['type'] == symbol_type)
                and (not defined or s['shndx'] != 0)]
        require(len(rows) == 1, f'need unique definition of {name}; observed {len(rows)}')
        return rows[0]

    def defined_target(self, relocation):
        symbol = self.symbols[relocation['symbol_index']]
        if 0 < symbol['shndx'] < len(self.sections):
            return (self.sections[symbol['shndx']]['name'], symbol['value'] + relocation['addend'])
        return None

    def symbol_bytes(self, symbol):
        section = self.section_index(symbol['shndx'])
        return self.payload(section)[symbol['value']:symbol['value'] + symbol['size']]

    def prefix4(self, symbol):
        section = self.section_index(symbol['shndx'])
        if symbol['value'] < 4 or section['type'] == 8:
            return None
        return self.payload(section)[symbol['value'] - 4:symbol['value']].hex()

    def relocation_record(self, r, reverse_names=None):
        s = self.symbols[r['symbol_index']]
        name = (reverse_names or {}).get(s['name'], s['name'])
        if s['type'] == 3:
            name = '<section:' + self.section_index(s['shndx'])['name'] + '>'
        elif not name:
            name = '<unnamed>'
        return {'section': r['section'], 'offset': r['offset'], 'type': r['type'],
                'symbol': name, 'symbol_type': s['type'], 'addend': r['addend'],
                'defined_target': self.defined_target(r)}


def signature_state(data):
    present = data.endswith(SIG_MAGIC)
    result = {'appended_marker_present': present, 'cryptographic_verification': 'NOT_PERFORMED',
              'validity_for_modified_artifact': 'MUST_NOT_BE_INHERITED'}
    if present:
        require(len(data) >= len(SIG_MAGIC) + 12, 'truncated module signature header')
        at = len(data) - len(SIG_MAGIC) - 12
        algo, hash_id, id_type, signer_len, key_id_len, pad, size = struct.unpack_from('>BBBBB3sI', data, at)
        require(size + signer_len + key_id_len <= at, 'module signature length outside file')
        result.update({'algorithm': algo, 'hash_id': hash_id, 'id_type': id_type,
                       'signature_bytes': size, 'signer_bytes': signer_len,
                       'key_id_bytes': key_id_len, 'padding_hex': pad.hex(),
                       'signature_sha256': sha(data[at - size:at])})
    return result


def versions(elf):
    out = {'legacy': [], 'extended': []}
    if '__versions' in elf.by_name:
        blob = elf.payload(elf.by_name['__versions'])
        require(len(blob) % 64 == 0, 'AArch64 legacy versions must use 64-byte records')
        for offset in range(0, len(blob), 64):
            crc, raw = struct.unpack_from('<Q56s', blob, offset)
            name = cstring(raw, 0)
            require(name and not any(raw[len(name) + 1:]), 'bad legacy version name padding')
            out['legacy'].append({'name': name, 'crc': f'0x{crc:08x}'})
    ns, cs = '__version_ext_names', '__version_ext_crcs'
    require((ns in elf.by_name) == (cs in elf.by_name), 'incomplete extended modversions')
    if ns in elf.by_name:
        raw = elf.payload(elf.by_name[ns])
        require(raw.endswith(b'\0'), 'extended version names need terminating NUL')
        crcs = elf.payload(elf.by_name[cs])
        require(len(crcs) % 4 == 0, 'extended CRC size is not a multiple of four')
        # Generated C arrays may carry an extra implicit NUL after the last name.
        # Consume exactly the CRC count, then require zero-only padding.
        cursor = 0
        for i in range(len(crcs) // 4):
            name = cstring(raw, cursor)
            require(name, 'empty extended version name')
            cursor += len(name.encode('utf-8')) + 1
            out['extended'].append({'name': name,
                                   'crc': f'0x{struct.unpack_from("<I", crcs, i * 4)[0]:08x}'})
        require(not any(raw[cursor:]), 'extra nonzero extended version names/CRC counts differ')
    for rows in out.values():
        require(len({r['name'] for r in rows}) == len(rows), 'duplicate module version names')
    if out['legacy'] and out['extended']:
        require(out['legacy'] == out['extended'], 'legacy and extended module versions disagree')
    return out


def inventory(elf):
    funcs = []
    for s in elf.symbols:
        if s['type'] == 2 and 0 < s['shndx'] < len(elf.sections):
            funcs.append({'name': s['name'], 'section': elf.sections[s['shndx']]['name'],
                'offset': s['value'], 'size': s['size'], 'binding': s['bind'],
                'machine_code_sha256': sha(elf.symbol_bytes(s)),
                'observed_preceding4_hex': elf.prefix4(s)})
    imports = sorted(s['name'] for s in elf.symbols if s['shndx'] == 0 and s['name'])
    modinfo = elf.payload(elf.by_name['.modinfo']).split(b'\0') if '.modinfo' in elf.by_name else []
    module = [x.decode('utf-8') for x in modinfo if x]
    sections = []
    for s in elf.sections[1:]:
        row = {k: s[k] for k in ('name', 'type', 'flags', 'align', 'size', 'entsize')}
        row['sha256'] = None if s['type'] == 8 else sha(elf.payload(s))
        sections.append(row)
    v = versions(elf)
    addrsig = elf.by_name.get('.llvm_addrsig')
    return {'file': elf.label, 'file_sha256': sha(elf.data), 'header': elf.header,
        'sections': sections, 'functions': funcs, 'function_count': len(funcs),
        'imports': imports, 'import_count': len(imports), 'modinfo': module,
        'module_namespaces': [x.split('=', 1)[1] for x in module if x.startswith('import_ns=')],
        'versions': v, 'version_count': len(v['legacy'] or v['extended']),
        'relocation_count': len(elf.relocations),
        'relocations': [elf.relocation_record(r) for r in elf.relocations],
        'absolute_kcfi_symbols': [{k: s[k] for k in ('name', 'bind', 'value')}
            for s in elf.symbols if s['shndx'] == 0xfff1 and s['name'].startswith('__kcfi_typeid_')],
        'signature': signature_state(elf.data),
        'btf_present': '.BTF' in elf.by_name,
        'addrsig': None if not addrsig else {
            'link': addrsig['link'], 'flags': addrsig['flags'],
            'semantic_coverage': 'UNKNOWN: unlinked input addrsig (sh_link=0)'
                if addrsig['link'] == 0 else 'not decoded by this tool'},
        'abi_layout_verified': False, 'runtime_verified': False}


def callback_plan(elf):
    """Resolve actual object/RELA edges, not guessed C struct field offsets."""
    specs = [('probe', 'oplus_fan_driver', 'oplus_fan_probe'),
             ('state_store', 'dev_attr_state', 'state_store'),
             ('level_store', 'dev_attr_level', 'level_store')]
    rows = []
    for label, owner, callback in specs:
        obj = elf.unique_symbol(owner, 1)
        fun = elf.unique_symbol(callback, 2)
        target = (elf.sections[fun['shndx']]['name'], fun['value'])
        os = elf.sections[obj['shndx']]['name']
        matches = [r for r in elf.relocations if r['section'] == os and r['type'] == 257
                   and obj['value'] <= r['offset'] and r['offset'] + 8 <= obj['value'] + obj['size']
                   and elf.defined_target(r) == target]
        require(len(matches) == 1, f'{label}: need one ABS64 callback pointer within {owner}')
        r = matches[0]
        rows.append({'role': label, 'owner': owner, 'owner_size_observed': obj['size'],
                     'offset_within_observed_object': r['offset'] - obj['value'],
                     'function': callback, 'function_prefix4_hex': elf.prefix4(fun),
                     'relocation': elf.relocation_record(r)})
    group = elf.unique_symbol('oplus_fan_attrs_group', 1)
    attrs = elf.unique_symbol('oplus_fan_attrs', 1)
    gs, av = elf.sections[group['shndx']]['name'], attrs['value']
    edges = [r for r in elf.relocations if r['section'] == gs
             and group['value'] <= r['offset'] < group['value'] + group['size']
             and elf.defined_target(r) == (elf.sections[attrs['shndx']]['name'], av)]
    require(len(edges) == 1 and edges[0]['type'] == 257, 'attribute group -> attrs edge not unique ABS64')
    probe = elf.unique_symbol('oplus_fan_probe', 2)
    probe_section = elf.sections[probe['shndx']]['name']
    registrations = [r for r in elf.relocations if r['section'] == probe_section
                     and probe['value'] <= r['offset'] < probe['value'] + probe['size']
                     and elf.defined_target(r) == (gs, group['value'])]
    require(Counter(r['type'] for r in registrations) == Counter({275: 1, 277: 1}),
            'need exact probe ADRP+ADD group-address relocation pair')
    call = [r for r in elf.relocations if r['section'] == probe_section
            and probe['value'] <= r['offset'] < probe['value'] + probe['size']
            and elf.symbols[r['symbol_index']]['name'] == 'sysfs_create_group']
    require(len(call) == 1 and call[0]['type'] == 283, 'need one probe sysfs_create_group CALL26')
    attr_edges = [elf.relocation_record(r) for r in elf.relocations
                  if r['section'] == elf.sections[attrs['shndx']]['name']
                  and av <= r['offset'] < av + attrs['size']]
    return {'callbacks': rows, 'attribute_group': {
        'symbol': group['name'], 'section': gs, 'offset': group['value'],
        'size_observed': group['size'], 'attrs_symbol': attrs['name'],
        'attrs_size_observed': attrs['size'], 'attrs_relocations': attr_edges,
        'attrs_pointer': elf.relocation_record(edges[0]),
        'probe_group_address': [elf.relocation_record(r) for r in registrations],
        'probe_create_call': elf.relocation_record(call[0])},
        'note': 'Observed ELF object bounds/edges only; not proof of C struct layout or prototypes.'}


def compare(original, candidate, renames=None, globalized=()):
    """Strict stage-1 comparator. No added code/data/imports or RELA changes."""
    renames = renames or {}
    reverse = {v: k for k, v in renames.items()}
    require(len(reverse) == len(renames), 'rename mapping must be injective')
    differences = []
    def same(label, left, right):
        if left != right:
            differences.append({'category': label, 'original': left, 'candidate': right})
    same('ELF header', original.header, candidate.header)
    same('section names', sorted(original.by_name), sorted(candidate.by_name))
    for name, a in original.by_name.items():
        b = candidate.by_name.get(name)
        if b is None:
            continue
        for key in ('type', 'flags', 'align', 'entsize', 'address'):
            same(f'section {name} {key}', a[key], b[key])
        same(f'section {name} link', original.section_index(a['link'])['name'],
             candidate.section_index(b['link'])['name'])
        if a['type'] == 4:
            same(f'section {name} relocation source', original.section_index(a['info'])['name'],
                 candidate.section_index(b['info'])['name'])
        elif a['type'] != 2:
            same(f'section {name} info', a['info'], b['info'])
        if name in META or a['type'] == 4:
            # Symbol/string table indices/order and RELA r_info indices are not identities.
            continue
        same(f'section {name} size', a['size'], b['size'])
        if a['type'] != 8:
            same(f'section {name} payload SHA256', sha(original.payload(a)), sha(candidate.payload(b)))
    checked_symbols = 0
    for s in original.symbols:
        if s['type'] not in (1, 2) and not (s['shndx'] == 0xfff1 and s['name'].startswith('__kcfi_')):
            continue
        if not s['name']:
            continue
        target = renames.get(s['name'], s['name'])
        matches = [t for t in candidate.symbols if t['name'] == target and t['type'] == s['type']]
        # modpost can emit duplicate LOCAL __UNIQUE_ID_* names from separate TUs.
        # Their observed section/value is an identity; selected rename targets
        # still require a unique name through prepare_symbols().
        if len(matches) > 1:
            source_section = original.sections[s['shndx']]['name'] if 0 < s['shndx'] < len(original.sections) else s['shndx']
            matches = [t for t in matches if t['value'] == s['value'] and t['size'] == s['size']
                       and (candidate.sections[t['shndx']]['name'] if 0 < t['shndx'] < len(candidate.sections) else t['shndx']) == source_section]
        same('symbol definition count ' + s['name'], 1, len(matches))
        if len(matches) != 1:
            continue
        t = matches[0]
        for key in ('value', 'size', 'other'):
            same(f'symbol {s["name"]} {key}', s[key], t[key])
        same('symbol binding ' + s['name'], 1 if s['name'] in globalized else s['bind'], t['bind'])
        asec = original.sections[s['shndx']]['name'] if 0 < s['shndx'] < len(original.sections) else s['shndx']
        bsec = candidate.sections[t['shndx']]['name'] if 0 < t['shndx'] < len(candidate.sections) else t['shndx']
        same('symbol section ' + s['name'], asec, bsec)
        if s['type'] == 2:
            same('FUNC machine code ' + s['name'], sha(original.symbol_bytes(s)), sha(candidate.symbol_bytes(t)))
            same('FUNC prefix4 ' + s['name'], original.prefix4(s), candidate.prefix4(t))
        checked_symbols += 1
    # Do not allow added/missing FUNC/OBJECT definitions hidden in existing payload.
    def definitions(e, names):
        return sorted((names.get(s['name'], s['name']), s['type']) for s in e.symbols
                      if s['type'] in (1, 2) and s['shndx'] != 0)
    same('FUNC/OBJECT definition inventory', definitions(original, {}), definitions(candidate, reverse))
    def imports(e):
        return sorted((s['name'], s['type'], s['bind'], s['other']) for s in e.symbols
                      if s['shndx'] == 0 and s['name'])
    same('undefined imports', imports(original), imports(candidate))
    def relocs(e, names):
        return Counter(json.dumps(e.relocation_record(r, names), sort_keys=True) for r in e.relocations)
    ar, br = relocs(original, {}), relocs(candidate, reverse)
    if ar != br:
        differences.append({'category': 'relocations',
                            'removed': [json.loads(x) for x in (ar - br).elements()],
                            'added': [json.loads(x) for x in (br - ar).elements()]})
    same('module versions', versions(original), versions(candidate))
    return {'passed': not differences, 'difference_count': len(differences),
            'differences': differences, 'checked_symbols': checked_symbols,
            'checked_functions': sum(s['type'] == 2 for s in original.symbols),
            'checked_relocations': len(original.relocations),
            'signature_original': signature_state(original.data),
            'signature_candidate': signature_state(candidate.data),
            'not_verified': ['C ABI/layout', 'KCFI prototype compatibility',
                'unlinked LLVM_ADDRSIG index semantics', 'kernel load/signature acceptance',
                'locking/lifecycle/ownership', 'device behavior']}


def restore_lld_plt_flag(original, linked):
    """Repair ONLY LLD's observed synthetic .plt SHF_EXECINSTR promotion.

    This edits one ELF section-header flag word, never code/data/RELA. Everything
    else must already pass strict comparison. The allowlist is intentionally
    specific; new linker behavior requires investigation rather than masking.
    """
    before = compare(original, linked)
    allowed = [{'category': 'section .plt flags', 'original': 2, 'candidate': 6}]
    require(before['differences'] in ([], allowed),
            'unexpected LLD delta; refusing bounded .plt flag restoration')
    if before['passed']:
        return linked.data, []
    s = linked.by_name['.plt']
    require(s['size'] == 1 and linked.payload(s) == original.payload(original.by_name['.plt']),
            '.plt is not the preserved one-byte placeholder')
    out = bytearray(linked.data)
    struct.pack_into('<Q', out, linked.shoff + s['index'] * 64 + 8, 2)
    return bytes(out), [{'section': '.plt', 'field': 'sh_flags', 'from': 6, 'to': 2,
                         'file_offset': linked.shoff + s['index'] * 64 + 8}]


def prepare_symbols(original_path, output_path, renames, objcopy):
    original = Elf.read(original_path)
    require(output_path.suffix == '.o' and not output_path.exists(), 'new analysis .o required')
    require(original_path.resolve() != output_path.resolve(), 'refuse input overwrite')
    require(len(set(renames.values())) == len(renames), 'nonunique renamed definitions')
    args = [objcopy]
    for name, new in renames.items():
        s = original.unique_symbol(name)
        require(s['type'] in (1, 2) and s['bind'] == 0, 'only unique LOCAL FUNC/OBJECT allowed')
        require(not any(t['name'] == new for t in original.symbols), 'rename destination already exists')
        # GNU objcopy applies redefine before binding changes, independent of
        # command-line order; globalize the destination spelling.
        args += ['--redefine-sym=' + name + '=' + new, '--globalize-symbol=' + new]
    args += [str(original_path), str(output_path)]
    run = command(args)
    after = Elf.read(output_path)
    result = compare(original, after, renames, renames)
    require(result['passed'], 'objcopy changed protected material; inspect analysis object')
    return {'command': run, 'comparison': result}


def redirect_function_relocations(elf, specifications):
    """Exact file-identity-bound RELA editing primitive, tested only synthetically.

    Each spec names source offset/type, previous resolved target, and an existing
    unique replacement FUNC. No new symbols, offset-based machine-code patch,
    guessed field layout, signature forging, or CFI hash rewriting is supported.
    Prefix equality is necessary, NOT sufficient evidence of production ABI.
    """
    require(len({(s['section'], s['offset']) for s in specifications}) == len(specifications),
            'duplicate relocation patch sites')
    out = bytearray(elf.data)
    log = []
    for spec in specifications:
        require(spec['input_sha256'] == sha(elf.data), 'relocation plan file SHA256 mismatch')
        hits = [r for r in elf.relocations if r['section'] == spec['section']
                and r['offset'] == spec['offset'] and r['type'] == spec['type']]
        require(len(hits) == 1, 'relocation patch target not unique')
        r = hits[0]
        require(r['type'] == 257, 'only ABS64 callback pointers supported by this primitive')
        old = elf.unique_symbol(spec['original_function'], 2)
        new = elf.unique_symbol(spec['replacement_function'], 2)
        require(elf.defined_target(r) == (elf.sections[old['shndx']]['name'], old['value']),
                'relocation no longer points at exact original function entry')
        require(elf.prefix4(old) is not None and elf.prefix4(old) == elf.prefix4(new),
                'callback observed KCFI prefix differs/missing; refusing redirection')
        struct.pack_into('<Qq', out, r['table_offset'] + 8,
                         (new['index'] << 32) | r['type'], 0)
        log.append({'before': elf.relocation_record(r), 'replacement': new['name'],
                    'observed_equal_prefix4': elf.prefix4(old)})
    return bytes(out), log


def command(arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=120)
    record = {'argv': arguments, 'returncode': result.returncode,
              'stdout': result.stdout, 'stderr': result.stderr}
    require(result.returncode == 0, 'command failed: ' + json.dumps(record))
    return record


def build_id_option(elf):
    if '.note.gnu.build-id' not in elf.by_name:
        return []
    raw = elf.payload(elf.by_name['.note.gnu.build-id'])
    require(len(raw) >= 16, 'truncated build-id note')
    namesz, descsz, kind = struct.unpack_from('<III', raw)
    start = 12 + ((namesz + 3) & ~3)
    require(namesz == 4 and raw[12:16] == b'GNU\0' and kind == 3
            and start + descsz == len(raw), 'unsupported build-id note shape')
    return ['--build-id=0x' + raw[start:start + descsz].hex()]


def audit(original_path, output, linker='ld.lld', objcopy='aarch64-linux-gnu-objcopy', expected_sha=OEM_SHA256):
    original_path, output = Path(original_path).resolve(), Path(output).resolve()
    require(output.is_relative_to(ANALYSIS.resolve()), 'output must stay in driver-relink analysis directory')
    require(not output.exists(), 'use a new run directory; never overwrite existing work')
    original = Elf.read(original_path)
    require(sha(original.data) == expected_sha, 'OEM input SHA256 does not match expected identity')
    require(inventory(original)['version_count'] == 85, 'expected exactly 85 original version records')
    output.mkdir(parents=True)
    (output / 'ANALYSIS_ONLY.txt').write_text('NOT A LOADABLE DELIVERABLE. No policy attached. Never install these .o files.\n')
    result = {'scope': 'offline stage-1 object preservation only',
              'expected_input_sha256': expected_sha,
              'audit_tool_sha256': sha(Path(__file__).read_bytes()),
              'commands': [], 'adapter_attached': False, 'runtime_verified': False}
    jwrite(output / 'original.inventory.json', inventory(original))
    jwrite(output / 'callback-relocations.json', callback_plan(original))
    input_copy = output / 'original.analysis.o'
    with input_copy.open('xb') as stream:
        stream.write(original.data)
    raw = output / 'lld-raw.analysis.o'
    result['commands'].append(command([linker, '-r', str(input_copy), '-o', str(raw)]))
    raw_check = compare(original, Elf.read(raw))
    jwrite(output / 'lld-raw.comparison.json', raw_check)
    result['raw_ld_r_preserves_all_protected_material'] = raw_check['passed']
    preserved_raw = output / 'lld-buildid.analysis.o'
    result['commands'].append(command([linker, '-r', *build_id_option(original),
                                      str(input_copy), '-o', str(preserved_raw)]))
    restored, edits = restore_lld_plt_flag(original, Elf.read(preserved_raw))
    protected = output / 'preserved.analysis.o'
    with protected.open('xb') as stream:
        stream.write(restored)
    preservation = compare(original, Elf.read(protected))
    jwrite(output / 'preserved.comparison.json', preservation)
    require(preservation['passed'], 'protected LLD object fails invariant checks')
    result['bounded_header_restorations'] = edits
    result['build_id_note_policy'] = 'original provenance note explicitly retained; NOT a new artifact identity/signature'
    prepared = output / 'original-callable.analysis.o'
    result['symbol_preparation'] = prepare_symbols(input_copy, prepared, RENAMES, objcopy)
    prepared_link = output / 'callable-linked.analysis.o'
    result['commands'].append(command([linker, '-r', *build_id_option(original),
                                      str(prepared), '-o', str(prepared_link)]))
    # First compare to the prepared object, so only the single .plt flag delta is eligible.
    fixed, edits2 = restore_lld_plt_flag(Elf.read(prepared), Elf.read(prepared_link))
    final = output / 'callable-preserved.analysis.o'
    with final.open('xb') as stream:
        stream.write(fixed)
    callable_check = compare(original, Elf.read(final), RENAMES, RENAMES)
    jwrite(output / 'callable-preserved.comparison.json', callable_check)
    require(callable_check['passed'], 'globalized/renamed relink fails original invariant checks')
    jwrite(output / 'callable-preserved.inventory.json', inventory(Elf.read(final)))
    require(sha(original_path.read_bytes()) == expected_sha, 'OEM input changed during analysis')
    result.update({'protected_relink_passed': preservation['passed'],
                   'callable_relink_passed': callable_check['passed'],
                   'callable_header_restorations': edits2, 'renames': RENAMES,
                   'input_unchanged': True, 'function_count': preservation['checked_functions'],
                   'relocation_count': preservation['checked_relocations'],
                   'version_count': inventory(original)['version_count'],
                   'signature_original': signature_state(original.data),
                   'signature_output': signature_state(fixed),
                   'production_adapter_status': 'BLOCKED pending verified exact ABI/layout/lifecycle/arbitration'})
    for name, executable in (('linker', linker), ('objcopy', objcopy)):
        result[name + '_version'] = command([executable, '--version'])['stdout'].splitlines()[0]
    jwrite(output / 'result.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    run = sub.add_parser('audit', help='actual immutable-OEM relink and preservation regression')
    run.add_argument('--input', type=Path, default=OEM)
    run.add_argument('--out-dir', type=Path, required=True)
    run.add_argument('--ld', default=shutil.which('ld.lld') or 'ld.lld')
    run.add_argument('--objcopy', default=shutil.which('aarch64-linux-gnu-objcopy') or 'aarch64-linux-gnu-objcopy')
    inspect = sub.add_parser('inventory', help='read-only JSON inventory')
    inspect.add_argument('input', type=Path)
    args = parser.parse_args()
    try:
        if args.action == 'inventory':
            print(json.dumps(inventory(Elf.read(args.input)), indent=2, sort_keys=True))
        else:
            result = audit(args.input, args.out_dir, args.ld, args.objcopy)
            print(json.dumps({k: v for k, v in result.items() if k not in ('commands', 'symbol_preparation')}, indent=2))
        return 0
    except (AuditError, OSError, subprocess.TimeoutExpired) as error:
        print('FAIL CLOSED: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
