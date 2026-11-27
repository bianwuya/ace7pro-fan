#define _GNU_SOURCE
#include "fan_preinit.h"
#include "sha256.h"
#include <ctype.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#ifndef FAN_MANIFEST_HEADER
#define FAN_MANIFEST_HEADER "manifest.h"
#endif
#include FAN_MANIFEST_HEADER

static int reject(const char **reason,const char *s) { *reason=s;return -1; }
static int bounded(uint64_t off,uint64_t n,size_t len) { return off<=len && n<=len-off; }
static uint16_t le16(const unsigned char *p) { return (uint16_t)((uint16_t)p[0]|((uint16_t)p[1]<<8)); }
static uint32_t le32(const unsigned char *p) { return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }
static uint64_t le64(const unsigned char *p) { return (uint64_t)le32(p)|((uint64_t)le32(p+4)<<32); }
static int is_hex(const char *s) {
    if(!s || strlen(s)!=64)return 0;
    for(unsigned i=0;i<64;i++)if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return 0;
    return 1;
}
static int valid_symbol(const unsigned char *s,size_t n) {
    if(!n || n>55)return 0;
    for(size_t i=0;i<n;i++)if(!(isalnum(s[i])||s[i]=='_'||s[i]=='.'))return 0;
    return 1;
}
int fan_validate_elf(const unsigned char *p,size_t n,struct fan_elf_info *out,const char **why) {
    uint64_t shoff;uint16_t shnum,shstr;const unsigned char *names=NULL,*mi=NULL,*vs=NULL,*ec=NULL,*en=NULL;
    size_t names_n=0,mi_n=0,vs_n=0,ec_n=0,en_n=0;unsigned mi_count=0,vs_count=0,ec_count=0,en_count=0,tm_count=0;
    memset(out,0,sizeof *out);
    if(n<64 || memcmp(p,"\177ELF",4) || p[EI_CLASS]!=ELFCLASS64 || p[EI_DATA]!=ELFDATA2LSB || p[EI_VERSION]!=EV_CURRENT || p[EI_OSABI]!=ELFOSABI_NONE || p[EI_ABIVERSION]!=0)
        return reject(why,"elf-ident");
    if(le16(p+16)!=ET_REL || le16(p+18)!=EM_AARCH64 || le32(p+20)!=EV_CURRENT || le64(p+24)!=0 || le64(p+32)!=0 || le32(p+48)!=0 || le16(p+52)!=64 || le16(p+54)!=0 || le16(p+56)!=0 || le16(p+58)!=64)
        return reject(why,"elf-header");
    shoff=le64(p+40);shnum=le16(p+60);shstr=le16(p+62);
    if(shoff<64 || shnum<4 || shnum>512 || !shstr || shstr>=shnum || !bounded(shoff,(uint64_t)shnum*64,n))return reject(why,"elf-sections");
    const unsigned char *ss=p+shoff+(size_t)shstr*64;
    if(le32(ss+4)!=SHT_STRTAB || !bounded(le64(ss+24),le64(ss+32),n))return reject(why,"elf-shstrtab");
    names=p+le64(ss+24);names_n=(size_t)le64(ss+32);
    if(!names_n || names[0]!=0)return reject(why,"elf-section-name");
    for(unsigned i=0;i<shnum;i++) {
        const unsigned char *s=p+shoff+(size_t)i*64;uint64_t off=le64(s+24),len=le64(s+32);uint32_t ni=le32(s),type=le32(s+4);
        if(ni>=names_n || !memchr(names+ni,0,names_n-ni))return reject(why,"elf-section-name");
        if(type!=SHT_NOBITS && !bounded(off,len,n))return reject(why,"elf-section-bounds");
        const char *name=(const char *)names+ni;
        if(!strcmp(name,".gnu.linkonce.this_module")) {
            /* Target ABI, independently observed in the pinned stock sample:
             * __this_module size 1600; module.name begins at byte 24 (56 bytes).
             * Do not generalize this to an arbitrary kernel module layout. */
            tm_count++;
            if(type!=SHT_PROGBITS || len!=1600 || !bounded(off,len,n) || memcmp(p+off+24,FAN_MODULE,sizeof FAN_MODULE) || !memchr(p+off+24,0,56))return reject(why,"this-module-identity");
        }
        if(!strcmp(name,".modinfo") || !strcmp(name,"__versions") || !strcmp(name,"__version_ext_crcs") || !strcmp(name,"__version_ext_names")) {
            if(type!=SHT_PROGBITS || !bounded(off,len,n) || !len || (le64(s+8)&SHF_COMPRESSED))return reject(why,"elf-metadata-section");
            if(!strcmp(name,".modinfo")){mi=p+off;mi_n=(size_t)len;mi_count++;}
            else if(!strcmp(name,"__versions")){vs=p+off;vs_n=(size_t)len;vs_count++;if(le64(s+56)!=0 && le64(s+56)!=64)return reject(why,"versions-entsize");}
            else if(!strcmp(name,"__version_ext_crcs")){ec=p+off;ec_n=(size_t)len;ec_count++;}
            else {en=p+off;en_n=(size_t)len;en_count++;}
        }
    }
    if(mi_count!=1 || vs_count!=1 || tm_count!=1)return reject(why,"missing-or-duplicate-metadata");
    unsigned found_name=0,found_vermagic=0;
    for(size_t off=0;off<mi_n;) {
        const unsigned char *end=memchr(mi+off,0,mi_n-off);
        if(!end)return reject(why,"modinfo-termination");
        size_t len=(size_t)(end-(mi+off));
        if(len>=5 && !memcmp(mi+off,"name=",5)){found_name++;if(len!=5+strlen(FAN_MODULE) || memcmp(mi+off+5,FAN_MODULE,strlen(FAN_MODULE)))return reject(why,"module-name");}
        if(len>=9 && !memcmp(mi+off,"vermagic=",9)) {
            found_vermagic++;size_t vlen=len-9;
            if(!vlen || vlen>=sizeof out->vermagic)return reject(why,"vermagic-size");
            for(size_t j=0;j<vlen;j++)if(mi[off+9+j]<32 || mi[off+9+j]>126)return reject(why,"vermagic-characters");
            memcpy(out->vermagic,mi+off+9,vlen);out->vermagic[vlen]=0;
        }
        off+=len+1;
    }
    if(found_name!=1 || found_vermagic!=1)return reject(why,"modinfo-name-vermagic-count");
    if(vs_n%64 || vs_n/64>4096)return reject(why,"versions-size");
    unsigned module_layout=0;size_t ext_pos=0;
    if(ec_count>1 || en_count>1 || ec_count!=en_count || (ec_count && ec_n!=(vs_n/64)*4))return reject(why,"extended-versions-shape");
    for(size_t i=0;i<vs_n/64;i++) {
        const unsigned char *v=vs+i*64,*end=memchr(v+8,0,56);uint64_t crc=le64(v);
        if(!crc || crc>UINT32_MAX || !end || !valid_symbol(v+8,(size_t)(end-(v+8))))return reject(why,"versions-crc-or-name");
        /* Reject duplicate symbol records, including conflicting CRCs. */
        for(size_t j=0;j<i;j++)if(!strcmp((const char *)v+8,(const char *)vs+j*64+8))return reject(why,"versions-duplicate");
        if(!strcmp((const char *)v+8,"module_layout"))module_layout++;
        if(ec_count) {
            if(ext_pos>=en_n)return reject(why,"extended-versions-names");
            const unsigned char *ee=memchr(en+ext_pos,0,en_n-ext_pos);
            if(!ee || (size_t)(ee-(en+ext_pos))!=(size_t)(end-(v+8)) || memcmp(en+ext_pos,v+8,(size_t)(end-(v+8))) || le32(ec+i*4)!=(uint32_t)crc)return reject(why,"extended-versions-mismatch");
            ext_pos=(size_t)(ee-en)+1;
        }
    }
    if(module_layout!=1)return reject(why,"versions-module-layout");
    /* The pinned OEM module's extended-name section has a trailing NUL pad.
     * Accept only zero padding, never another unpaired symbol name. */
    if(ec_count)for(size_t i=ext_pos;i<en_n;i++)if(en[i])return reject(why,"extended-versions-trailing-data");
    out->versions_count=(unsigned)(vs_n/64);fan_sha256_hex(vs,vs_n,out->versions_sha256);return 0;
}

