/* Offline release-input reader. Never compiled into the preinit executable. */
#define FAN_TEST_LIBRARY
#include "fan_preinit.c"
static void quoted(const char *s) {
    putchar('"');for(;*s;s++){if(*s=='"'||*s=='\\')putchar('\\');putchar((unsigned char)*s);}putchar('"');
}
int main(int argc,char **argv) {
    if(argc!=3 || (strcmp(argv[1],"--fd") && strcmp(argv[1],"--stock-fd"))){puts("probe requires an inherited read-only FD");return 2;}
    int stock=!strcmp(argv[1],"--stock-fd");
    char *end=NULL;errno=0;long n=strtol(argv[2],&end,10);
    if(errno || !end || *end || n<3 || n>INT_MAX)return 2;
    if(stock && fan_validate_stock_fd((int)n,NULL)){perror("offline-stock");return 5;}
    struct fan_blob b;struct fan_elf_info info;const char *why=NULL;
    if(fan_read_blob((int)n,&b)){perror("offline-read");return 3;}
    if(fan_validate_elf(b.data,b.size,&info,&why)){fprintf(stderr,"offline-elf: %s\n",why);fan_free_blob(&b);return 4;}
    char sha[65];fan_sha256_hex(b.data,b.size,sha);
    printf("{\"size\":%zu,\"sha256\":\"%s\",\"versions_sha256\":\"%s\",\"versions_count\":%u,\"module_name\":\""FAN_MODULE"\",\"vermagic\":",b.size,sha,info.versions_sha256,info.versions_count);
    quoted(info.vermagic);puts("}");fan_free_blob(&b);
    /* Force a compile-time reference to the intentionally disabled header in
     * this offline translation unit; it is never used as probe authority. */
    return fan_release_manifest.present?5:0;
}
