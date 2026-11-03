#!/usr/bin/env python3
"""Dump one DEX class's fields and methods with access flags. Read-only, offline.

Used to compare OEM firmware variants: obfuscation renames members between builds,
but access flags, descriptors and field types stay stable, so those are the features
worth keying on. Never runs on device and ships nothing.
"""
import struct
import sys
from zipfile import ZipFile

FLAGS = [(0x1, 'public'), (0x2, 'private'), (0x4, 'protected'), (0x8, 'static'),
         (0x10, 'final'), (0x40, 'volatile'), (0x80, 'transient'), (0x100, 'native'),
         (0x400, 'abstract'), (0x1000, 'synthetic')]


def flags(value):
    return '|'.join(name for bit, name in FLAGS if value & bit) or 'package'


class Dex:
    def __init__(self, data):
        self.d = data
        sc, so = self.u32(56), self.u32(60)
        self.strings = []
        for i in range(sc):
            at = self.u32(so + 4 * i)
            _, at = self.uleb(at)
            end = data.index(b'\0', at)
            self.strings.append(data[at:end].decode('utf-8', 'replace'))
        tc, to = self.u32(64), self.u32(68)
        self.types = [self.strings[self.u32(to + 4 * i)] for i in range(tc)]
        pc, po = self.u32(72), self.u32(76)
        self.protos = []
        for i in range(pc):
            p = po + i * 12
            ret = self.types[self.u32(p + 4)]
            ps = self.u32(p + 8)
            args = []
            if ps:
                n = self.u32(ps)
                args = [self.types[self.u16(ps + 4 + 2 * j)] for j in range(n)]
            self.protos.append((args, ret))
        self.field_count, self.field_offset = self.u32(80), self.u32(84)
        self.method_count, self.method_offset = self.u32(88), self.u32(92)

    def u16(self, o):
        return struct.unpack_from('<H', self.d, o)[0]

    def u32(self, o):
        return struct.unpack_from('<I', self.d, o)[0]

    def uleb(self, o):
        r = s = 0
        while True:
            b = self.d[o]
            o += 1
            r |= (b & 127) << s
            if not b & 128:
                return r, o
            s += 7

    def classes(self):
        cc, co = self.u32(96), self.u32(100)
        return {self.types[self.u32(co + i * 32)]: self.u32(co + i * 32 + 24) for i in range(cc)}

    def io(self, at, index):
        self.uleb(at)

    def dump(self, cls):
        cd = self.classes().get(cls)
        if cd is None:
            return None
        if cd == 0:
            return [], []
        at = cd
        sf, at = self.uleb(at)
        inf, at = self.uleb(at)
        dm, at = self.uleb(at)
        vm, at = self.uleb(at)
        fields = []
        # Each group restarts its diff-encoded index at zero.
        for count in (sf, inf):
            idx = 0
            for _ in range(count):
                diff, at = self.uleb(at)
                idx += diff
                af, at = self.uleb(at)
                p = self.field_offset + idx * 8
                fields.append((af, self.types[self.u16(p + 2)], self.strings[self.u32(p + 4)]))
        methods = []
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, at = self.uleb(at)
                idx += diff
                af, at = self.uleb(at)
                _, at = self.uleb(at)
                p = self.method_offset + idx * 8
                args, ret = self.protos[self.u16(p + 2)]
                methods.append((af, self.strings[self.u32(p + 4)], args, ret))
        return fields, methods


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    apk, cls = sys.argv[1], sys.argv[2]
    with ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
        for name in names:
            data = z.read(name)
            if data[:4] != b'dex\n':
                continue
            dex = Dex(data)
            result = dex.dump(cls)
            if result is None:
                continue
            fields, methods = result
            print(f'{cls}  [{name}]  {len(fields)} fields, {len(methods)} methods')
            for af, t, n in fields:
                print(f'  FIELD  {flags(af):22} {t:38} {n}')
            for af, n, args, ret in methods:
                print(f'  METH   {flags(af):22} {ret:38} {n}({", ".join(args)})')
            return
    print(f'class not found: {cls}')
    sys.exit(1)


if __name__ == '__main__':
    main()
