#ifndef FAN_PREINIT_H
#define FAN_PREINIT_H
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#define FAN_TARGET "/vendor_dlkm/lib/modules/oplus_fan.ko"
#define FAN_REPLACEMENT "oplus_fan.replacement"
#define FAN_STOCK_SHA "a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd"
#define FAN_STOCK_SIZE UINT64_C(104888)
#define FAN_MAX_SIZE (8U * 1024U * 1024U)
#define FAN_MODULE "oplus_fan"
#define FAN_VENDOR_CONTEXT "u:object_r:vendor_file:s0"
#define FAN_STOCK_VERMAGIC_FLAGS " SMP preempt mod_unload modversions aarch64"
/* KSU runs this synchronously before second-stage init/module services.
 * Observed boot traces vary: preinit 0.826676 s versus 1.416411 s; a fixed
 * 1.2 s cutoff incorrectly rejected the latter before vendor.modprobe 2.325 s.
 * Keep a 10 s stale-invocation ceiling, NOT an inferred module-load deadline.
 * Exact KSU environment, PID1 mount namespace and both unloaded inventories
 * remain mandatory and are rechecked immediately before the only bind.
 * This is not a race lock against an unrelated concurrent module loader. */
#define FAN_DEADLINE_MS UINT64_C(10000)
struct fan_manifest {
    unsigned present;
    const char *filename;
    const char *sha256;
    uint64_t size;
    const char *kernel_release;
    const char *kmi;
    const char *vermagic;
    const char *versions_sha256;
    unsigned versions_count;
};
#if defined(__aarch64__)
_Static_assert(sizeof(struct fan_manifest)==72, "builder requires immutable 72-byte manifest ABI");
#endif
struct fan_elf_info { char vermagic[256]; char versions_sha256[65]; unsigned versions_count; };
struct fan_blob { unsigned char *data; size_t size; struct stat st; };
struct fan_result { int code; const char *state; const char *reason; int error_number; };
/* The transaction layer is syscall-injected for OFFLINE tests. Production
 * constructs these callbacks internally; no runtime test/path overrides. */
struct fan_ops {
    void *ctx;
    const char *(*guard)(void *);
    const char *(*recheck)(void *);
    int (*bind)(void *);
    int (*capture)(void *);
    int (*remount_ro)(void *);
    int (*verify)(void *);
    int (*owned)(void *);
    int (*unmount)(void *);
    int (*stock_restored)(void *);
};
struct fan_result fan_transaction(const struct fan_ops *ops);
int fan_validate_elf(const unsigned char *,size_t,struct fan_elf_info *,const char **);
int fan_validate_manifest(const struct fan_manifest *,const char **);
int fan_validate_replacement(const struct fan_blob *,const struct stat *,const struct fan_manifest *,const char **);
int fan_open_absolute(const char *,int);
int fan_open_payload(int,const char *);
int fan_read_blob(int,struct fan_blob *);
int fan_validate_stock_fd(int,const struct stat *);
int fan_vendor_context_matches(const char *,size_t);
int fan_vendor_label_ok(int);
int fan_prepare_replacement_fd(int,const struct stat *,const struct fan_manifest *,struct stat *,const char **);
void fan_free_blob(struct fan_blob *);
int fan_derive_kmi(const char *,char *,size_t);
const char *fan_gate_values(const char *,const char *,unsigned,unsigned,uint64_t,int,int,int,int,const char *,const char *,const struct fan_manifest *);
int fan_mount_record(const char *,uint64_t,const char *,int *);
int fan_readonly_ok(int,unsigned long);
int fan_cli_exit_code(int);
#endif
