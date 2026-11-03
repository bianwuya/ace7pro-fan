#!/usr/bin/env python3
"""Read-only DEX method table dump for one class. Used to compare OEM firmware variants.

Not part of the shipped app; run from the repo with the private reference APK or any
collected OplusExSystemService.apk. Prints name, kind and signature only.
"""
import struct
import sys
from zipfile import ZipFile


class Dex:
    def __init__(self, data):
        self.d = data
        self.strings = []
        sc, so = self.u32(56), self.u32(60)
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
            params = self.u32(p + 8)
            args = []
            if params:
                n = self.u32(params)
                args = [self.types[self.u16(params + 4 + 2 * j)] for j in range(n)]
            self.protos.append((args, ret))
        self.method_offset = self.u32(92)

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

    def methods(self, cls):
        cd = self.classes().get(cls)
        if cd is None:
            return None
        if cd == 0:
            return []
        at = cd
        sf, at = self.uleb(at)
        inf, at = self.uleb(at)
        dm, at = self.uleb(at)
        vm, at = self.uleb(at)
        for _ in range(sf + inf):
            _, at = self.uleb(at)
            _, at = self.uleb(at)
        rows = []
        for count, kind in ((dm, 'direct'), (vm, 'virtual')):
            index = 0
            for _ in range(count):
                diff, at = self.uleb(at)
                index += diff
                _, at = self.uleb(at)
                code, at = self.uleb(at)
                p = self.method_offset + index * 8
                name = self.strings[self.u32(p + 4)]
                args, ret = self.protos[self.u16(p + 2)]
                rows.append((kind, name, args, ret, code != 0))
        return rows


def dexes(path):
    with ZipFile(path) as z:
        for n in sorted(z.namelist()):
            if n.startswith('classes') and n.endswith('.dex'):
                yield n, z.read(n)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    apk, cls = sys.argv[1], sys.argv[2]
    for name, data in dexes(apk):
        if data[:4] != b'dex\n':
            continue
        dex = Dex(data)
        rows = dex.methods(cls)
        if rows is None:
            continue
        print(f'{cls}  ({name}, {len(rows)} methods)')
        for kind, mname, args, ret, has_code in rows:
            print(f'  {kind:7} {ret:>22}  {mname}({", ".join(args)})'
                  + ('' if has_code else '   [abstract/native]'))
        return
    print(f'class not found: {cls}')
    sys.exit(1)


if __name__ == '__main__':
    main()