static int decimal_component(const char **cursor,unsigned max,unsigned *out) {
    const char *p=*cursor;unsigned n=0;
    if(*p<'0' || *p>'9')return -1;
    do { unsigned d=(unsigned)(*p-'0');if(n>max/10 || (n==max/10 && d>max%10))return -1;n=n*10+d;p++; } while(*p>='0'&&*p<='9');
    *cursor=p;*out=n;return 0;
}
int fan_derive_kmi(const char *release,char *out,size_t cap) {
    unsigned major,minor,patch,android,generation;const char *p=release;
    if(!p || decimal_component(&p,100,&major) || *p++!='.' || decimal_component(&p,100,&minor) || *p++!='.' || decimal_component(&p,UINT_MAX,&patch) || strncmp(p,"-android",8))return -1;
    p+=8;
    if(decimal_component(&p,100,&android) || !android || *p++!='-' || decimal_component(&p,1000,&generation) || *p!='-' || !p[1])return -1;
    (void)patch;
    int n=snprintf(out,cap,"%u.%u-android%u-%u",major,minor,android,generation);
    return n>0 && (size_t)n<cap?0:-1;
}
int fan_validate_manifest(const struct fan_manifest *m,const char **why) {
    if(m->present!=1)return reject(why,"replacement-not-configured");
    if(!m->filename || strcmp(m->filename,FAN_REPLACEMENT))return reject(why,"replacement-path");
    if(!is_hex(m->sha256) || !is_hex(m->versions_sha256) || !strcmp(m->sha256,FAN_STOCK_SHA) || m->size<64 || m->size>FAN_MAX_SIZE || !m->versions_count || m->versions_count>4096)return reject(why,"manifest-identity");
    if(!m->kernel_release || !m->kmi || !m->vermagic || !*m->kernel_release || strlen(m->kernel_release)>=128 || strlen(m->vermagic)>=256)return reject(why,"manifest-kernel");
    for(const char *s=m->kernel_release;*s;s++)if(!(isalnum((unsigned char)*s)||*s=='.'||*s=='_'||*s=='-'||*s=='+'))return reject(why,"manifest-release-characters");
    char kmi[96];if(fan_derive_kmi(m->kernel_release,kmi,sizeof kmi) || strcmp(kmi,m->kmi))return reject(why,"manifest-kmi");
    /* Observed target: exact runtime uname and stock vermagic release tokens
     * differ. With MODVERSIONS, do NOT rewrite vermagic or equate that first
     * token to uname. Pin uname separately; require matching KMI and the
     * exact flags from pinned stock. Generator also requires stock/new full
     * vermagic equality, while replacement CRC identity is independently pinned. */
    const char *flags=strchr(m->vermagic,' ');char module_release[128],module_kmi[96];
    if(!flags || flags==m->vermagic || (size_t)(flags-m->vermagic)>=sizeof module_release || strcmp(flags,FAN_STOCK_VERMAGIC_FLAGS))return reject(why,"manifest-vermagic");
    size_t release_len=(size_t)(flags-m->vermagic);memcpy(module_release,m->vermagic,release_len);module_release[release_len]=0;
    if(fan_derive_kmi(module_release,module_kmi,sizeof module_kmi) || strcmp(module_kmi,m->kmi))return reject(why,"manifest-vermagic-kmi");
    return 0;
}
static int safe_stat(const struct stat *s) { return S_ISREG(s->st_mode) && s->st_nlink==1 && s->st_uid==0 && !(s->st_mode&(S_IWGRP|S_IWOTH)); }
static int same_inode(const struct stat *a,const struct stat *b) { return a->st_dev==b->st_dev && a->st_ino==b->st_ino; }
static int same_snapshot(const struct stat *a,const struct stat *b) {
    return same_inode(a,b) && a->st_size==b->st_size && a->st_mode==b->st_mode && a->st_uid==b->st_uid && a->st_gid==b->st_gid && a->st_nlink==b->st_nlink && a->st_mtim.tv_sec==b->st_mtim.tv_sec && a->st_mtim.tv_nsec==b->st_mtim.tv_nsec && a->st_ctim.tv_sec==b->st_ctim.tv_sec && a->st_ctim.tv_nsec==b->st_ctim.tv_nsec;
}
int fan_validate_replacement(const struct fan_blob *b,const struct stat *target,const struct fan_manifest *m,const char **why) {
    if(fan_validate_manifest(m,why))return -1;
    if(!safe_stat(&b->st) || same_inode(&b->st,target))return reject(why,"replacement-file-identity");
    if(b->size!=m->size || b->st.st_size<0 || (uint64_t)b->st.st_size!=m->size)return reject(why,"replacement-size");
    char sha[65];fan_sha256_hex(b->data,b->size,sha);if(strcmp(sha,m->sha256))return reject(why,"replacement-sha256");
    struct fan_elf_info info;if(fan_validate_elf(b->data,b->size,&info,why))return -1;
    if(strcmp(info.vermagic,m->vermagic) || strcmp(info.versions_sha256,m->versions_sha256) || info.versions_count!=m->versions_count)return reject(why,"replacement-abi-identity");
    return 0;
}
int fan_open_absolute(const char *path,int flags) {
    char buf[PATH_MAX];size_t n=strlen(path);
    if(n<2 || n>=sizeof buf || path[0]!='/' || path[n-1]=='/'){errno=EINVAL;return -1;}
    memcpy(buf,path+1,n);int fd=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);if(fd<0)return -1;
    char *part=buf;
    for(;;) {
        char *slash=strchr(part,'/');if(slash)*slash=0;
        if(!*part || !strcmp(part,".") || !strcmp(part,"..")){close(fd);errno=EINVAL;return -1;}
        int next=openat(fd,part,(slash?(O_PATH|O_DIRECTORY):flags)|O_NOFOLLOW|O_CLOEXEC);
        int saved=errno;close(fd);if(next<0){errno=saved;return -1;}fd=next;
        if(!slash)break;part=slash+1;
    }
    return fd;
}
int fan_open_payload(int dirfd,const char *filename) {
    if(!filename || strcmp(filename,FAN_REPLACEMENT)){errno=EINVAL;return -1;}
    /* O_NONBLOCK prevents an attacker-controlled FIFO/device from hanging us;
     * fstat rejects every non-regular file before any content read. */
    int fd=openat(dirfd,filename,O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);
    if(fd<0)return -1;struct stat st;
    if(fstat(fd,&st)){int saved=errno;close(fd);errno=saved;return -1;}
    if(!safe_stat(&st) || st.st_size<64 || (uint64_t)st.st_size>FAN_MAX_SIZE){close(fd);errno=EINVAL;return -1;}
    return fd;
}
int fan_read_blob(int fd,struct fan_blob *b) {
    memset(b,0,sizeof *b);
    if(fstat(fd,&b->st))return -1;
    if(!safe_stat(&b->st) || b->st.st_size<64 || (uint64_t)b->st.st_size>FAN_MAX_SIZE){errno=EINVAL;return -1;}
    b->size=(size_t)b->st.st_size;b->data=malloc(b->size);if(!b->data)return -1;
    size_t pos=0;while(pos<b->size) { ssize_t n=pread(fd,b->data+pos,b->size-pos,(off_t)pos);if(n<0&&errno==EINTR)continue;if(n<=0){fan_free_blob(b);errno=EIO;return -1;}pos+=(size_t)n; }
    unsigned char extra;ssize_t tail=pread(fd,&extra,1,(off_t)pos);struct stat end;
    if(tail!=0 || fstat(fd,&end) || !same_snapshot(&b->st,&end)){fan_free_blob(b);errno=ESTALE;return -1;}
    return 0;
}
void fan_free_blob(struct fan_blob *b) { free(b->data);memset(b,0,sizeof *b); }
int fan_vendor_context_matches(const char *context,size_t size) {
    const size_t len=sizeof(FAN_VENDOR_CONTEXT)-1;
    return context && (size==len || (size==len+1 && context[len]==0)) && !memcmp(context,FAN_VENDOR_CONTEXT,len);
}
int fan_vendor_label_ok(int fd) {
    char context[128];ssize_t size=fgetxattr(fd,"security.selinux",context,sizeof context);
    if(size<0)return 0;
    return fan_vendor_context_matches(context,(size_t)size);
}
int fan_prepare_replacement_fd(int fd,const struct stat *target,const struct fan_manifest *manifest,struct stat *snapshot,const char **why) {
    struct fan_blob b;
    if(fan_read_blob(fd,&b)){*why="replacement-read-failed";return -1;}
    int invalid=fan_validate_replacement(&b,target,manifest,why);fan_free_blob(&b);
    if(invalid)return -1;
    /* Only this already hash/ELF/CRC-pinned replacement inode is relabelled.
     * No policy edits, identity changes, stock mutations or permission widening.
     * Metadata's default vold_metadata_file is unreadable to vendor_modprobe.
     * Set the exact observed stock label before its read-only bind/SELinux load. */
    if(!fan_vendor_label_ok(fd) && fsetxattr(fd,"security.selinux",FAN_VENDOR_CONTEXT,sizeof(FAN_VENDOR_CONTEXT),0)){
        *why="replacement-vendor-label-set-failed";return -1;
    }
    if(!fan_vendor_label_ok(fd)){errno=EACCES;*why="replacement-vendor-label-readback-failed";return -1;}
    /* Setting an xattr legitimately changes ctime. Re-read and revalidate all
     * bytes and identities, then pin the new snapshot for final bind rechecks. */
    if(fan_read_blob(fd,&b)){*why="replacement-post-label-read-failed";return -1;}
    invalid=fan_validate_replacement(&b,target,manifest,why);
    if(!invalid)*snapshot=b.st;
    fan_free_blob(&b);return invalid;
}
const char *fan_gate_values(const char *preinit,const char *ksu,unsigned uid,unsigned euid,uint64_t ms,int ns_ok,int proc_ok,int loaded,int sys_ok,const char *machine,const char *release,const struct fan_manifest *m) {
    if(!preinit || strcmp(preinit,"1") || !ksu || strcmp(ksu,"true"))return "not-ksu-preinit";
    if(uid || euid)return "not-root";
    if(ms>FAN_DEADLINE_MS)return "preinit-boot-deadline";
    if(!ns_ok)return "mount-namespace-not-init";
    if(!proc_ok || !sys_ok)return "module-state-unavailable";
    if(loaded)return "module-already-loaded";
    if(!machine || strcmp(machine,"aarch64"))return "uname-architecture";
    char kmi[96];if(!release || fan_derive_kmi(release,kmi,sizeof kmi) || strcmp(kmi,m->kmi) || strcmp(release,m->kernel_release))return "uname-kmi-release";
    return NULL;
}
int fan_cli_exit_code(int internal_code) { return internal_code==10?0:internal_code; }
static struct fan_result result(int code,const char *state,const char *reason,int error_number) { struct fan_result r={code,state,reason,error_number};return r; }
static struct fan_result rollback(const struct fan_ops *o,const char *reason,int saved) {
    /* Never stack a second bind, retry remount, detach another mount, or write
     * the original. Without positive ownership we report uncertainty. */
    if(o->owned(o->ctx)!=1)return result(29,"uncertain-mount","rollback-ownership-unproven",saved);
    errno=0;
    if(o->unmount(o->ctx))return result(29,"uncertain-mount","rollback-unmount-failed",errno);
    errno=0;
    if(o->stock_restored(o->ctx)!=1)return result(29,"uncertain-mount","rollback-stock-unverified",errno);
    return result(28,"stock-retained",reason,saved);
}
struct fan_result fan_transaction(const struct fan_ops *o) {
    const char *why=o->guard(o->ctx);if(why)return result(22,"stock-retained",why,0);
    errno=0;why=o->recheck(o->ctx);if(why)return result(26,"stock-retained",why,errno);
    /* Last gate immediately before the only bind call; no claim of atomicity
     * against module loading. An early synchronous KSU caller is required. */
    why=o->guard(o->ctx);if(why)return result(26,"stock-retained",why,0);
    errno=0;if(o->bind(o->ctx))return result(27,"stock-retained","bind-failed",errno);
    errno=0;if(o->capture(o->ctx))return rollback(o,"capture-failed-rolled-back",errno);
    errno=0;if(o->remount_ro(o->ctx))return rollback(o,"remount-failed-rolled-back",errno);
    errno=0;if(o->verify(o->ctx))return rollback(o,"verification-failed-rolled-back",errno);
    return result(10,"applied","replacement-bound-readonly",0);
}

