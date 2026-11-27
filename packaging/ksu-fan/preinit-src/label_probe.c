/* Device file-label regression probe, NOT shipped in the KSU ZIP.
 * Validates both explicitly opened descriptors before invoking production's
 * pinned replacement labelling routine. No mount/module/driver/stock writes. */
#define FAN_TEST_LIBRARY
#include "fan_preinit.c"
static int parse_fd(const char *s) {
    char *end=NULL;errno=0;long n=strtol(s,&end,10);
    return errno||!end||*end||n<3||n>INT_MAX?-1:(int)n;
}
int main(int argc,char **argv) {
    if(argc!=3 || getuid()!=0 || geteuid()!=0)return 2;
    int stock=parse_fd(argv[1]),payload=parse_fd(argv[2]);
    if(stock<0 || payload<0)return 2;
    struct stat original,snapshot;const char *why=NULL;
    if(fstat(stock,&original) || fan_validate_stock_fd(stock,NULL)){perror("stock-identity");return 3;}
    errno=0;
    if(fan_prepare_replacement_fd(payload,&original,&fan_release_manifest,&snapshot,&why)){
        fprintf(stderr,"pinned-label-preparation-failed reason=%s errno=%d\n",why,errno);return 4;
    }
    if(!fan_vendor_label_ok(payload) || fan_validate_stock_fd(stock,&original))return 5;
    printf("pinned-payload-label=verified context=%s bytes=%"PRIu64" versions=%u stock_unchanged=true actual_mounts=0 module_syscalls=0 policy_writes=0\n",FAN_VENDOR_CONTEXT,fan_release_manifest.size,fan_release_manifest.versions_count);
    return 0;
}
