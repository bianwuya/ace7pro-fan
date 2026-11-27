/* OFFLINE TYPE PROBE ONLY. Never link into the driver or install this object.
 *
 * Incomplete tagged types intentionally avoid inventing any kernel layouts.
 * Compiler builtin typedefs state only the AArch64 LP64 callback scalar ABI.
 * Equal emitted KCFI prefixes provide evidence about these exact prototypes,
 * NOT about struct layout, kernel config, lifecycle, or complete KMI safety.
 */
#ifndef ETA_FAN_OFFLINE_ABI_PROBE
#error "Type-probe fixture only; not production adapter code"
#endif

typedef __PTRDIFF_TYPE__ eta_probe_ssize_t;
typedef __SIZE_TYPE__ eta_probe_size_t;
struct platform_device;
struct device;
struct device_attribute;

_Static_assert(sizeof(void *) == 8, "AArch64 LP64 pointer required");
_Static_assert(sizeof(eta_probe_ssize_t) == 8, "LP64 ssize_t scalar required");
_Static_assert(sizeof(eta_probe_size_t) == 8, "LP64 size_t scalar required");

__attribute__((noinline, used))
int eta_abi_probe(struct platform_device *pdev)
{
    (void)pdev;
    return 0;
}

__attribute__((noinline, used))
eta_probe_ssize_t eta_abi_level_store(struct device *dev,
    struct device_attribute *attr, const char *buf, eta_probe_size_t count)
{
    (void)dev;
    (void)attr;
    (void)buf;
    (void)count;
    return 0;
}

__attribute__((noinline, used))
eta_probe_ssize_t eta_abi_state_store(struct device *dev,
    struct device_attribute *attr, const char *buf, eta_probe_size_t count)
{
    (void)dev;
    (void)attr;
    (void)buf;
    (void)count;
    return 0;
}