int fan_validate_stock_fd(int fd,const struct stat *expected) {
    struct fan_blob b;const char *why=NULL;struct fan_elf_info info;char sha[65];
    if(fan_read_blob(fd,&b))return -1;
    int ok=b.size==FAN_STOCK_SIZE && (!expected || same_snapshot(&b.st,expected));
    fan_sha256_hex(b.data,b.size,sha);if(strcmp(sha,FAN_STOCK_SHA) || fan_validate_elf(b.data,b.size,&info,&why))ok=0;
    fan_free_blob(&b);if(!ok){errno=ESTALE;return -1;}return 0;
}

#ifndef FAN_TEST_LIBRARY
struct production {
    int stock_fd,payload_fd,mounted_fd;
    uint64_t stock_mount,new_mount;
    struct stat stock_stat,payload_stat;
    const struct fan_manifest *manifest;
};
static int read_text(const char *path,char *buf,size_t cap) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return -1;
    size_t used=0;while(used<cap-1){ssize_t n=read(fd,buf+used,cap-1-used);if(n<0&&errno==EINTR)continue;if(n<0){int e=errno;close(fd);errno=e;return -1;}if(!n)break;used+=(size_t)n;}
    char extra;ssize_t n=read(fd,&extra,1);int e=errno;close(fd);if(n!=0){errno=n<0?e:EOVERFLOW;return -1;}buf[used]=0;return 0;
}
static void fd_path(int fd,char out[64]) { (void)snprintf(out,64,"/proc/self/fd/%d",fd); }
static int mount_id(int fd,uint64_t *id) {
    char path[64],buf[4096];(void)snprintf(path,sizeof path,"/proc/self/fdinfo/%d",fd);
    if(read_text(path,buf,sizeof buf))return -1;
    char *line=buf;unsigned count=0;while(line&&*line){char *next=strchr(line,'\n');if(next)*next++=0;if(!strncmp(line,"mnt_id:",7)){char tail;uint64_t value;if(sscanf(line+7,"%"SCNu64" %c",&value,&tail)!=1 || !value){errno=EINVAL;return -1;}*id=value;count++;}line=next;}
    if(count!=1){errno=EINVAL;return -1;}return 0;
}
static int procfd_identity(int fd) {
    char path[64];struct stat a,b;fd_path(fd,path);
    if(fstat(fd,&a) || stat(path,&b))return -1;
    if(!same_inode(&a,&b)){errno=ESTALE;return -1;}return 0;
}
/* A mountinfo line must have the exact canonical mountpoint, not its alias.
 * Kernel escaping cannot disguise FAN_TARGET (it has no escapable bytes). */
