#!/usr/bin/env python3
"""Offline pinned-running-BTF projection; no guessed private fan structures."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT/'build/kernel-replace-audit/kernel-inputs'
BTF_SHA = '1870e245673b3eaae0d1fdafb0fd7027888b638018ed9561943a51dc2539d6af'
KO_SHA = 'a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd'
CONFIG_SHA = 'c1a63d7e622c6638e03d9c37816d3ec0d0bb40045af22dba0c093aeba4737123'


def check(ok, message):
    if not ok:
        raise ValueError(message)


class Btf:
    def __init__(self, data):
        magic, version, flags, hlen, toff, tlen, soff, slen = struct.unpack_from('<HBBIIIII', data)
        check((magic,version,flags)==(0xeb9f,1,0), 'unsupported BTF header')
        end=hlen+toff+tlen
        check(end<=len(data) and hlen+soff+slen<=len(data), 'BTF ranges out of file')
        strings=data[hlen+soff:hlen+soff+slen]
        def string(n):
            check(n<len(strings),'BTF bad string'); e=strings.find(b'\0',n)
            check(e>=0,'BTF unterminated string'); return strings[n:e].decode()
        self.types=[{'kind':0,'name':'void','size':0}]; pos=hlen+toff
        lengths={1:4,2:0,3:12,4:12,5:12,6:8,7:0,8:0,9:0,10:0,11:0,
                 12:0,13:8,14:4,15:12,16:0,17:4,18:0,19:12}
        while pos<end:
            n,info,size=struct.unpack_from('<III',data,pos);pos+=12
            kind=(info>>24)&31; count=info&65535; kflag=info>>31
            check(kind in lengths,'BTF unsupported kind')
            variable=kind in (4,5,6,13,15,19)
            length=lengths[kind]*(count if variable else 1)
            check(pos+length<=end,'BTF type payload outside type section')
            t={'id':len(self.types),'name':string(n),'kind':kind,'size':size,'count':count,'kflag':kflag}
            if kind in (4,5):
                t['members']=[]
                for i in range(count):
                    no,ty,off=struct.unpack_from('<III',data,pos+i*12)
                    t['members'].append({'name':string(no),'type':ty,'offset':off&0xffffff if kflag else off,'bits':off>>24 if kflag else 0})
            elif kind==6:
                t['values']={string(no):value for no,value in (struct.unpack_from('<Ii',data,pos+i*8) for i in range(count))}
            elif kind==13:
                t['params']=[{'name':string(no),'type':ty} for no,ty in (struct.unpack_from('<II',data,pos+i*8) for i in range(count))]
            elif kind==3:
                t['array']=struct.unpack_from('<III',data,pos)
            elif kind==1:
                t['encoding']=struct.unpack_from('<I',data,pos)[0]
            self.types.append(t);pos+=length
        check(pos==end,'BTF parse mismatch')

    def named(self,name,kind=None):
        rows=[t for t in self.types if t['name']==name and (kind is None or t['kind']==kind)]
        if len(rows)>1 and kind in (4,5):
            forms=[(t['size'],[(m['name'],m['offset'],m['bits'],self.shape(m['type'])) for m in t['members']]) for t in rows]
            check(all(x==forms[0] for x in forms),'conflicting duplicate BTF '+name)
            rows=rows[:1]
        if len(rows)>1 and kind==12:
            forms=[self.shape(t['size']) for t in rows]
            check(all(x==forms[0] for x in forms),'conflicting duplicate FUNC '+name)
            rows=rows[:1]
        check(len(rows)==1,f'BTF unique/equivalent {name} kind {kind}: {len(rows)}')
        return rows[0]

    def resolve(self,ty):
        t=self.types[ty]
        while t['kind'] in (8,9,10,11,18):t=self.types[t['size']]
        return t

    def shape(self,ty):
        t=self.resolve(ty);k=t['kind']
        if k==2:return '*'+self.shape(t['size'])
        if k==1:return ('s' if (t['encoding']>>24)&1 else 'u')+str(t['size']*8)
        if k==0:return 'void'
        if k in (4,5):return ('struct ' if k==4 else 'union ')+t['name']
        if k==6:return 'enum '+t['name']
        if k==13:return '('+','.join(self.shape(p['type']) for p in t['params'])+')->'+self.shape(t['size'])
        if k==3:return '['+str(t['array'][2])+']'+self.shape(t['array'][0])
        return f'kind{k}:{t["name"]}'

    def verify_struct(self,name,size,fields):
        t=self.named(name,4);check(t['size']==size,f'{name} size mismatch')
        rows={m['name']:m for m in t['members']}
        report=[]
        for name,off,shape in fields:
            check(name in rows and rows[name]['offset']==off*8 and rows[name]['bits']==0,f'field {t["name"]}.{name} offset mismatch')
            actual=self.shape(rows[name]['type']);check(actual==shape,f'{t["name"]}.{name} type {actual} != {shape}')
            report.append({'field':name,'offset':off,'type':actual})
        return {'name':t['name'],'size':size,'verified_fields':report}

    def func(self,name,signature):
        t=self.named(name,12);actual=self.shape(t['size'])
        check(actual==signature,f'{name}: {actual} != {signature}')
        return {'function':name,'signature':actual}


HEADER = r'''/* GENERATED by tools/gen_fan_target_abi.py from pinned running BTF.
 * Target-specific projections, NOT general replacement Linux headers.
 * No private OEM fan layout is defined. Do not use on another kernel.
 */
#ifndef ETA_FAN_TARGET_ABI_H
#define ETA_FAN_TARGET_ABI_H
#ifndef ETA_FAN_TARGET_ABI_VERIFIED
#error "Build only through the pinned offline adapter builder"
#endif
#define ETA_TARGET_BTF_SHA256 "@BTF@"
#define ETA_TARGET_CONFIG_SHA256 "@CONFIG@"
#define ETA_ORIGINAL_KO_SHA256 "@KO@"
typedef __SIZE_TYPE__ size_t;
typedef __PTRDIFF_TYPE__ ssize_t;
typedef signed int __s32;
typedef unsigned int __u32;
typedef unsigned long long __u64;
typedef _Bool bool;
#define true 1
#define false 0
#define NULL ((void *)0)
#define offsetof(T,F) __builtin_offsetof(T,F)
struct lock_class_key;
struct workqueue_struct;
struct thermal_zone_device;
struct power_supply;
struct bin_attribute;
struct kobject { unsigned char opaque[96]; } __attribute__((aligned(8)));
struct device { struct kobject kobj; unsigned char opaque[816]; };
struct platform_device { unsigned char prefix[16]; struct device dev; unsigned char tail[80]; };
struct mutex { unsigned char opaque[48]; } __attribute__((aligned(8)));
struct list_head { struct list_head *next,*prev; };
struct hlist_node { struct hlist_node *next,**pprev; };
struct work_struct { struct { long counter; } data; struct list_head entry; void (*func)(struct work_struct *); };
struct timer_list { struct hlist_node entry; unsigned long expires; void (*function)(struct timer_list *); unsigned int flags; };
struct delayed_work { struct work_struct work; struct timer_list timer; struct workqueue_struct *wq; int cpu; unsigned long long reserved1,reserved2; };
struct attribute { const char *name; unsigned short mode; unsigned char padding[6]; };
struct device_attribute { struct attribute attr; ssize_t (*show)(struct device *,struct device_attribute *,char *); ssize_t (*store)(struct device *,struct device_attribute *,const char *,size_t); };
struct attribute_group { const char *name; unsigned short (*is_visible)(struct kobject *,struct attribute *,int); unsigned short (*is_bin_visible)(struct kobject *,struct bin_attribute *,int); struct attribute **attrs; struct bin_attribute **bin_attrs; };
union power_supply_propval { int intval; const char *strval; };
enum power_supply_property { POWER_SUPPLY_PROP_STATUS=0, POWER_SUPPLY_PROP_PRESENT=3, POWER_SUPPLY_PROP_ONLINE=4 };
#define ETA_ASSERT_SIZE(T,N) _Static_assert(sizeof(struct T)==(N),#T " BTF size")
#define ETA_ASSERT_FIELD(T,F,N) _Static_assert(offsetof(struct T,F)==(N),#T "." #F " BTF offset")
ETA_ASSERT_SIZE(kobject,96); ETA_ASSERT_SIZE(device,912); ETA_ASSERT_SIZE(platform_device,1008);
ETA_ASSERT_SIZE(mutex,48); ETA_ASSERT_SIZE(work_struct,32); ETA_ASSERT_SIZE(delayed_work,104);
ETA_ASSERT_SIZE(timer_list,40); ETA_ASSERT_SIZE(attribute,16); ETA_ASSERT_SIZE(device_attribute,32); ETA_ASSERT_SIZE(attribute_group,40);
ETA_ASSERT_FIELD(device,kobj,0); ETA_ASSERT_FIELD(platform_device,dev,16);
ETA_ASSERT_FIELD(work_struct,data,0); ETA_ASSERT_FIELD(work_struct,entry,8); ETA_ASSERT_FIELD(work_struct,func,24);
ETA_ASSERT_FIELD(delayed_work,timer,32); ETA_ASSERT_FIELD(delayed_work,wq,72); ETA_ASSERT_FIELD(delayed_work,cpu,80);
ETA_ASSERT_FIELD(delayed_work,reserved1,88); ETA_ASSERT_FIELD(delayed_work,reserved2,96);
ETA_ASSERT_FIELD(timer_list,expires,16); ETA_ASSERT_FIELD(timer_list,function,24); ETA_ASSERT_FIELD(timer_list,flags,32);
ETA_ASSERT_FIELD(attribute,mode,8); ETA_ASSERT_FIELD(device_attribute,show,16); ETA_ASSERT_FIELD(device_attribute,store,24);
ETA_ASSERT_FIELD(attribute_group,attrs,24); ETA_ASSERT_FIELD(attribute_group,bin_attrs,32);
_Static_assert(sizeof(union power_supply_propval)==8,"propval BTF size");
/* Exact ACK work_bits with DEBUG_OBJECTS_WORK=n; NR_CPUS=32. */
#define ETA_WORK_NO_POOL (0x7fffffffUL << 21)
#define ETA_WORK_CPU_UNBOUND 32
#define ETA_TIMER_IRQSAFE 0x00200000U
#define ETA_WQ_UNBOUND 2U
#define ETA_WQ_FREEZABLE 4U
extern void __mutex_init(struct mutex *,const char *,struct lock_class_key *);
extern void mutex_lock(struct mutex *);
extern void mutex_unlock(struct mutex *);
extern long long ktime_get(void);
extern void init_timer_key(struct timer_list *,void (*)(struct timer_list *),unsigned int,const char *,struct lock_class_key *);
extern void delayed_work_timer_fn(struct timer_list *);
extern struct workqueue_struct *alloc_workqueue(const char *,unsigned int,int,...);
extern void destroy_workqueue(struct workqueue_struct *);
extern unsigned long __msecs_to_jiffies(const unsigned int);
extern bool queue_delayed_work_on(int,struct workqueue_struct *,struct delayed_work *,unsigned long);
extern bool mod_delayed_work_on(int,struct workqueue_struct *,struct delayed_work *,unsigned long);
extern bool cancel_delayed_work_sync(struct delayed_work *);
extern int sysfs_create_group(struct kobject *,const struct attribute_group *);
extern void sysfs_remove_group(struct kobject *,const struct attribute_group *);
extern int __devm_add_action(struct device *,void (*)(void *),void *,const char *);
extern struct thermal_zone_device *thermal_zone_get_zone_by_name(const char *);
extern int thermal_zone_get_temp(struct thermal_zone_device *,int *);
extern struct power_supply *power_supply_get_by_name(const char *);
extern int power_supply_get_property(struct power_supply *,enum power_supply_property,union power_supply_propval *);
extern void power_supply_put(struct power_supply *);
extern int scnprintf(char *,size_t,const char *,...);
#endif
'''


def generate(out,report):
    data=(INPUT/'vmlinux.btf').read_bytes();check(hashlib.sha256(data).hexdigest()==BTF_SHA,'running BTF identity mismatch')
    ko=(ROOT/'build/direct-node-audit/oplus_fan.ko').read_bytes();check(hashlib.sha256(ko).hexdigest()==KO_SHA,'OEM identity mismatch')
    config=(INPUT/'running.config').read_bytes();cfgsha=hashlib.sha256(config).hexdigest();check(cfgsha==CONFIG_SHA,'config identity mismatch')
    config=config.decode();required=['CONFIG_64BIT=y','CONFIG_SMP=y','CONFIG_NR_CPUS=32','CONFIG_HZ=250','CONFIG_CFI_CLANG=y','CONFIG_CFI_ICALL_NORMALIZE_INTEGERS=y','CONFIG_STACKPROTECTOR_STRONG=y','CONFIG_STACKPROTECTOR_PER_TASK=y','CONFIG_SHADOW_CALL_STACK=y']
    for line in required:check(line in config.splitlines(),'required config '+line)
    for key in ['CONFIG_DEBUG_OBJECTS_WORK','CONFIG_LOCKDEP','CONFIG_DEBUG_LOCK_ALLOC','CONFIG_DEBUG_OBJECTS_TIMERS']:
        check(not any(line.startswith(key+'=') for line in config.splitlines()),'unsupported enabled '+key)
    b=Btf(data);layouts=[]
    # Explicit checks below retain full primitive type/pointed-tag authority.
    for enum_name,expected in [('work_bits',{'WORK_STRUCT_FLAG_BITS':4,'WORK_OFFQ_FLAG_BITS':1,'WORK_OFFQ_DISABLE_BITS':16,'WORK_OFFQ_POOL_SHIFT':21,'WORK_OFFQ_POOL_BITS':31}),('wq_misc_consts',{'WORK_CPU_UNBOUND':32}),('wq_flags',{'WQ_UNBOUND':2,'WQ_FREEZABLE':4})]:
        values=b.named(enum_name,6)['values']
        for name,value in expected.items():check(values.get(name)==value,'running BTF work enum differs: '+name)
    for name,size,fields in [
        ('list_head',16,[('next',0,'*struct list_head'),('prev',8,'*struct list_head')]),
        ('hlist_node',16,[('next',0,'*struct hlist_node'),('pprev',8,'**struct hlist_node')]),
        ('work_struct',32,[('entry',8,'struct list_head'),('func',24,'*( *unused )')]),
        ('delayed_work',104,[('work',0,'struct work_struct'),('timer',32,'struct timer_list'),('wq',72,'*struct workqueue_struct'),('cpu',80,'s32'),('__kabi_reserved1',88,'u64'),('__kabi_reserved2',96,'u64')]),
        ('timer_list',40,[('entry',0,'struct hlist_node'),('expires',16,'u64'),('function',24,'*( *unused )'),('flags',32,'u32')]),
        ('device',912,[('kobj',0,'struct kobject')]),('platform_device',1008,[('dev',16,'struct device')]),
        ('attribute',16,[('name',0,'*u8'),('mode',8,'u16')]),
        ('device_attribute',32,[('attr',0,'struct attribute'),('show',16,'*(*struct device,*struct device_attribute,*u8)->s64'),('store',24,'*(*struct device,*struct device_attribute,*u8,u64)->s64')]),
        ('attribute_group',40,[('name',0,'*u8'),('is_visible',8,'*(*struct kobject,*struct attribute,s32)->u16'),('is_bin_visible',16,'*(*struct kobject,*struct bin_attribute,s32)->u16'),('attrs',24,'**struct attribute'),('bin_attrs',32,'**struct bin_attribute')]),
        ('mutex',48,[]),('kobject',96,[]),('task_struct',5184,[('stack_canary',1808,'u64')]) ]:
        fields=[(n,o, ('*(\u002astruct work_struct)->void' if name=='work_struct' else '*(\u002astruct timer_list)->void') if s=='*( *unused )' else s) for n,o,s in fields]
        layouts.append(b.verify_struct(name,size,fields))
    w=b.named('work_struct',4);wm={m['name']:m for m in w['members']};check(wm['data']['offset']==0,'work data offset');at=b.resolve(wm['data']['type']);check(at['kind']==4 and at['size']==8,'work atomic long size');check(len(at['members'])==1 and b.shape(at['members'][0]['type'])=='s64' and at['members'][0]['offset']==0,'work atomic data type')
    pv=b.named('power_supply_propval',5);check(pv['size']==8,'power union size');check({m['name']:b.shape(m['type']) for m in pv['members']}=={'intval':'s32','strval':'*u8'},'power union member types')
    ep=b.named('power_supply_property',6)['values']
    for n,v in [('STATUS',0),('PRESENT',3),('ONLINE',4)]:check(ep['POWER_SUPPLY_PROP_'+n]==v,'power enum mismatch')
    signatures={
      'mutex_lock':'(*struct mutex)->void','mutex_unlock':'(*struct mutex)->void',
      '__mutex_init':'(*struct mutex,*u8,*struct lock_class_key)->void',
      'ktime_get':'()->s64','init_timer_key':'(*struct timer_list,*(*struct timer_list)->void,u32,*u8,*struct lock_class_key)->void',
      'delayed_work_timer_fn':'(*struct timer_list)->void',
      'alloc_workqueue':'(*u8,u32,s32,void)->*struct workqueue_struct',
      'destroy_workqueue':'(*struct workqueue_struct)->void',
      '__msecs_to_jiffies':'(u32)->u64',
      'queue_delayed_work_on':'(s32,*struct workqueue_struct,*struct delayed_work,u64)->u8',
      'mod_delayed_work_on':'(s32,*struct workqueue_struct,*struct delayed_work,u64)->u8',
      'cancel_delayed_work_sync':'(*struct delayed_work)->u8',
      'sysfs_create_group':'(*struct kobject,*struct attribute_group)->s32',
      'sysfs_remove_group':'(*struct kobject,*struct attribute_group)->void',
      '__devm_add_action':'(*struct device,*(*void)->void,*void,*u8)->s32',
      'thermal_zone_get_zone_by_name':'(*u8)->*struct thermal_zone_device',
      'thermal_zone_get_temp':'(*struct thermal_zone_device,*s32)->s32',
      'power_supply_get_by_name':'(*u8)->*struct power_supply',
      'power_supply_get_property':'(*struct power_supply,enum power_supply_property,*union power_supply_propval)->s32',
      'power_supply_put':'(*struct power_supply)->void',
      'scnprintf':'(*u8,u64,*u8,void)->s32'}
    funcs=[b.func(n,s) for n,s in signatures.items()]
    headers={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (INPUT/'exact-headers').iterdir() if p.is_file()}
    check(len(headers)>=6,'exact headers missing')
    work=(INPUT/'exact-headers/include__linux__workqueue.h').read_text();timer=(INPUT/'exact-headers/include__linux__timer.h').read_text()
    for text in ['WORK_OFFQ_DISABLE_BITS\t= 16','WORK_OFFQ_FLAG_BITS\t= WORK_OFFQ_FLAG_END - WORK_OFFQ_FLAG_SHIFT','WORK_CPU_UNBOUND\t= NR_CPUS','WQ_UNBOUND\t\t= 1 << 1','WQ_FREEZABLE\t\t= 1 << 2']:
        check(text in work,'exact work init constant missing '+text)
    check('#define TIMER_IRQSAFE\t\t0x00200000' in timer,'timer flag differs')
    text=HEADER.replace('@BTF@',BTF_SHA).replace('@CONFIG@',cfgsha).replace('@KO@',KO_SHA)
    out=Path(out);out.write_text(text)
    result={'btf_sha256':BTF_SHA,'config_sha256':cfgsha,'original_ko_sha256':KO_SHA,'header_sha256':hashlib.sha256(text.encode()).hexdigest(),'layouts':layouts,'function_prototypes':funcs,'exact_header_hashes':headers,'private_fan_struct_used':False,'scope':'target ABI projections and direct API declarations; runtime and OEM semantics unverified'}
    Path(report).write_text(json.dumps(result,indent=2)+'\n');return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    print(json.dumps(generate(a.out,a.report),indent=2))
