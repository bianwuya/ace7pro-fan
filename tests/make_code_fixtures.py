#!/usr/bin/env python3
"""Synthetic non-installable APK mutations of a private local reference; never ship these APKs."""
from pathlib import Path
import hashlib
import struct
import sys
import zlib
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(sys.argv[1]); reference = root / 'reference.apk'

def u16(data, off): return struct.unpack_from('<H', data, off)[0]
def u32(data, off): return struct.unpack_from('<I', data, off)[0]
def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]; off += 1; result |= (b & 127) << shift
        if not b & 128: return result, off
        shift += 7
        assert shift < 35

def locate(data, cls, name):
    sc, so, tc, to = u32(data,56),u32(data,60),u32(data,64),u32(data,68)
    strings = []
    for i in range(sc):
        _, at = uleb(data, u32(data, so+i*4)); end = data.index(0, at)
        strings.append(bytes(data[at:end]).decode('utf-8', errors='replace'))
    types = [strings[u32(data,to+i*4)] for i in range(tc)]
    mo, co = u32(data,92), u32(data,100)
    for i in range(u32(data,96)):
        off = co+i*32
        if types[u32(data,off)] != cls: continue
        at = u32(data,off+24)
        sf, at=uleb(data,at); inf,at=uleb(data,at); dm,at=uleb(data,at); vm,at=uleb(data,at)
        for _ in range(sf+inf):
            _,at=uleb(data,at); _,at=uleb(data,at)
        for count in (dm,vm):
            index=0
            for _ in range(count):
                diff,at=uleb(data,at); index+=diff
                _,at=uleb(data,at); code,at=uleb(data,at)
                if strings[u32(data,mo+index*8+4)]==name: return code
    return None

def width(op):
    if op in (2,5,8,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29) or 0x2d<=op<=0x3d or 0x44<=op<=0x6d or 0x90<=op<=0xaf or 0xd0<=op<=0xe2: return 2
    if op in (3,6,9,0x14,0x17,0x1b) or 0x24<=op<=0x26 or 0x2a<=op<=0x2c or 0x6e<=op<=0x72 or 0x74<=op<=0x78: return 3
    if op==0x18:return 5
    return 1

def mutate(data, method):
    data=bytearray(data); code=locate(data,'Li9/h;',method)
    if code is None:return None
    start=code+16; units=u32(data,code+12); pc=0
    while pc<units:
        first=u16(data,start+pc*2); op=first&255
        if op==0x12:
            struct.pack_into('<H',data,start+pc*2,first^0x1000)
            data[12:32]=hashlib.sha1(data[32:]).digest()
            struct.pack_into('<I',data,8,zlib.adler32(data[12:])&0xffffffff)
            return bytes(data)
        pc+=width(op)
    raise AssertionError('no const/4 fixture')

with ZipFile(reference) as z:
    entries={i.filename:z.read(i) for i in z.infolist()}

for filename, method in [('repacked.apk',None),('changed-candidate.apk','q'),('changed-protection.apk','c'),('malformed.apk','bad')]:
    out=dict(entries); found=False
    if method=='bad': out['classes.dex']=b'not a dex'; found=True
    elif method:
        for name,data in entries.items():
            if not name.endswith('.dex'):continue
            changed=mutate(data,method)
            if changed is not None:out[name]=changed;found=True;break
        assert found, method
    out['compat-test-marker.txt']=b'synthetic offline fixture only'
    with ZipFile(root/filename,'w',ZIP_DEFLATED) as z:
        for name,data in out.items():z.writestr(name,data)
print('Created repack, candidate/protection mutations, malformed DEX fixtures (not installable, not real extra firmware).')