#endif
int fan_mount_record(const char *line,uint64_t expected,const char *target,int *readonly) {
    uint64_t id=0,parent=0;char dev[64],root[PATH_MAX],point[PATH_MAX],opts[256];
    if(sscanf(line,"%"SCNu64" %"SCNu64" %63s %4095s %4095s %255s",&id,&parent,dev,root,point,opts)!=6 || !strstr(line," - "))return -1;
    (void)parent;
    if(strcmp(point,target))return 0;
    if(expected && id!=expected)return -2;
    int ro=0,rw=0;char *save=NULL;for(char *s=strtok_r(opts,",",&save);s;s=strtok_r(NULL,",",&save)){if(!strcmp(s,"ro"))ro++;if(!strcmp(s,"rw"))rw++;}
    if(ro+rw!=1)return -1;
    *readonly=ro;return 1;
}
int fan_readonly_ok(int mountinfo_ro,unsigned long vfs_flags) { return mountinfo_ro==1 && (vfs_flags&ST_RDONLY)!=0; }
#ifndef FAN_TEST_LIBRARY
static int inspect_target_mount(uint64_t expected,int *readonly) {
    /* Streaming with bounded line length: no giant mountinfo allocation. */
    int fd=open("/proc/self/mountinfo",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return -1;
    FILE *f=fdopen(fd,"r");if(!f){close(fd);return -1;}char line[16384];int found=0,bad=0;unsigned rows=0;
    while(fgets(line,sizeof line,f)) {
        if(++rows>100000 || !strchr(line,'\n')){bad=1;break;}
        int ro=0,r=fan_mount_record(line,expected,FAN_TARGET,&ro);
        if(r<0){bad=1;break;}if(r==1){found++;*readonly=ro;}
    }
    if(ferror(f))bad=1;fclose(f);if(bad || found>1){errno=EINVAL;return -1;}return found;
}
static const char *production_guard(void *ctx0) {
    struct production *p=ctx0;struct stat self_ns,init_ns,sysdir,mod;struct utsname u;struct timespec t;
    const char *preinit=getenv("KSU_PREINIT"),*ksu=getenv("KSU");
    if(!preinit || strcmp(preinit,"1") || !ksu || strcmp(ksu,"true"))return "not-ksu-preinit";
    if(getuid()!=0 || geteuid()!=0)return "not-root";
    if(clock_gettime(CLOCK_BOOTTIME,&t) || t.tv_sec<0 || (uint64_t)t.tv_sec>UINT64_MAX/1000)return "boottime-unavailable";
    uint64_t ms=(uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000;
    if(ms>FAN_DEADLINE_MS)return "preinit-boot-deadline";
    int ns_ok=!stat("/proc/self/ns/mnt",&self_ns) && !stat("/proc/1/ns/mnt",&init_ns) && same_inode(&self_ns,&init_ns);
    /* Complete bounded /proc/modules inventory, not substring searching. */
    int loaded=0,proc_ok=1;int fd=open("/proc/modules",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)proc_ok=0;
    else {
        FILE *f=fdopen(fd,"r");if(!f){close(fd);proc_ok=0;}
        else {char line[4096];unsigned rows=0;while(fgets(line,sizeof line,f)){char name[128];unsigned long long size,refs;if(++rows>16384 || !strchr(line,'\n') || sscanf(line,"%127s %llu %llu",name,&size,&refs)!=3){proc_ok=0;break;}if(!strcmp(name,FAN_MODULE))loaded=1;}if(ferror(f))proc_ok=0;fclose(f);}
    }
    int sys_ok=!stat("/sys/module",&sysdir) && S_ISDIR(sysdir.st_mode);
    if(!lstat("/sys/module/"FAN_MODULE,&mod))loaded=1;else if(errno!=ENOENT)sys_ok=0;
    if(uname(&u))return "uname-unavailable";
    if(clock_gettime(CLOCK_BOOTTIME,&t) || t.tv_sec<0 || (uint64_t)t.tv_sec>UINT64_MAX/1000)return "boottime-unavailable";
    ms=(uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000;
    return fan_gate_values(preinit,ksu,(unsigned)getuid(),(unsigned)geteuid(),ms,ns_ok,proc_ok,loaded,sys_ok,u.machine,u.release,p->manifest);
}
static const char *production_recheck(void *ctx0) {
    struct production *p=ctx0;int ro=0;struct fan_blob b;const char *why=NULL;struct stat target,source;uint64_t mid;
    int current=fan_open_absolute(FAN_TARGET,O_RDONLY|O_NONBLOCK);
    if(current<0)return "target-reopen-failed";
    int bad=fstat(current,&target) || !same_snapshot(&target,&p->stock_stat) || mount_id(current,&mid) || mid!=p->stock_mount;
    close(current);if(bad)return "target-path-drift";
    if(inspect_target_mount(0,&ro)!=0)return "target-already-mounted-or-unknown";
    if(fan_validate_stock_fd(p->stock_fd,&p->stock_stat))return "stock-recheck-failed";
    if(fstat(p->payload_fd,&source) || !same_snapshot(&source,&p->payload_stat))return "replacement-fd-drift";
    if(!fan_vendor_label_ok(p->payload_fd))return "replacement-vendor-label-drift";
    if(fan_read_blob(p->payload_fd,&b))return "replacement-recheck-read";
    int invalid=fan_validate_replacement(&b,&p->stock_stat,p->manifest,&why);fan_free_blob(&b);if(invalid)return why;
    if(procfd_identity(p->stock_fd) || procfd_identity(p->payload_fd))return "procfd-unavailable";
    return NULL;
}
static int production_bind(void *ctx0) {
    struct production *p=ctx0;char source[64],target[64];fd_path(p->payload_fd,source);fd_path(p->stock_fd,target);
    return mount(source,target,NULL,MS_BIND,NULL);
}
static int production_owned(void *ctx0) {
    struct production *p=ctx0;int ro=0;struct stat st;uint64_t mid;
    if(p->mounted_fd<0 || !p->new_mount || p->new_mount==p->stock_mount || fstat(p->mounted_fd,&st) || !same_inode(&st,&p->payload_stat) || mount_id(p->mounted_fd,&mid) || mid!=p->new_mount || inspect_target_mount(p->new_mount,&ro)!=1)return 0;
    int fd=fan_open_absolute(FAN_TARGET,O_RDONLY|O_NONBLOCK);if(fd<0)return 0;
    int ok=!fstat(fd,&st) && same_inode(&st,&p->payload_stat) && !mount_id(fd,&mid) && mid==p->new_mount;
    close(fd);return ok;
}
static int production_capture(void *ctx0) {
    struct production *p=ctx0;struct stat st;uint64_t mid;
    p->mounted_fd=fan_open_absolute(FAN_TARGET,O_RDONLY|O_NONBLOCK);
    if(p->mounted_fd<0)return -1;
    if(fstat(p->mounted_fd,&st) || mount_id(p->mounted_fd,&mid))return -1;
    if(!same_inode(&st,&p->payload_stat) || mid==p->stock_mount){errno=ESTALE;return -1;}
    p->new_mount=mid;
    if(!production_owned(p)){errno=ESTALE;return -1;}
    return procfd_identity(p->mounted_fd);
}
static int production_remount(void *ctx0) {
    struct production *p=ctx0;char target[64];if(!production_owned(p)){errno=ESTALE;return -1;}
    /* The original target FD intentionally still pins STOCK's mount. Remount
     * must use the newly opened, positively identified overlay FD, not it. */
    fd_path(p->mounted_fd,target);return mount(NULL,target,NULL,MS_REMOUNT|MS_BIND|MS_RDONLY,NULL);
}
static int production_verify(void *ctx0) {
    struct production *p=ctx0;struct statvfs vfs;struct fan_blob b;const char *why=NULL;int ro=0;
    if(!production_owned(p)){errno=ESTALE;return -1;}
    int seen=inspect_target_mount(p->new_mount,&ro);if(seen<0)return -1;if(seen!=1){errno=ESTALE;return -1;}
    if(fstatvfs(p->mounted_fd,&vfs))return -1;
    if(!fan_readonly_ok(ro,vfs.f_flag)){errno=EROFS;return -1;}
    if(!fan_vendor_label_ok(p->mounted_fd)){errno=EACCES;return -1;}
    if(fan_read_blob(p->mounted_fd,&b))return -1;
    int bad=fan_validate_replacement(&b,&p->stock_stat,p->manifest,&why);fan_free_blob(&b);if(bad){errno=ESTALE;return -1;}return 0;
}
static int production_unmount(void *ctx0) {
    struct production *p=ctx0;char path[64];if(!production_owned(p)){errno=ESTALE;return -1;}fd_path(p->mounted_fd,path);
    return umount2(path,0); /* No MNT_DETACH / forced or lazy rollback. */
}
static int production_restored(void *ctx0) {
    struct production *p=ctx0;int ro=0;uint64_t mid;struct stat st;
    if(inspect_target_mount(0,&ro)!=0)return 0;
    int fd=fan_open_absolute(FAN_TARGET,O_RDONLY|O_NONBLOCK);if(fd<0)return 0;
    int ok=!fstat(fd,&st) && same_snapshot(&st,&p->stock_stat) && !mount_id(fd,&mid) && mid==p->stock_mount && !fan_validate_stock_fd(fd,&p->stock_stat);
    close(fd);return ok;
}
static int payload_directory(void) {
    char exe[PATH_MAX];ssize_t n=readlink("/proc/self/exe",exe,sizeof exe-1);if(n<=0 || (size_t)n>=sizeof exe-1){errno=EINVAL;return -1;}exe[n]=0;
    if(strstr(exe," (deleted)")){errno=ESTALE;return -1;}char *slash=strrchr(exe,'/');if(!slash || slash==exe){errno=EINVAL;return -1;}*slash=0;
    /* Always executable's own preinit directory, never cwd, argv[0], module
     * path variables, or a caller-supplied config/payload path. */
    int fd=fan_open_absolute(exe,O_RDONLY|O_DIRECTORY);if(fd<0)return -1;struct stat st;
    if(fstat(fd,&st) || !S_ISDIR(st.st_mode) || st.st_uid!=0 || (st.st_mode&(S_IWGRP|S_IWOTH))){close(fd);errno=EINVAL;return -1;}return fd;
}
static int finish(struct fan_result r) {
    int exit_code=fan_cli_exit_code(r.code);
    printf("fan-preinit result=%s exit=%d reason=%s errno=%d\n",r.state,exit_code,r.reason,r.error_number);return exit_code;
}
int main(int argc,char **argv) {
    int apply=0;
    if(argc==2 && !strcmp(argv[1],"--apply"))apply=1;
    else if(argc>2 || (argc==2 && strcmp(argv[1],"--check-only")))return finish(result(20,"not-applied","usage-check-only-or-apply",0));
    const char *why=NULL;
    if(apply && fan_validate_manifest(&fan_release_manifest,&why))return finish(result(fan_release_manifest.present?24:21,"not-applied",why,0));
    struct production p={.stock_fd=-1,.payload_fd=-1,.mounted_fd=-1,.manifest=&fan_release_manifest};
    if(apply){why=production_guard(&p);if(why)return finish(result(22,"not-applied",why,0));}
    p.stock_fd=fan_open_absolute(FAN_TARGET,O_RDONLY|O_NONBLOCK);
    if(p.stock_fd<0)return finish(result(23,"not-applied","stock-open-failed",errno));
    struct fan_result r;
    if(fstat(p.stock_fd,&p.stock_stat) || fan_validate_stock_fd(p.stock_fd,NULL))r=result(23,"not-applied","stock-identity-failed",errno);
    else if(!apply)r=result(0,"stock-retained",fan_release_manifest.present?"stock-verified-check-only-no-mount":"stock-verified-check-only-no-payload-no-mount",0);
    else {
        int dir=payload_directory();
        if(dir<0)r=result(25,"stock-retained","preinit-directory-unavailable",errno);
        else {
            p.payload_fd=fan_open_payload(dir,p.manifest->filename);close(dir);
            if(p.payload_fd<0)r=result(25,"stock-retained","replacement-open-failed",errno);
            else {
                if(fan_prepare_replacement_fd(p.payload_fd,&p.stock_stat,p.manifest,&p.payload_stat,&why))r=result(25,"stock-retained",why,errno);
                else if(mount_id(p.stock_fd,&p.stock_mount))r=result(26,"stock-retained","stock-mount-id-unavailable",errno);
                else {
                    const struct fan_ops ops={&p,production_guard,production_recheck,production_bind,production_capture,production_remount,production_verify,production_owned,production_unmount,production_restored};
                    r=fan_transaction(&ops);
                }
            }
        }
    }
    if(p.mounted_fd>=0)close(p.mounted_fd);if(p.payload_fd>=0)close(p.payload_fd);close(p.stock_fd);return finish(r);
}
#endif
