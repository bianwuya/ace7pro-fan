#ifndef ETA_FAN_ADAPTER_CONFIG_PROTOCOL_H
#define ETA_FAN_ADAPTER_CONFIG_PROTOCOL_H
#include "../policy_core.h"
#define ETA_ADAPTER_PROTOCOL_VERSION 2U
enum eta_adapter_strategy {
    ETA_STRATEGY_PASS_THROUGH = 0,
    ETA_STRATEGY_ADDITIVE = 1,
    ETA_STRATEGY_FULL_OWNER = 2
};
#define ETA_ADAPTER_NAME_BYTES 64U
#define ETA_ADAPTER_GATE_MASK 127U
#define ETA_ADAPTER_LEASE_MAX_MS 5000U
struct eta_adapter_config {
    eta_u64 revision;
    eta_u32 protocol_version,strategy;
    eta_u32 enabled,lease_ms,main_enabled,mode,auto_enabled,gate_mask;
    eta_u32 thermal_status_valid;
    eta_s32 thermal_status;
    struct eta_fan_config policy;
    char zone[ETA_FAN_CHANNELS][ETA_ADAPTER_NAME_BYTES];
};
/* v1: 26 integers + four distinct sensor names; strategy remains ADDITIVE.
 * v2: same 26 integers + strategy (0/1/2) + four names. Cold default is PASS.
 * Parser never mutates *out on failure; count includes optional final newline.
 * No negative/overflow values, extra tokens, or shell/skin sources.
 */
int eta_adapter_parse_config(const char *line,eta_u32 count,struct eta_adapter_config *out);
int eta_adapter_permissions(const struct eta_adapter_config *c,eta_u64 now,eta_u64 deadline);
#endif
