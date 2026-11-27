#!/usr/bin/env python3
"""Offline target-pinned incremental relink; output is NONRELEASE, NEVER loaded.

Original alloc sections stay separate from adapter/policy alloc sections. Every
original contribution is mapped and verified, with exactly eight RELA edits.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import sys

import relink_fan_driver as r
import gen_fan_target_abi as abi

ROOT=r.ROOT
SOURCE=ROOT/'kernel/eta_fan_policy/driver-adapter'
DONOR=abi.INPUT/'oplus_chg_v2.analysis.ko'
DONOR_SHA='08e57825b2f08dcf120ec9087c37ea091985f3dbf64ab3fcc78a6f081a925454'
RENAMES={n:'eta_original_'+n for n in ('oplus_fan_probe','oplus_fan_remove','oplus_fan_shutdown','oplus_fan_suspend','oplus_fan_resume','oplus_fan_attrs_group','level_store','state_store','state_show','level_show','level_eff_show','status_show','present_show')}
CALLBACKS=[('oplus_fan_probe','eta_adapter_probe','.data',8),
 ('oplus_fan_remove','eta_adapter_remove','.data',16),
 ('oplus_fan_shutdown','eta_adapter_shutdown','.data',24),
 ('state_store','eta_adapter_state_store','.data',0x1d8),
 ('level_store','eta_adapter_level_store','.data',0x218),
 ('oplus_fan_suspend','eta_adapter_suspend','.rodata',0x1e0),
 ('oplus_fan_resume','eta_adapter_resume','.rodata',0x1e8)]
NEW_IMPORTS={'power_supply_get_by_name','power_supply_get_property','power_supply_put','sysfs_remove_group'}
FLAGS=['--target=aarch64-linux-gnu','-ffreestanding','-fno-builtin','-fno-pic','-fno-pie','-fno-strict-aliasing','-fno-omit-frame-pointer','-mno-omit-leaf-frame-pointer','-fno-optimize-sibling-calls','-O2',
 '-fstack-protector-strong','-mstack-protector-guard=sysreg','-mstack-protector-guard-reg=sp_el0','-mstack-protector-guard-offset=1808',
 '-fsanitize=kcfi','-Xclang','-fsanitize-cfi-icall-experimental-normalize-integers',
 '-mbranch-protection=pac-ret','-ffixed-x18','-mno-outline-atomics','-mgeneral-regs-only',
 '-ffunction-sections','-fdata-sections','-Wall','-Wextra','-Werror']


def donor_versions():
    """Section-only reader: donor contains debug SHT_REL, irrelevant to versions.
    Do not relax production ET_REL relocation parsing to accept unknown relocs.
    """
    data=DONOR.read_bytes();r.require(r.sha(data)==DONOR_SHA,'CRC donor SHA mismatch')
    h=r.EHDR.unpack_from(data);r.require(h[0][:7]==b'\x7fELF\x02\x01\x01' and h[1:3]==(1,183),'bad donor ELF')
    r.require(h[11]==64 and h[6]+h[12]*64<=len(data),'bad donor section table')
    rows=[r.SHDR.unpack_from(data,h[6]+i*64) for i in range(h[12])]
    s=rows[h[13]];names=data[s[4]:s[4]+s[5]];sections={}
    for row in rows:
        if row[1]==8:continue
        r.require(row[4]+row[5]<=len(data),'donor section range')
        name=r.cstring(names,row[0]);r.require(name not in sections,'duplicate donor section')
        sections[name]=data[row[4]:row[4]+row[5]]
    raw=sections['__versions'];r.require(len(raw)%64==0,'donor legacy shape');records=[]
    for pos in range(0,len(raw),64):
        crc,name=struct.unpack_from('<Q56s',raw,pos);records.append({'name':r.cstring(name,0),'crc':f'0x{crc:08x}'})
    names=sections['__version_ext_names'];crcs=sections['__version_ext_crcs'];cursor=0;extended=[]
    r.require(len(crcs)%4==0,'donor ext CRC shape')
    for i in range(len(crcs)//4):
        name=r.cstring(names,cursor);cursor+=len(name.encode())+1
        extended.append({'name':name,'crc':f'0x{struct.unpack_from("<I",crcs,i*4)[0]:08x}'})
    r.require(not any(names[cursor:]) and records==extended,'donor legacy/ext disagree')
    r.require(len({a['name'] for a in records})==len(records),'duplicate donor CRC')
    values={a['name']:a['crc'] for a in records}
    r.require(NEW_IMPORTS<=values.keys(),'missing exact donor CRC')
    return {n:values[n] for n in sorted(NEW_IMPORTS)}


def patch_relocations(original,linked):
    out=bytearray(linked.data);log=[]
    for old,new,section,offset in CALLBACKS:
        a=original.unique_symbol(old,2);b=linked.unique_symbol(new,2)
        r.require(original.prefix4(a)==linked.prefix4(b),'CFI mismatch '+old+' -> '+new)
        hits=[x for x in linked.relocations if x['section']==section and x['offset']==offset and x['type']==257]
        r.require(len(hits)==1,'callback source not unique')
        rel=hits[0]
        r.require(linked.defined_target(rel)==(original.sections[a['shndx']]['name'],a['value']),'callback original target drift')
        struct.pack_into('<Qq',out,rel['table_offset']+8,(b['index']<<32)|257,0)
        log.append({'section':section,'offset':offset,'type':257,'old_function':old,'new_function':new,'prefix4':original.prefix4(a),'before':linked.relocation_record(rel)})
    rows=[x for x in linked.relocations if x['section']=='.text' and x['offset']==0x1038 and x['type']==283]
    r.require(len(rows)==1 and linked.symbols[rows[0]['symbol_index']]['name']=='sysfs_create_group','capture CALL26 drift')
    target=linked.unique_symbol('eta_adapter_capture_group',2);rel=rows[0]
    struct.pack_into('<Qq',out,rel['table_offset']+8,(target['index']<<32)|283,0)
    log.append({'section':'.text','offset':0x1038,'type':283,'old_import':'sysfs_create_group','new_function':'eta_adapter_capture_group','before':linked.relocation_record(rel)})
    return bytes(out),log


def contribution_check(original,candidate,patches,crcs):
    differences=[];mapping=[];reverse={v:k for k,v in RENAMES.items()}
    def same(label,a,b):
        if a!=b:differences.append({'category':label,'original':a,'candidate':b})
    same('ELF header',original.header,candidate.header)
    version_names={'__versions','__version_ext_crcs','__version_ext_names'}
    for name,a in original.by_name.items():
        b=candidate.by_name.get(name)
        if not b:differences.append({'category':'missing original section','name':name});continue
        for key in ('type','flags','align','entsize','address'):same(f'{name} {key}',a[key],b[key])
        same(name+' section link',original.section_index(a['link'])['name'],candidate.section_index(b['link'])['name'])
        if a['type']==4:
            same(name+' source',original.section_index(a['info'])['name'],candidate.section_index(b['info'])['name'])
        elif a['type']!=2:same(name+' info',a['info'],b['info'])
        if name in r.META or a['type']==4 or name in version_names:continue
        same(name+' preserved size',a['size'],b['size'])
        if a['type']!=8:same(name+' preserved bytes',r.sha(original.payload(a)),r.sha(candidate.payload(b)))
        mapping.append({'input_section':name,'input_offset':0,'output_section':name,'output_offset':0,'bytes':a['size'],'nobits':a['type']==8})
    funcs=[]
    for a in original.symbols:
        if a['type']!=2 or not a['shndx']:continue
        b=candidate.unique_symbol(RENAMES.get(a['name'],a['name']),2)
        same(a['name']+' section',original.sections[a['shndx']]['name'],candidate.sections[b['shndx']]['name'])
        for field in ('value','size','other'):same(a['name']+' '+field,a[field],b[field])
        same(a['name']+' binding',1 if a['name'] in RENAMES else a['bind'],b['bind'])
        same(a['name']+' machine code',r.sha(original.symbol_bytes(a)),r.sha(candidate.symbol_bytes(b)))
        same(a['name']+' prefix4',original.prefix4(a),candidate.prefix4(b))
        funcs.append({'original':a['name'],'candidate':b['name'],'section':candidate.sections[b['shndx']]['name'],'offset':b['value'],'bytes':b['size'],'machine_code_sha256':r.sha(original.symbol_bytes(a)),'prefix4_hex':original.prefix4(a)})
    for a in original.symbols:
        if a['type']!=1 or not a['shndx']:continue
        section=original.sections[a['shndx']]['name'];name=RENAMES.get(a['name'],a['name'])
        hits=[b for b in candidate.symbols if b['name']==name and b['type']==1 and b['value']==a['value'] and b['size']==a['size'] and b['shndx']<len(candidate.sections) and candidate.sections[b['shndx']]['name']==section]
        same('original OBJECT '+a['name']+' multiplicity',1,len(hits))
        if len(hits)==1:
            same(a['name']+' binding',1 if a['name'] in RENAMES else a['bind'],hits[0]['bind'])
            same(a['name']+' visibility',a['other'],hits[0]['other'])
    # Every original absolute KCFI declaration must remain byte-for-byte numeric.
    for a in original.symbols:
        if a['shndx']==0xfff1 and a['name'].startswith('__kcfi_'):
            b=candidate.unique_symbol(a['name']);same(a['name']+' KCFI value',a['value'],b['value'])
    approved={(p['section'],p['offset']):p for p in patches}
    origins=set(original.by_name)
    original_rows={(x['section'],x['offset']):x for x in original.relocations}
    candidate_source_rows=[x for x in candidate.relocations if x['section'] in origins]
    candidate_rows={(x['section'],x['offset']):x for x in candidate_source_rows}
    r.require(len(original_rows)==len(original.relocations),'duplicate original relocation sites unsupported')
    r.require(len(candidate_rows)==len(candidate_source_rows),'duplicate candidate original-section relocation sites')
    r.require(len(approved)==8 and set(approved)<=set(original_rows),'exact eight original RELA edits required')
    same('original relocation site set',sorted(original_rows),sorted(candidate_rows))
    for site,a in original_rows.items():
        b=candidate_rows.get(site)
        if not b:continue
        if site in approved:
            p=approved[site];same('approved '+str(site)+' type',p['type'],b['type'])
            s=candidate.symbols[b['symbol_index']];same('approved '+str(site)+' function',p['new_function'],s['name']);same('approved '+str(site)+' addend',0,b['addend'])
        else:same('preserved RELA '+str(site),original.relocation_record(a),candidate.relocation_record(b,reverse))
    ov=r.versions(original);cv=r.versions(candidate)
    extra=[{'name':n,'crc':c} for n,c in crcs.items()]
    same('legacy/extended original+proven additions',ov['legacy']+extra,cv['legacy'])
    for name in ('__versions','__version_ext_crcs'):
        raw=original.payload(original.by_name[name]);same(name+' original raw prefix',r.sha(raw),r.sha(candidate.payload(candidate.by_name[name])[:len(raw)]))
    # Original ext C array has one additional implicit NUL. Insert additions
    # before that final padding byte, preserving all original name bytes.
    oldnames=original.payload(original.by_name['__version_ext_names']);newnames=candidate.payload(candidate.by_name['__version_ext_names'])
    same('original ext name byte prefix excluding implicit pad',oldnames[:-1].hex(),newnames[:len(oldnames)-1].hex())
    oi={s['name'] for s in original.symbols if s['name'] and s['shndx']==0}
    ci={s['name'] for s in candidate.symbols if s['name'] and s['shndx']==0}
    same('original imports plus four evidenced APIs',sorted(oi|NEW_IMPORTS),sorted(ci))
    for a in original.symbols:
        if a['name'] in oi and a['shndx']==0:
            b=candidate.unique_symbol(a['name'],defined=False)
            same('original import binding '+a['name'],(a['type'],a['bind'],a['other']),(b['type'],b['bind'],b['other']))
    newsections=[{k:s[k] for k in ('name','type','flags','align','size')} for s in candidate.sections if s['name'] not in origins]
    for s in newsections:
        if s['flags']&2:r.require(s['name'].startswith(('.eta_adapter','.eta_policy','.eta_protocol')),'unexpected new allocated section '+s['name'])
    return {'passed':not differences,'differences':differences,'original_section_contributions':mapping,'original_functions':funcs,'approved_relocation_edits':patches,'preserved_original_relocations':len(original_rows)-len(approved),'preserved_original_version_count':len(ov['legacy']),'added_versions':extra,'new_sections':newsections,'runtime_verified':False,'release_eligible':False,'known_unknowns':['raw original LLVM_ADDRSIG has sh_link=0; index semantics unknown','not built by kernel full Kbuild/modpost','unloaded: locking/device thermal naming/USB supply semantics need review','no OEM enable snapshot resurrection on hard exit; awaiting original next store','single-instance lifecycle/rebind serialized in host harness; kernel concurrency not exercised']}


def build(out,candidate_label='NONRELEASE_UNLOADED'):
    r.require(candidate_label in ('NONRELEASE_UNLOADED','CONTROL_TEST_PENDING'),'unsupported candidate label')
    out=Path(out).resolve();r.require(out.is_relative_to(r.ANALYSIS),'output outside analysis');r.require(not out.exists(),'new output directory required');out.mkdir(parents=True)
    original=r.Elf.read(r.OEM);r.require(r.sha(original.data)==r.OEM_SHA256,'wrong original')
    (out/(candidate_label+'.txt')).write_text('Experimental '+candidate_label+' candidate. Default disabled. No control runtime verification. A label is not loading/reboot authorization or a passed load report.\n')
    abi_result=abi.generate(SOURCE/'target_abi.h',out/'target-abi.json')
    commands=[];objects=[]
    for name,source,prefix in [('adapter',SOURCE/'driver_adapter.c','.eta_adapter'),('protocol',SOURCE/'config_protocol.c','.eta_protocol'),('policy',ROOT/'kernel/eta_fan_policy/policy_core.c','.eta_policy')]:
        raw=out/(name+'.compiler.o');obj=out/(name+'.o')
        commands.append(r.command(['clang',*FLAGS,'-DETA_FAN_TARGET_ABI_VERIFIED=1','-DETA_FAN_CANDIDATE_LABEL="'+candidate_label+'"','-c',str(source),'-o',str(raw)]))
        rawelf=r.Elf.read(raw)
        property_name='.note.gnu.property'
        r.require(property_name in rawelf.by_name and rawelf.payload(rawelf.by_name[property_name])==original.payload(original.by_name[property_name]),'new compiler PAC property differs from original')
        # LLD AND-merges PAC properties across inputs; keep each verified GNU
        # property note under its canonical name rather than renaming it away.
        renames=['--rename-section='+s['name']+'='+prefix+s['name'] for s in rawelf.sections if s['flags']&2 and s['name']!=property_name]
        commands.append(r.command(['aarch64-linux-gnu-objcopy','--remove-section=.comment','--remove-section=.llvm_addrsig',*renames,str(raw),str(obj)]));objects.append(obj)
    prepared=out/'original-callable.o';prepared_info=r.prepare_symbols(r.OEM,prepared,RENAMES,'aarch64-linux-gnu-objcopy')
    # Type hashes for all new indirect callbacks, not merely sysfs store.
    adapter=r.Elf.read(objects[0]);cfi=[]
    for a,b,_,_ in CALLBACKS:
        av=original.prefix4(original.unique_symbol(a,2));bv=adapter.prefix4(adapter.unique_symbol(b,2));r.require(av==bv,'callback CFI mismatch')
        cfi.append({'original':a,'replacement':b,'prefix4':av,'match':av==bv})
    for a,b in [('oplus_fan_cleanup','eta_adapter_cleanup'),('oplus_fan_derate_work_fn','eta_policy_work'),('level_store','eta_config_store'),('state_show','eta_status_show'),('state_show','eta_config_snapshot_show')]:
        av=original.prefix4(original.unique_symbol(a,2));bv=adapter.prefix4(adapter.unique_symbol(b,2));r.require(av==bv,'additional indirect callback CFI mismatch '+b);cfi.append({'original_type_witness':a,'new':b,'prefix4':av,'match':True})
    linked=out/'incremental-raw.o';commands.append(r.command(['ld.lld','-r',*r.build_id_option(original),str(prepared),*[str(p) for p in objects],'-o',str(linked)]))
    le=r.Elf.read(linked);payload=bytearray(le.data)
    # Exactly the already-proven LLD .plt header quirk, explicitly checked.
    a=original.by_name['.plt'];b=le.by_name['.plt'];r.require(a['flags']==2 and b['flags']==6 and a['size']==b['size']==1 and original.payload(a)==le.payload(b),'.plt quirk no longer expected')
    struct.pack_into('<Q',payload,le.shoff+b['index']*64+8,2)
    patched,patches=patch_relocations(original,r.Elf(payload));intermediate=out/'incremental-redirected.o';intermediate.write_bytes(patched)
    crcs=donor_versions();legacy=original.payload(original.by_name['__versions']);extcrc=original.payload(original.by_name['__version_ext_crcs']);names=original.payload(original.by_name['__version_ext_names'])
    r.require(names.endswith(b'\0\0'),'expected one implicit NUL pad');names=names[:-1]
    for n,crc in crcs.items():
        v=int(crc,16);legacy+=struct.pack('<Q56s',v,n.encode());extcrc+=struct.pack('<I',v);names+=n.encode()+b'\0'
    names+=b'\0'
    specs=[('__versions',legacy),('__version_ext_crcs',extcrc),('__version_ext_names',names)];args=['aarch64-linux-gnu-objcopy']
    for name,payload in specs:
        p=out/(name+'.bin');p.write_bytes(payload);args+=['--update-section='+name+'='+str(p)]
    final=out/('oplus_fan.'+candidate_label+('.candidate.ko' if candidate_label=='CONTROL_TEST_PENDING' else '.ko'));args += [str(intermediate),str(final)];commands.append(r.command(args))
    candidate=r.Elf.read(final)
    marker=(candidate_label+';CANDIDATE;BTF=').encode()
    r.require(marker in candidate.data,'compiled candidate label marker missing')
    r.require(b'candidateLabel='+candidate_label.encode() in candidate.data,'compiled status candidate label missing')
    old_types={x['type'] for x in original.relocations};new_types={x['type'] for x in candidate.relocations}
    r.require(new_types<=old_types,'new relocation kind not already exercised by original target module: '+str(sorted(new_types-old_types)))
    comparison=contribution_check(original,candidate,patches,crcs)
    comparison['relocation_types_original']=sorted(old_types);comparison['relocation_types_candidate']=sorted(new_types)
    r.jwrite(out/'contribution-comparison.json',comparison)
    r.require(comparison['passed'],'incremental original-contribution check FAILED; do not use candidate')
    r.jwrite(out/'candidate.inventory.json',r.inventory(candidate));r.jwrite(out/'cfi-validation.json',cfi)
    result={'scope':candidate_label+' real policy+adapter candidate','candidate_label':candidate_label,'production_runtime_verified':False,'load_report_generated':False,'candidate':str(final),'candidate_sha256':r.sha(candidate.data),'original_sha256':r.sha(original.data),'original_unchanged':r.sha(r.OEM.read_bytes())==r.OEM_SHA256,'adapter_and_policy_linked':True,'original_contributions_preserved':True,'protocol_versions':[1,2],'ownership_strategies':{'0':'pass-through','1':'additive/v1-compatible','2':'full-owner-policy-only-including-zero'},'cold_default_enabled':False,'cold_default_strategy':0,'original_functions':85,'original_relocations':1414,'approved_relocation_changes':8,'original_imports':84,'added_imports':crcs,'original_version_count':85,'candidate_version_count':89,'cfi_callback_checks':len(cfi),'abi_validation':abi_result,'signature_original':r.signature_state(original.data),'signature_candidate':r.signature_state(candidate.data),'release_eligible':False,'runtime_verified':False,'commands':commands,'symbol_preparation':prepared_info,'source_hashes':{str(p.relative_to(ROOT)):r.sha(p.read_bytes()) for p in [SOURCE/'target_abi.h',SOURCE/'driver_adapter.c',SOURCE/'config_protocol.c',SOURCE/'config_protocol.h',Path(__file__),ROOT/'kernel/eta_fan_policy/policy_core.c',ROOT/'kernel/eta_fan_policy/policy_core.h',ROOT/'tools/gen_fan_target_abi.py']}}
    r.jwrite(out/'result.json',result);return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out-dir',required=True,type=Path)
    p.add_argument('--candidate-label',choices=('NONRELEASE_UNLOADED','CONTROL_TEST_PENDING'),default='NONRELEASE_UNLOADED');a=p.parse_args()
    try:
        result=build(a.out_dir,a.candidate_label);print(json.dumps({k:v for k,v in result.items() if k not in ('commands','symbol_preparation','abi_validation')},indent=2))
    except (ValueError,OSError) as e:
        print('FAIL CLOSED: '+str(e),file=sys.stderr);raise SystemExit(1)
