/* OFFLINE ONLY: synthetic parser bytes, temporary regular files, and injected
 * transaction callbacks. This executable never invokes mount or umount. */
#define FAN_TEST_LIBRARY
#include "fan_preinit.c"
static unsigned checks=0;
#define CHECK(c,label) do { checks++;if(!(c)){fprintf(stderr,"FAIL %u: %s (line %d)\n",checks,label,__LINE__);exit(1);}printf("ok %u - %s\n",checks,label); } while(0)
static void w16(unsigned char *p,uint16_t x){p[0]=(unsigned char)x;p[1]=(unsigned char)(x>>8);}
static void w32(unsigned char *p,uint32_t x){for(unsigned i=0;i<4;i++)p[i]=(unsigned char)(x>>(8*i));}
static void w64(unsigned char *p,uint64_t x){for(unsigned i=0;i<8;i++)p[i]=(unsigned char)(x>>(8*i));}
static const char vm[]="6.12.69-android16-6-offline SMP preempt mod_unload modversions aarch64";
static unsigned char fixture[4096];
static void section(unsigned index,uint32_t name,uint32_t type,uint64_t off,uint64_t size) {
    unsigned char *s=fixture+3072+index*64;w32(s,name);w32(s+4,type);w64(s+24,off);w64(s+32,size);
}
static void fresh_fixture(void) {
    static const char names[]="\0.shstrtab\0.modinfo\0__versions\0.gnu.linkonce.this_module\0";
    memset(fixture,0,sizeof fixture);memcpy(fixture,"\177ELF\2\1\1",7);
    w16(fixture+16,ET_REL);w16(fixture+18,EM_AARCH64);w32(fixture+20,EV_CURRENT);w64(fixture+40,3072);w16(fixture+52,64);w16(fixture+58,64);w16(fixture+60,5);w16(fixture+62,1);
    memcpy(fixture+64,names,sizeof names);
    const char *name="name="FAN_MODULE;size_t nn=strlen(name)+1;memcpy(fixture+192,name,nn);memcpy(fixture+192+nn,"vermagic=",9);memcpy(fixture+192+nn+9,vm,sizeof vm);
    w64(fixture+384,0x12345678);memcpy(fixture+392,"module_layout",14);
    w64(fixture+448,0x90abcdef);memcpy(fixture+456,"_printk",8);
    memcpy(fixture+512+24,FAN_MODULE,sizeof FAN_MODULE);
    section(1,1,SHT_STRTAB,64,sizeof names);section(2,11,SHT_PROGBITS,192,nn+9+sizeof vm);section(3,20,SHT_PROGBITS,384,128);section(4,31,SHT_PROGBITS,512,1600);
}
static struct fan_manifest fixture_manifest(char sha[65],char versions[65]) {
    fresh_fixture();fan_sha256_hex(fixture,sizeof fixture,sha);fan_sha256_hex(fixture+384,128,versions);
    struct fan_manifest m={1,FAN_REPLACEMENT,sha,sizeof fixture,"6.12.69-android16-6-offline","6.12-android16-6",vm,versions,2};return m;
}
static struct fan_blob fixture_blob(void) {
    struct fan_blob b={.data=fixture,.size=sizeof fixture};b.st.st_mode=S_IFREG|0644;b.st.st_nlink=1;b.st.st_uid=0;b.st.st_size=sizeof fixture;b.st.st_dev=1;b.st.st_ino=2;return b;
}
static void sha_tests(void) {
    char out[65];fan_sha256_hex("",0,out);CHECK(!strcmp(out,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"SHA-256 empty NIST vector");
    fan_sha256_hex("abc",3,out);CHECK(!strcmp(out,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"SHA-256 abc NIST vector");
    const char *longv="abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";fan_sha256_hex(longv,strlen(longv),out);CHECK(!strcmp(out,"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),"SHA-256 two-block NIST vector");
    struct fan_sha256 s;unsigned char block[1000],digest[32];memset(block,'a',sizeof block);fan_sha256_init(&s);for(unsigned i=0;i<1000;i++)fan_sha256_update(&s,block,sizeof block);fan_sha256_final(&s,digest);
    for(unsigned i=0;i<32;i++)(void)snprintf(out+2*i,3,"%02x",digest[i]);CHECK(!strcmp(out,"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),"SHA-256 million-a incremental NIST vector");
}
static void elf_tests(void) {
    struct fan_elf_info info;const char *why=NULL;fresh_fixture();
    CHECK(!fan_validate_elf(fixture,sizeof fixture,&info,&why),"synthetic non-loadable metadata parser fixture accepted");
    CHECK(info.versions_count==2&&!strcmp(info.vermagic,vm),"vermagic and CRC record count read exactly");
    fixture[0]=0;CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"bad ELF magic rejected");fresh_fixture();fixture[EI_CLASS]=ELFCLASS32;CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"ELF32 rejected");
    fresh_fixture();fixture[EI_DATA]=ELFDATA2MSB;CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"big-endian ELF rejected");
    fresh_fixture();w16(fixture+18,EM_X86_64);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"wrong architecture rejected");
    fresh_fixture();w16(fixture+16,ET_EXEC);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"executable instead of relocatable rejected");
    fresh_fixture();w64(fixture+40,UINT64_MAX-30);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"overflowing section table rejected");
    fresh_fixture();w64(fixture+3072+2*64+24,4090);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"out-of-file metadata rejected");
    fresh_fixture();w64(fixture+3072+3*64+32,0);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"missing CRC section rejected");
    fresh_fixture();w64(fixture+384,0);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"zero CRC rejected");
    fresh_fixture();w64(fixture+384,UINT64_C(1)<<40);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"non-u32 CRC rejected");
    fresh_fixture();memset(fixture+392,'x',56);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"unterminated CRC symbol rejected");
    fresh_fixture();fixture[392]='z';CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"module_layout CRC missing rejected");
    fresh_fixture();memcpy(fixture+456,"module_layout",14);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"duplicate CRC symbol rejected");
    fresh_fixture();fixture[192+5]='z';CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"wrong modinfo module name rejected");
    fresh_fixture();fixture[512+24]='z';CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"wrong __this_module embedded name rejected");
    fresh_fixture();w64(fixture+3072+4*64+32,1592);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"wrong target-specific module structure size rejected");
    fresh_fixture();w32(fixture+3072+3*64,11);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"duplicate metadata sections rejected");
    fresh_fixture();w64(fixture+3072+3*64+56,32);CHECK(fan_validate_elf(fixture,sizeof fixture,&info,&why)<0,"CRC record stride mismatch rejected");
    fresh_fixture();CHECK(fan_validate_elf(fixture,63,&info,&why)<0,"truncated ELF header rejected");
    /* Deterministic mutation sweep, also run under UBSan by build.py.
     * ASan startup is not supported by this host; do not imply ASan coverage. */
    for(unsigned i=0;i<4096;i++){fresh_fixture();fixture[i]^=(unsigned char)(1U<<(i%8));(void)fan_validate_elf(fixture,sizeof fixture,&info,&why);}CHECK(1,"4096 deterministic ELF byte mutations completed");
}
static void manifest_tests(void) {
    char sha[65],versions[65];const char *why=NULL;struct fan_manifest m=fixture_manifest(sha,versions);struct fan_blob b=fixture_blob();struct stat target=b.st;target.st_ino=1;
    CHECK(fan_validate_manifest(&fan_release_manifest,&why)<0&&!strcmp(why,"replacement-not-configured"),"default manifest refuses missing real replacement");
    CHECK(!fan_validate_manifest(&m,&why),"fully pinned synthetic manifest passes structural checks");
    {
        struct fan_manifest observed=m;
        observed.kernel_release="6.12.69-android16-6-g242e1a07f878-ab15865446-4k";
        observed.vermagic="6.12.69-android16-6-o-gdea09c7a246a-4k SMP preempt mod_unload modversions aarch64";
        observed.versions_count=89;
        CHECK(!fan_validate_manifest(&observed,&why),"observed differing runtime uname and stock vermagic release accepted with same KMI and exact flags");
        CHECK(!fan_gate_values("1","true",0,0,830,1,1,0,1,"aarch64",observed.kernel_release,&observed),"observed runtime release remains exactly bound independently of vermagic");
        CHECK(fan_gate_values("1","true",0,0,830,1,1,0,1,"aarch64","6.12.69-android16-6-o-gdea09c7a246a-4k",&observed)!=NULL,"vermagic release cannot substitute for bound runtime uname");
        observed.vermagic="6.12.69-android16-7-o-other SMP preempt mod_unload modversions aarch64";
        CHECK(fan_validate_manifest(&observed,&why)<0,"different vermagic KMI still rejected");
        observed.vermagic="6.12.69-android16-6-o-other SMP preempt mod_unload modversions aarch64 extra";
        CHECK(fan_validate_manifest(&observed,&why)<0,"modified stock vermagic remaining flags rejected");
    }
    CHECK(!fan_validate_replacement(&b,&target,&m,&why),"matching SHA size ELF name vermagic CRC hash accepted");
    fixture[3000]^=1;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0&&!strcmp(why,"replacement-sha256"),"bad replacement SHA rejected");fixture[3000]^=1;
    m.filename="../oplus_fan.replacement";CHECK(fan_validate_manifest(&m,&why)<0,"relative path escape rejected");m.filename="/vendor_dlkm/lib/modules/oplus_fan.ko";CHECK(fan_validate_manifest(&m,&why)<0,"absolute replacement path rejected");m.filename=FAN_REPLACEMENT;
    m.size=FAN_MAX_SIZE+1U;CHECK(fan_validate_manifest(&m,&why)<0,"oversize manifest rejected");m.size=sizeof fixture;
    m.sha256=FAN_STOCK_SHA;CHECK(fan_validate_manifest(&m,&why)<0,"stock cannot masquerade as new replacement");m.sha256=sha;
    sha[0]='g';CHECK(fan_validate_manifest(&m,&why)<0,"malformed SHA hex rejected");fan_sha256_hex(fixture,sizeof fixture,sha);
    m.kmi="6.6-android15-8";CHECK(fan_validate_manifest(&m,&why)<0,"manifest KMI mismatch rejected");m.kmi="6.12-android16-6";
    m.vermagic="6.12.69-android16-6-offline SMP aarch64";CHECK(fan_validate_manifest(&m,&why)<0,"manifest missing modversions rejected");m.vermagic=vm;
    versions[0]=versions[0]=='0'?'1':'0';CHECK(fan_validate_replacement(&b,&target,&m,&why)<0&&!strcmp(why,"replacement-abi-identity"),"wrong CRC section digest rejected");fan_sha256_hex(fixture+384,128,versions);
    m.versions_count=3;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0,"wrong CRC record count rejected");m.versions_count=2;
    target=b.st;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0,"same source/target inode rejected");target.st_ino=1;
    b.st.st_nlink=2;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0,"replacement hardlinks rejected");b.st.st_nlink=1;
    b.st.st_mode=S_IFREG|0666;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0,"world/group writable replacement rejected");b.st.st_mode=S_IFREG|0644;
    b.st.st_uid=1000;CHECK(fan_validate_replacement(&b,&target,&m,&why)<0,"non-root owned replacement rejected");
}
static void gate_tests(void) {
    char sha[65],vs[65];struct fan_manifest m=fixture_manifest(sha,vs);const char *r;char kmi[96];
    CHECK(!fan_derive_kmi(m.kernel_release,kmi,sizeof kmi)&&!strcmp(kmi,m.kmi),"KMI extracted from exact supported release");
    CHECK(fan_derive_kmi("999999999999999999999.12.69-android16-6-offline",kmi,sizeof kmi)<0,"overflowing decimal release component refused");
    CHECK(fan_derive_kmi("6.12.4294967296-android16-6-offline",kmi,sizeof kmi)<0,"overflowing kernel patch refused without scanf conversion");
    CHECK(fan_derive_kmi("6.12.69-android16-6-",kmi,sizeof kmi)<0,"empty exact release suffix refused");
    CHECK(fan_derive_kmi(m.kernel_release,kmi,4)<0,"truncated KMI output refused");
#define G(pre,k,u,e,ms,ns,pr,ld,sy,ma,re) fan_gate_values(pre,k,u,e,ms,ns,pr,ld,sy,ma,re,&m)
    CHECK(!G("1","true",0,0,830,1,1,0,1,"aarch64",m.kernel_release),"preinit environment root namespace unloaded arch exact KMI/release gate accepted");
    CHECK(!G("1","true",0,0,1416,1,1,0,1,"aarch64",m.kernel_release),"actual reboot preinit 1.416 seconds accepted while driver still unloaded");
    CHECK(!G("1","true",0,0,5000,1,1,0,1,"aarch64",m.kernel_release),"slower synchronous preinit is not rejected by previous boot timing");
    CHECK(!G("1","true",0,0,10000,1,1,0,1,"aarch64",m.kernel_release),"stale-invocation ceiling boundary accepted");
    CHECK(G("1","true",0,0,10001,1,1,0,1,"aarch64",m.kernel_release)!=NULL,"one millisecond after stale-invocation ceiling refused");
    r=G("1","true",0,0,1416,1,1,1,1,"aarch64",m.kernel_release);CHECK(r&&!strcmp(r,"module-already-loaded"),"wider time ceiling never permits replacing an already loaded driver");
    r=G(NULL,"true",0,0,830,1,1,0,1,"aarch64",m.kernel_release);CHECK(r&&!strcmp(r,"not-ksu-preinit"),"ordinary Android/non-preinit environment refused");
    CHECK(G("1","1",0,0,830,1,1,0,1,"aarch64",m.kernel_release)!=NULL,"KSU=true exact value required");
    CHECK(G("1","true",1000,0,830,1,1,0,1,"aarch64",m.kernel_release)!=NULL,"real UID must also be root");
    CHECK(G("1","true",0,1000,830,1,1,0,1,"aarch64",m.kernel_release)!=NULL,"effective UID must be root");
    r=G("1","true",0,0,30000,1,1,0,1,"aarch64",m.kernel_release);CHECK(r&&!strcmp(r,"preinit-boot-deadline"),"late --apply refused even with spoofed KSU environment");
    r=G("1","true",0,0,830,0,1,0,1,"aarch64",m.kernel_release);CHECK(r&&!strcmp(r,"mount-namespace-not-init"),"private mount namespace refused");
    CHECK(G("1","true",0,0,830,1,0,0,1,"aarch64",m.kernel_release)!=NULL,"missing proc modules inventory refused");
    CHECK(G("1","true",0,0,830,1,1,0,0,"aarch64",m.kernel_release)!=NULL,"missing sys module state refused");
    r=G("1","true",0,0,830,1,1,1,1,"aarch64",m.kernel_release);CHECK(r&&!strcmp(r,"module-already-loaded"),"already loaded module refused");
    CHECK(G("1","true",0,0,830,1,1,0,1,"arm64",m.kernel_release)!=NULL,"uname architecture alias not silently accepted");
    CHECK(G("1","true",0,0,830,1,1,0,1,"aarch64","6.12.69-android16-7-other")!=NULL,"uname KMI mismatch refused");
    CHECK(G("1","true",0,0,830,1,1,0,1,"aarch64","6.12.69-android16-6-other")!=NULL,"same KMI but different exact kernel release refused");
#undef G
}
struct fake { unsigned gates,binds,captures,remounts,verifies,unmounts,restores;int reject_gate,fail_recheck,fail_bind,fail_capture,fail_remount,fail_verify,not_owned,fail_unmount,fail_restore,active;char sequence[64]; };
static void event(struct fake *f,char c){size_t n=strlen(f->sequence);if(n+1>=sizeof f->sequence)abort();f->sequence[n]=c;f->sequence[n+1]=0;}
static const char *fg(void *c){struct fake *f=c;event(f,'G');f->gates++;return f->reject_gate==(int)f->gates?"module-already-loaded":NULL;}
static const char *fr(void *c){struct fake *f=c;event(f,'R');return f->fail_recheck?"stock-recheck-failed":(f->active?"target-already-mounted-or-unknown":NULL);}
static int fb(void *c){struct fake *f=c;event(f,'B');f->binds++;errno=EIO;if(f->fail_bind)return -1;f->active=1;return 0;}
static int fc(void *c){struct fake *f=c;event(f,'C');f->captures++;errno=EIO;return f->fail_capture?-1:0;}
static int fm(void *c){struct fake *f=c;event(f,'M');f->remounts++;errno=EROFS;return f->fail_remount?-1:0;}
static int fv(void *c){struct fake *f=c;event(f,'V');f->verifies++;errno=ESTALE;return f->fail_verify?-1:0;}
static int fo(void *c){struct fake *f=c;event(f,'O');return !f->not_owned;}
static int fu(void *c){struct fake *f=c;event(f,'U');f->unmounts++;errno=EBUSY;if(f->fail_unmount)return -1;f->active=0;return 0;}
static int fs(void *c){struct fake *f=c;event(f,'S');f->restores++;return !f->fail_restore;}
static void run_case(struct fake f,int code,const char *state,const char *seq,const char *label) {
    struct fan_ops ops={&f,fg,fr,fb,fc,fm,fv,fo,fu,fs};struct fan_result r=fan_transaction(&ops);
    CHECK(r.code==code&&!strcmp(r.state,state)&&!strcmp(f.sequence,seq),label);
    const char *reason=NULL;int expected_errno=0;
    switch(code) {
        case 10:reason="replacement-bound-readonly";break;
        case 22:reason="module-already-loaded";break;
        case 26:reason=f.fail_recheck?"stock-recheck-failed":"module-already-loaded";break;
        case 27:reason="bind-failed";expected_errno=EIO;break;
        case 28:reason=f.fail_capture?"capture-failed-rolled-back":(f.fail_remount?"remount-failed-rolled-back":"verification-failed-rolled-back");expected_errno=f.fail_capture?EIO:(f.fail_remount?EROFS:ESTALE);break;
        case 29:reason=f.not_owned?"rollback-ownership-unproven":(f.fail_unmount?"rollback-unmount-failed":"rollback-stock-unverified");expected_errno=f.not_owned?(f.fail_capture?EIO:ESTALE):(f.fail_unmount?EBUSY:0);break;
        default:abort();
    }
    CHECK(!strcmp(r.reason,reason)&&r.error_number==expected_errno,"exact outcome reason and non-stale errno match stdout contract");
    CHECK(f.binds<=1&&f.remounts<=1&&f.unmounts<=1,"never a second bind/remount/unmount attempt");
    printf("outcome stdout=\"fan-preinit result=%s exit=%d reason=%s errno=%d\" sequence=%s\n",r.state,fan_cli_exit_code(r.code),r.reason,r.error_number,f.sequence);
}
static void transaction_tests(void) {
    CHECK(fan_cli_exit_code(10)==0,"applied internal outcome 10 maps to KSU process success zero");
    CHECK(fan_cli_exit_code(0)==0,"check-only success remains process zero");
    for(int code=20;code<=29;code++)CHECK(fan_cli_exit_code(code)==code,"refusal rollback and uncertain outcomes remain nonzero failures");
    run_case((struct fake){0},10,"applied","GRGBCMV","successful transaction binds once remounts once verifies readonly");
    run_case((struct fake){.reject_gate=1},22,"stock-retained","G","initial loaded/nonpreinit refusal never mounts");
    run_case((struct fake){.fail_recheck=1},26,"stock-retained","GR","read-only stock/source recheck refusal never mounts");
    run_case((struct fake){.reject_gate=2},26,"stock-retained","GRG","loaded race at final gate never mounts");
    run_case((struct fake){.fail_bind=1},27,"stock-retained","GRGB","bind syscall failure never remounts or unmounts");
    run_case((struct fake){.fail_capture=1},28,"stock-retained","GRGBCOUS","capture check failure rolls back owned new mount only");
    run_case((struct fake){.fail_capture=1,.not_owned=1},29,"uncertain-mount","GRGBCO","unproven mount ownership does not unmount someone else's mount");
    run_case((struct fake){.fail_remount=1},28,"stock-retained","GRGBCMOUS","readonly remount failure unmounts own mount and verifies stock");
    run_case((struct fake){.fail_verify=1},28,"stock-retained","GRGBCMVOUS","readonly/hash readback failure unmounts own mount and verifies stock");
    run_case((struct fake){.fail_verify=1,.fail_unmount=1},29,"uncertain-mount","GRGBCMVOU","rollback unmount failure never claims stock restoration");
    run_case((struct fake){.fail_verify=1,.fail_restore=1},29,"uncertain-mount","GRGBCMVOUS","rollback stock recheck failure reports uncertainty");
    run_case((struct fake){.fail_verify=1,.not_owned=1},29,"uncertain-mount","GRGBCMVO","mount ownership loss never removes unrelated mount");
    struct fake f={0};struct fan_ops ops={&f,fg,fr,fb,fc,fm,fv,fo,fu,fs};
    struct fan_result first=fan_transaction(&ops),second=fan_transaction(&ops);
    CHECK(first.code==10&&second.code==26&&f.binds==1&&f.remounts==1&&f.unmounts==0,"already-mounted reentry never performs a second bind or remount");
}
static void filesystem_tests(void) {
    const char *out=getenv("FAN_TEST_OUT");CHECK(out&&out[0]=='/',"test temporary files confined to explicit build output directory");
    char dir[PATH_MAX];CHECK(snprintf(dir,sizeof dir,"%s/fs-XXXXXX",out)>0&&mkdtemp(dir)!=NULL,"create offline temporary filesystem fixture");
    int d=open(dir,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0,"open temporary directory");
    int fd=openat(d,FAN_REPLACEMENT,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0644);CHECK(fd>=0,"create non-driver file fixture");
    unsigned char zero[128]={0};CHECK(write(fd,zero,sizeof zero)==(ssize_t)sizeof zero,"write bounded synthetic file data");
    int p=fan_open_payload(d,FAN_REPLACEMENT);CHECK(p>=0,"safe single regular replacement leaf accepted");
    struct fan_blob b;CHECK(!fan_read_blob(p,&b)&&b.size==128,"bounded pread and fstat snapshots read fixture");fan_free_blob(&b);close(p);
    CHECK(fan_open_payload(d,"../escape")<0,"payload path escape refused before open");
    CHECK(fan_open_payload(d,"/etc/passwd")<0,"payload absolute path refused before open");
    CHECK(!linkat(d,FAN_REPLACEMENT,d,"second-link",0),"create offline hardlink fixture");CHECK(fan_open_payload(d,FAN_REPLACEMENT)<0,"hard-linked payload refused by actual fstat");unlinkat(d,"second-link",0);
    CHECK(!fchmod(fd,0666),"set temporary file insecure permission");CHECK(fan_open_payload(d,FAN_REPLACEMENT)<0,"insecure file mode refused by actual fstat");(void)fchmod(fd,0644);
    CHECK(!ftruncate(fd,(off_t)FAN_MAX_SIZE+1),"create sparse oversize fixture");CHECK(fan_open_payload(d,FAN_REPLACEMENT)<0,"oversize file refused before allocation");(void)ftruncate(fd,128);close(fd);
    CHECK(!renameat(d,FAN_REPLACEMENT,d,"plain.fixture"),"rename temporary fixture");CHECK(!symlinkat("plain.fixture",d,FAN_REPLACEMENT),"create temporary leaf symlink");CHECK(fan_open_payload(d,FAN_REPLACEMENT)<0,"replacement symlink refused by O_NOFOLLOW");unlinkat(d,FAN_REPLACEMENT,0);
    CHECK(!mkfifoat(d,FAN_REPLACEMENT,0600),"create temporary FIFO fixture");CHECK(fan_open_payload(d,FAN_REPLACEMENT)<0,"nonregular FIFO refused without blocking");unlinkat(d,FAN_REPLACEMENT,0);
    char absolute[PATH_MAX];int n=snprintf(absolute,sizeof absolute,"%s/plain.fixture",dir);CHECK(n>0&&(size_t)n<sizeof absolute,"fixture absolute path fits");p=fan_open_absolute(absolute,O_RDONLY);CHECK(p>=0,"safe absolute path traversed with held directory FDs");if(p>=0)close(p);
    n=snprintf(absolute,sizeof absolute,"%s/../plain.fixture",dir);CHECK(n>0&&(size_t)n<sizeof absolute&&fan_open_absolute(absolute,O_RDONLY)<0,"dotdot rejected by actual absolute opener");
    n=snprintf(absolute,sizeof absolute,"%s/alias",dir);CHECK(n>0&&(size_t)n<sizeof absolute&&!symlink(".",absolute),"create parent symlink fixture");
    n=snprintf(absolute,sizeof absolute,"%s/alias/plain.fixture",dir);CHECK(n>0&&(size_t)n<sizeof absolute&&fan_open_absolute(absolute,O_RDONLY)<0,"symlink parent refused by actual absolute opener");
    unlinkat(d,"alias",0);unlinkat(d,"plain.fixture",0);close(d);CHECK(!rmdir(dir),"remove only offline temporary fixtures");
}
static void mountinfo_tests(void) {
    int ro=-1;
    CHECK(fan_readonly_ok(1,ST_RDONLY),"readonly recheck requires mountinfo and fstatvfs agreement");
    CHECK(!fan_readonly_ok(0,ST_RDONLY),"readonly recheck rejects mountinfo rw despite fstatvfs ro");
    CHECK(!fan_readonly_ok(1,0),"readonly recheck rejects fstatvfs rw despite mountinfo ro");
    CHECK(!fan_readonly_ok(-1,ST_RDONLY),"readonly recheck rejects unknown mountinfo state");
    CHECK(fan_mount_record("234 20 8:1 /lib/a /vendor_dlkm/lib/modules/oplus_fan.ko ro,nosuid - ext4 /dev/x ro\n",234,FAN_TARGET,&ro)==1&&ro,"mountinfo exact owned readonly ID recognized");
    CHECK(fan_mount_record("234 20 8:1 /lib/a /vendor_dlkm/lib/modules/oplus_fan.ko rw - ext4 /dev/x ro\n",234,FAN_TARGET,&ro)==1&&!ro,"per-mount rw not confused with superblock ro");
    CHECK(fan_mount_record("235 20 8:1 /lib/a /vendor_dlkm/lib/modules/oplus_fan.ko ro - ext4 /dev/x ro\n",234,FAN_TARGET,&ro)==-2,"different top mount ID rejected");
    CHECK(fan_mount_record("234 20 8:1 /lib/a /vendor/lib/modules/oplus_fan.ko ro - ext4 /dev/x ro\n",234,FAN_TARGET,&ro)==0,"vendor alias not accepted as canonical target");
    CHECK(fan_mount_record("broken",234,FAN_TARGET,&ro)<0,"unparseable mountinfo fails closed");
    CHECK(fan_mount_record("234 20 8:1 /lib/a /vendor_dlkm/lib/modules/oplus_fan.ko ro,rw - ext4 /dev/x ro\n",234,FAN_TARGET,&ro)<0,"contradictory mount options rejected");
}
static void label_tests(void) {
    CHECK(fan_vendor_context_matches(FAN_VENDOR_CONTEXT,sizeof(FAN_VENDOR_CONTEXT)),"exact stock vendor context with terminating NUL accepted");
    CHECK(fan_vendor_context_matches(FAN_VENDOR_CONTEXT,sizeof(FAN_VENDOR_CONTEXT)-1),"exact stock vendor context without terminating NUL accepted");
    CHECK(!fan_vendor_context_matches(NULL,0),"missing context rejected");
    CHECK(!fan_vendor_context_matches("",0),"empty context rejected");
    CHECK(!fan_vendor_context_matches(FAN_VENDOR_CONTEXT,sizeof(FAN_VENDOR_CONTEXT)-2),"truncated vendor context rejected");
    CHECK(!fan_vendor_context_matches("u:object_r:vold_metadata_file:s0",sizeof("u:object_r:vold_metadata_file:s0")),"metadata context that vendor loader cannot access rejected");
    CHECK(!fan_vendor_context_matches("u:object_r:vendor_file:s0x",sizeof("u:object_r:vendor_file:s0x")-1),"nonterminated vendor context suffix rejected");
    CHECK(!fan_vendor_context_matches("u:object_r:vendor_file:s0:c1",sizeof("u:object_r:vendor_file:s0:c1")),"unexpected security category rejected");
    CHECK(!fan_vendor_context_matches("u:object_r:system_file:s0",sizeof("u:object_r:system_file:s0")),"different system type rejected");
    CHECK(!fan_vendor_label_ok(-1),"invalid descriptor never satisfies vendor-label check");
}
int main(void) {
    sha_tests();elf_tests();manifest_tests();gate_tests();transaction_tests();filesystem_tests();mountinfo_tests();label_tests();
    printf("PASS %u assertions; all mount operations were injected, no device paths mutated\n",checks);return 0;
}
