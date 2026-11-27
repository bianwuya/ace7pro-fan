#!/usr/bin/env python3
"""Build a one-shot READ-ONLY observer. This tool NEVER loads any module."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import struct
import sys
import gen_fan_target_abi as abi
import relink_fan_driver as r
from build_fan_driver_candidate import FLAGS

ROOT=r.ROOT
OUT=ROOT/'build/kernel-replace-audit/observer-live'
ALLOWED_IMPORTS={'ktime_get','thermal_zone_get_zone_by_name','thermal_zone_get_temp','_printk_deferred','__stack_chk_fail','memcpy','memset'}
NAME='eta_fan_observer'


def esc(text):
    return json.dumps(text)


def build(out):
    out=Path(out).resolve();r.require(out==OUT.resolve(),'observer output must be exact observer-live directory')
    r.require(not out.exists(),'refuse overwrite of existing observer output');out.mkdir(parents=True)
    original=r.Elf.read(r.OEM);r.require(r.sha(original.data)==r.OEM_SHA256,'original KO identity mismatch')
    # Revalidate all used running ABI/config evidence and stack-protector flags.
    abi.generate(out/'target_abi.checked.h',out/'target-abi.json')
    r.require((out/'target_abi.checked.h').read_bytes()==(ROOT/'kernel/eta_fan_policy/driver-adapter/target_abi.h').read_bytes(),'included ABI header differs from checked projection')
    bt=abi.Btf((abi.INPUT/'vmlinux.btf').read_bytes())
    module=bt.verify_struct('module',1600,[('name',24,'[56]u8'),('init',392,'*()->s32'),('exit',1528,'*()->void')])
    printk=bt.func('_printk_deferred','(*u8,void)->s32')
    blob=bytearray(original.payload(original.by_name['.gnu.linkonce.this_module']))
    r.require(len(blob)==1600 and blob[24:80].split(b'\0',1)[0]==b'oplus_fan','unexpected template module name')
    r.require(not any(blob[:24]+blob[80:]),'template includes nonzero runtime/state/other data; refuse copy')
    # All pointer locations are zero in file; attach fresh ELF relocations only.
    expected={(392,'init_module'),(1528,'cleanup_module')}
    actual={(x['offset'],original.symbols[x['symbol_index']]['name']) for x in original.relocations if x['section']=='.gnu.linkonce.this_module'}
    r.require(actual==expected,'template module relocation positions differ')
    blob[24:80]=NAME.encode().ljust(56,b'\0');(out/'module-metadata.bin').write_bytes(blob)
    commands=[];objects=[]
    for name,source in [('observer',ROOT/'kernel/eta_fan_policy/observer/observer.c'),('policy',ROOT/'kernel/eta_fan_policy/policy_core.c')]:
        p=out/(name+'.o');commands.append(r.command(['clang',*FLAGS,'-DETA_FAN_TARGET_ABI_VERIFIED=1','-c',str(source),'-o',str(p)]));objects.append(p)
    defined=set();imports=set()
    for p in objects:
        e=r.Elf.read(p)
        defined|={s['name'] for s in e.symbols if s['shndx'] and s['bind'] in (1,2)}
        imports|={s['name'] for s in e.symbols if s['name'] and s['shndx']==0}
    imports-=defined;r.require(imports<=ALLOWED_IMPORTS,'forbidden observer import '+str(imports-ALLOWED_IMPORTS))
    r.require({'thermal_zone_get_zone_by_name','thermal_zone_get_temp','ktime_get','_printk_deferred'}<=imports,'observer missing required direct read/log APIs')
    values={v['name']:v['crc'] for v in r.versions(original)['legacy']};records=[{'name':n,'crc':values[n]} for n in sorted(imports|{'module_layout'})]
    legacy=b''.join(struct.pack('<Q56s',int(v['crc'],16),v['name'].encode()) for v in records)
    names=b''.join(v['name'].encode()+b'\0' for v in records)
    crcs=b''.join(struct.pack('<I',int(v['crc'],16)) for v in records)
    for name,payload in [('versions.bin',legacy),('version-names.bin',names),('version-crcs.bin',crcs)]: (out/name).write_bytes(payload)
    for name in ['.note.Linux','.note.gnu.property','.plt','.init.plt','.text.ftrace_trampoline']:
        (out/(name.strip('.')+'.bin')).write_bytes(original.payload(original.by_name[name]))
    vermagic=next(x.split('=',1)[1] for x in r.inventory(original)['modinfo'] if x.startswith('vermagic='))
    assembly=['.section .gnu.linkonce.this_module,"aw",%progbits','.balign 64','.global __this_module','.type __this_module,%object','__this_module:',f'.incbin {esc(str(out/"module-metadata.bin"))}', '.size __this_module,.-__this_module', '.reloc __this_module+392,R_AARCH64_ABS64,init_module', '.reloc __this_module+1528,R_AARCH64_ABS64,cleanup_module', '.section .modinfo,"a",%progbits', '.asciz '+esc('name='+NAME),'.asciz "license=GPL"','.asciz "depends="','.asciz "description=Read-only one-shot fan policy observer; init returns -EAGAIN; no fan control"','.asciz '+esc('vermagic='+vermagic)]
    for section,file,align in [('__versions','versions.bin',8),('__version_ext_names','version-names.bin',1),('__version_ext_crcs','version-crcs.bin',4),('.plt','plt.bin',16),('.init.plt','init.plt.bin',1),('.text.ftrace_trampoline','text.ftrace_trampoline.bin',1)]:
        assembly += [f'.section {section},"a",%progbits',f'.balign {align}',f'.incbin {esc(str(out/file))}']
    for section,file,align in [('.note.Linux','note.Linux.bin',4),('.note.gnu.property','note.gnu.property.bin',8)]:
        assembly += [f'.section {section},"a",%note',f'.balign {align}',f'.incbin {esc(str(out/file))}']
    assembly += ['.section .note.GNU-stack,"",%progbits']
    src=out/'metadata.S';src.write_text('\n'.join(assembly)+'\n');meta=out/'metadata.o'
    commands.append(r.command(['clang','--target=aarch64-linux-gnu','-c',str(src),'-o',str(meta)]))
    final=out/(NAME+'.ko');commands.append(r.command(['ld.lld','-r',str(meta),*[str(p) for p in objects],'-o',str(final)]))
    candidate=r.Elf.read(final);inv=r.inventory(candidate)
    r.require(set(inv['imports'])==imports,'unexpected final imports')
    r.require(r.versions(candidate)['legacy']==records,'version records drift')
    r.require(candidate.payload(candidate.by_name['.gnu.linkonce.this_module'])==bytes(blob),'module bytes drift')
    r.require(candidate.by_name['.gnu.linkonce.this_module']['align']==64,'module alignment drift')
    cfi=[]
    for n in ['init_module','cleanup_module']:
        old=original.prefix4(original.unique_symbol(n,2));new=candidate.prefix4(candidate.unique_symbol(n,2));r.require(old==new,'init/exit KCFI mismatch');cfi.append({'name':n,'original':old,'observer':new,'matches':True})
    module_relocs=[candidate.relocation_record(x) for x in candidate.relocations if x['section']=='.gnu.linkonce.this_module']
    r.require({(x['offset'],x['symbol'],x['addend'],x['type']) for x in module_relocs}=={(392,'init_module',0,257),(1528,'cleanup_module',0,257)},'final module init/exit relocations drift')
    # All external calls are read/log, compiler memory helpers or stack failure.
    edges=[]
    for x in candidate.relocations:
        if x['type'] in (282,283):
            symbol=candidate.symbols[x['symbol_index']]
            target=candidate.defined_target(x)
            owners=[s['name'] for s in candidate.symbols if s['type']==2 and s['shndx']<len(candidate.sections) and candidate.sections[s['shndx']]['name']==x['section'] and s['value']<=x['offset']<s['value']+s['size']]
            edges.append({'callers':owners,'section':x['section'],'offset':x['offset'],'symbol':symbol['name'],'target':target})
            r.require(symbol['shndx']!=0 or symbol['name'] in ALLOWED_IMPORTS,'forbidden call')
    funcs=[s['name'] for s in candidate.symbols if s['type']==2]
    r.require(not any(n.startswith(('oplus_','eta_adapter_')) or n in ('probe','level_store','state_store') for n in funcs),'hardware driver function leaked into observer')
    text=candidate.data
    r.require(b'ETA_FAN_OBSERVER_V1 SELFTEST_COMPLETE' in text,'completion marker missing')
    dis=r.command(['aarch64-linux-gnu-objdump','-dr',str(final)])['stdout'];(out/'observer.disasm.txt').write_text(dis)
    initdis=r.command(['aarch64-linux-gnu-objdump','-dr','--disassemble=init_module',str(final)])['stdout']
    # Kernel must fail init synchronously; machine code loads signed -11.
    r.require('#0xfffffff5' in initdis or '#-11' in initdis or '#0xa' in initdis,'inspect init errno code: expected -11 immediate')
    r.require('sp_el0' in initdis and '#1808' in initdis and '__stack_chk_fail' in initdis,'stack protection absent')
    r.require(not __import__('re').search(r'\bx18\b',dis),'new object clobbers reserved SCS register')
    r.require(r.sha(r.OEM.read_bytes())==r.OEM_SHA256,'original modified')
    result={'module':str(final),'module_sha256':r.sha(candidate.data),'bytes':len(candidate.data),'module_name':NAME,'expected_init_return':-11,'expected_userspace_insmod':'nonzero/EAGAIN if execution reached planned end; nonzero alone is NOT success','dmesg_prefix':'ETA_FAN_OBSERVER_V1','required_completion_marker':'ETA_FAN_OBSERVER_V1 SELFTEST_COMPLETE','residency':'No successful init path; synchronous reads/calculation/logging only. Expected kernel failed-init teardown, no worker/timer/persistent registration. Load rejection or crash before marker is not success.','observe_only':True,'control_writes':0,'async_resources':0,'loaded_by_builder':False,'runtime_verified':False,'imports':sorted(imports),'versions':records,'original_hardware_functions_included':False,'original_unchanged':True,'module_layout_btf':module,'printk_btf':printk,'cfi_init_exit':cfi,'module_metadata_relocations':module_relocs,'signature':r.signature_state(candidate.data),'commands':commands,'source_hashes':{str(p.relative_to(ROOT)):r.sha(p.read_bytes()) for p in [ROOT/'kernel/eta_fan_policy/observer/observer.c',ROOT/'kernel/eta_fan_policy/policy_core.c',ROOT/'kernel/eta_fan_policy/policy_core.h',ROOT/'kernel/eta_fan_policy/driver-adapter/target_abi.h',Path(__file__)]}}
    r.jwrite(out/'result.json',result);r.jwrite(out/'inventory.json',inv);r.jwrite(out/'callgraph.json',{'direct_call_edges':edges,'external_call_allowlist':sorted(ALLOWED_IMPORTS),'external_function_resolution':'Every final undefined import is read/log/stack/memory; no registration or fan control API.'})
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out-dir',type=Path,default=OUT);args=parser.parse_args()
    try:
        result=build(args.out_dir);print(json.dumps({k:v for k,v in result.items() if k not in ('commands','source_hashes','module_layout_btf')},indent=2))
    except (ValueError,OSError) as e:
        print('FAIL CLOSED: '+str(e),file=sys.stderr);raise SystemExit(1)
