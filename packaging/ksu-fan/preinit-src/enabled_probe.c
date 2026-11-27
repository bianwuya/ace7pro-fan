/* Offline only: validate enabled manifest and actual payload through supplied
 * read-only FDs. Never included in the installed helper; no mount callbacks. */
#define FAN_TEST_LIBRARY
#include "fan_preinit.c"
static int parse_fd(const char *s) {
    char *end=NULL;errno=0;long n=strtol(s,&end,10);
    return errno||!end||*end||n<3||n>INT_MAX?-1:(int)n;
}
int main(int argc,char **argv) {
    if(argc!=3)return 2;
    int stock=parse_fd(argv[1]),payload=parse_fd(argv[2]);
    if(stock<0||payload<0)return 2;
    const char *why=NULL;struct fan_blob b;struct stat original;
    if(fstat(stock,&original)||fan_validate_stock_fd(stock,NULL)){perror("offline-stock");return 3;}
    if(fan_validate_manifest(&fan_release_manifest,&why)){fprintf(stderr,"offline-manifest: %s\n",why);return 4;}
    if(fan_read_blob(payload,&b)){perror("offline-payload-read");return 5;}
    int bad=fan_validate_replacement(&b,&original,&fan_release_manifest,&why);fan_free_blob(&b);
    if(bad){fprintf(stderr,"offline-payload-identity: %s\n",why);return 6;}
    why=fan_gate_values("1","true",0,0,830,1,1,0,1,"aarch64",fan_release_manifest.kernel_release,&fan_release_manifest);
    if(why){fprintf(stderr,"offline-injected-gate: %s\n",why);return 7;}
    if(sizeof(struct fan_manifest)!=72||fan_cli_exit_code(10)!=0||fan_cli_exit_code(28)!=28||fan_cli_exit_code(29)!=29)return 8;
    printf("offline-enabled-validation=passed payload_size=%"PRIu64" versions_count=%u manifest_abi=%zu candidatecontroltested=false actual_mounts=0\n",fan_release_manifest.size,fan_release_manifest.versions_count,sizeof(struct fan_manifest));
    return 0;
}
