#ifndef ETA_FAN_POLICY_CORE_H
#define ETA_FAN_POLICY_CORE_H

/*
 * Pure integer demand policy + a SEPARATE normal-profile request slew limiter.
 * No driver, scheduler, transport, ownership arbitration or stable userspace ABI.
 * Source references: DailyFanPolicy.java, DailyFanConfig.java, ChargeCurve.java,
 * OemFanRamp.java and build/direct-node-audit/REPORT.md section 3.
 * This is NOT the OEM shell-temperature / KP / power / scene temperature model.
 *
 * Caller serializes each state object. Temperatures are signed milli-Celsius;
 * time is unsigned monotonic milliseconds, NOT wall time. All availability and
 * permissions must be supplied explicitly; a zero-initialized input is DENIED.
 * Never substitute 25 C, zero C, an old epoch, or a derated/effective fan level.
 */
#ifdef __KERNEL__
#include <linux/types.h>
typedef __s32 eta_s32;
typedef __u32 eta_u32;
typedef __u64 eta_u64;
#else
#include <stdint.h>
typedef int32_t eta_s32;
typedef uint32_t eta_u32;
typedef uint64_t eta_u64;
#endif

#define ETA_FAN_CHANNELS 4U
#define ETA_FAN_ALL_CHANNELS 15U
#define ETA_FAN_WEIGHT_UNIT 1000000U
/* One Java setting unit = 1,000,000 integer units; 0..100 units per channel.
 * Ratios need not sum to one million. E.g. 600000:0:400000:0 and
 * 60000000:0:40000000:0 are identical. Quantization is a caller contract:
 * convert validated decimal settings once, reject overflow/non-finite values,
 * and bump config.version. No decimal parser or implicit defaults live here.
 */
#define ETA_FAN_WEIGHT_MAX 100000000U
#define ETA_FAN_FRESH_MS 15000U
#define ETA_FAN_COOL_MS 30000U
#define ETA_FAN_NORMAL_MIN 6U
#define ETA_FAN_MAX_LEVEL 20U
#define ETA_FAN_GAIN_UNIT 1000U
#define ETA_FAN_GAIN_MAX 8000U

/* Canonical order is DailyFanConfig's order, not legacy FusionTemp.CHANNELS.
 * Adapters MUST explicitly map their telemetry/charging settings into it.
 */
enum eta_fan_channel {
    ETA_FAN_BATTERY = 0,
    ETA_FAN_BOARD = 1,
    ETA_FAN_SURROUND = 2,
    ETA_FAN_CHARGE_MOS = 3
};

enum eta_fan_flag {
    ETA_FAN_UNKNOWN = 0,
    ETA_FAN_NO = 1,
    ETA_FAN_YES = 2
};

enum eta_fan_power {
    ETA_POWER_UNKNOWN = 0,
    ETA_POWER_BATTERY = 1,
    ETA_POWER_CHARGING = 2,
    ETA_POWER_EXTERNAL_IDLE = 3
};

enum eta_fan_mode {
    ETA_MODE_UNKNOWN = 0,
    ETA_MODE_AUTO = 1,
    ETA_MODE_MANUAL = 2,
    ETA_MODE_OTHER = 3
};

enum eta_fan_policy_kind {
    ETA_POLICY_NONE = 0,
    ETA_POLICY_DAILY = 1,
    ETA_POLICY_CHARGE = 2
};

enum eta_fan_reason {
    ETA_REASON_READY = 0,
    ETA_REASON_COOLING,
    ETA_REASON_BELOW_THRESHOLD,
    ETA_REASON_ARGUMENT_INVALID,
    ETA_REASON_POWER_UNKNOWN,
    ETA_REASON_EXTERNAL_IDLE,
    ETA_REASON_POWER_SAMPLE_MISMATCH,
    ETA_REASON_CHANNEL_SAMPLE_MISMATCH,
    ETA_REASON_SWITCH_UNKNOWN,
    ETA_REASON_SWITCH_OFF,
    ETA_REASON_MODE_UNKNOWN,
    ETA_REASON_MODE_BLOCKED,
    ETA_REASON_AUTO_UNKNOWN,
    ETA_REASON_AUTO_OFF,
    ETA_REASON_GATE_UNKNOWN,
    ETA_REASON_GATE_BLOCKED,
    ETA_REASON_CONFIG_UNAVAILABLE,
    ETA_REASON_CONFIG_INVALID,
    ETA_REASON_SETTING_UNKNOWN,
    ETA_REASON_SETTING_DISABLED,
    ETA_REASON_THERMAL_UNKNOWN,
    ETA_REASON_THERMAL_BLOCKED,
    ETA_REASON_SAMPLE_FUTURE,
    ETA_REASON_SAMPLE_STALE,
    ETA_REASON_SAMPLE_INVALID,
    ETA_REASON_TEMPERATURE_MISSING,
    ETA_REASON_TEMPERATURE_INVALID,
    ETA_REASON_TEMPERATURE_GUARD,
    ETA_REASON_WEIGHT_INVALID,
    ETA_REASON_NO_CONTRIBUTORS,
    ETA_REASON_CONTEXT_CHANGED,
    ETA_REASON_TIME_REVERSED,
    ETA_REASON_SAMPLE_GAP
};

#define ETA_GATE_UI_CLEAR (1U << 0)
#define ETA_GATE_PROTECTION_CLEAR (1U << 1)
#define ETA_GATE_ABORT_CLEAR (1U << 2)
#define ETA_GATE_NOISE_CLEAR (1U << 3)
#define ETA_GATE_HANDLE_CLEAR (1U << 4)
#define ETA_GATE_HARDWARE_HEALTHY (1U << 5)
#define ETA_GATE_COMMON_SCENE (1U << 6)

#define ETA_RESET_POWER (1U << 0)
#define ETA_RESET_CONFIG (1U << 1)
#define ETA_RESET_CHANNELS (1U << 2)

struct eta_fan_settings {
    enum eta_fan_flag enabled;
    eta_s32 start_mc;             /* 20000..50000 inclusive */
    eta_u32 floor;                /* 1..20; daily minimum over normal steps */
    eta_u32 weight[ETA_FAN_CHANNELS];
    eta_u32 gain_milli;           /* charge only: 1..8000 = .001..8 levels/C */
    eta_u32 ceiling;              /* charge only: floor..20 */
};

struct eta_fan_config {
    eta_u64 version;              /* change on any parameter revision */
    struct eta_fan_settings daily;
    struct eta_fan_settings charge;
};

struct eta_fan_sample {
    eta_u64 at_ms;
    enum eta_fan_power power;     /* power at sampling, not a cached guess */
    eta_u64 power_epoch;          /* each power edge/CHANGED report, even A->B->A */
    eta_u64 channel_generation;   /* increment on mapping/source changes */
    eta_s32 temperature_mc[ETA_FAN_CHANNELS];
    eta_u32 valid_mask;           /* explicit validity, including zero weights */
};

struct eta_fan_policy_input {
    eta_u64 now_ms;
    enum eta_fan_power power;
    eta_u64 power_epoch;
    eta_u64 channel_generation;
    struct eta_fan_sample sample;
    enum eta_fan_flag main_enabled;
    enum eta_fan_mode mode;
    enum eta_fan_flag auto_enabled;
    enum eta_fan_flag ui_clear;
    enum eta_fan_flag protection_clear;
    enum eta_fan_flag abort_clear;
    enum eta_fan_flag noise_clear;
    enum eta_fan_flag handle_clear;
    enum eta_fan_flag hardware_healthy;
    enum eta_fan_flag common_scene;
    enum eta_fan_flag thermal_status_valid;
    eta_s32 thermal_status;       /* only known status 0 allows takeover */
};

struct eta_fan_fusion {
    eta_u32 valid;
    eta_u32 contributor_mask;
    eta_s32 temperature_mc;       /* diagnostic truncation, NOT threshold input */
    eta_u64 weighted_mc;          /* exact numerator, nonnegative valid band */
    eta_u64 total_weight;         /* exact denominator; no contributors => 0 */
};

/* Standalone fusion renormalizes over valid weighted contributors. Policy use
 * STILL requires all four raw sensors valid and below 45/55/55/55 C, even when
 * weight=0. All comparisons and charge rounding use the exact rational mean,
 * so sub-milli-Celsius residuals cannot change strict threshold equalities.
 * Max sum = 4*65000*100000000 = 26000000000000, safely within 64 bits.
 */
enum eta_fan_reason eta_fan_fuse(const eta_u32 weight[ETA_FAN_CHANNELS],
    const struct eta_fan_sample *sample, struct eta_fan_fusion *fusion);

struct eta_fan_policy_state {
    eta_u32 context_valid;
    enum eta_fan_power power;
    eta_u64 power_epoch;
    eta_u64 channel_generation;
    eta_u64 config_version;
    struct eta_fan_settings settings;
    eta_u32 contributor_mask;
    eta_u32 active;
    eta_u32 daily_step;           /* private latch: 0=off, 1..6 normal steps */
    eta_u32 cooling;
    eta_u64 cooling_since_ms;
    eta_u64 last_sample_ms;
    eta_u64 last_now_ms;
};

struct eta_fan_policy_output {
    enum eta_fan_policy_kind policy;
    enum eta_fan_reason reason;
    struct eta_fan_fusion fusion;
    eta_u32 has_target;
    eta_u32 target;               /* UPSTREAM DEMAND, not final physical level */
    eta_u32 active;
    eta_u32 cooling;
    eta_u32 hard_exit;            /* immediately release; NEVER slew this exit */
    eta_u32 gate_mask;            /* denied/unknown permission fields */
    eta_u32 reset_flags;
};

void eta_fan_policy_reset(struct eta_fan_policy_state *state);
/* On a context change return one explicit hard-exit frame before rearming on
 * the next fresh input; daily/charge latches never transfer. Value comparisons
 * also detect settings changes without a version bump. Stale (age >=15 s),
 * >=15 s sample/call gaps and backwards clocks release and clear continuity.
 * Daily: start > start_mc; stop <= start_mc-1000 for 30 s; active demand is
 * max(floor, ordinary/default hysteretic step of the exact fused temperature).
 * The step resets on stop or any hard exit; it never changes charge demand.
 * Charge: start >= start_mc; stop < start_mc-1000 for 30 s.
 * Charge target=0 means NO ADDITIONAL DEMAND (as in ChargeCurve), not permission
 * to stop OEM demand. Ownership/arbitration belongs to the future adapter.
 */
void eta_fan_policy_step(struct eta_fan_policy_state *state,
    const struct eta_fan_config *config, const struct eta_fan_policy_input *input,
    struct eta_fan_policy_output *output);
const char *eta_fan_reason_name(enum eta_fan_reason reason);

enum eta_fan_ramp_reason {
    ETA_RAMP_STEADY = 0,
    ETA_RAMP_STARTED,
    ETA_RAMP_PENDING,
    ETA_RAMP_MOVED,
    ETA_RAMP_STOPPED,
    ETA_RAMP_HARD_EXIT,
    ETA_RAMP_TIME_REVERSED,
    ETA_RAMP_SAMPLE_GAP,
    ETA_RAMP_ARGUMENT_INVALID
};

enum eta_fan_direction {
    ETA_DIRECTION_HOLD = 0,
    ETA_DIRECTION_UP = 1,
    ETA_DIRECTION_DOWN = 2
};

struct eta_fan_ramp_state {
    eta_u32 initialized;
    eta_u32 requested_level;      /* last logical software request; 0 = off */
    eta_u64 last_change_seconds;  /* valid even when exactly 0 */
    eta_u64 last_call_ms;
};

struct eta_fan_ramp_input {
    eta_u64 now_ms;
    eta_s32 target;               /* final arbitrated integer target, clamp 0..20 */
    eta_u32 hard_exit;            /* MUST propagate policy/safety exit */
};

struct eta_fan_ramp_output {
    eta_u32 requested_level;
    eta_u32 output_changed;       /* hint only; NEVER performs a write */
    eta_u32 release;
    enum eta_fan_direction direction;
    enum eta_fan_ramp_reason reason;
};

void eta_fan_normal_reset(struct eta_fan_ramp_state *state);
/* Optional takeover seed: caller has independently validated last requested
 * output AND enable state. Pass 0 when disabled, not retained sysfs level.
 * NEVER seed with an effective/derated level; write failure/ownership conflict
 * must be handled by the adapter, not retried/compensated inside this core.
 */
void eta_fan_normal_seed(struct eta_fan_ramp_state *state,
    eta_s32 requested_level, eta_u64 now_ms);
/* Only ordinary normal 6..20, up trunc(elapsed_seconds/5), down /10.
 * Fresh off->positive always starts at 6. Pending preserves last-change time;
 * an equal target resets it. target=0 slews to 6, then stops on NEXT call.
 * Time reversal / call gap >=15 s release rather than banking a large jump.
 * There is deliberately NO effective level input and no derate compensation.
 * Kernel adapter must honor hard_exit (including policy context changes),
 * arbitrate upstream demand, serialize state, and submit only necessary edges.
 */
void eta_fan_normal_step(struct eta_fan_ramp_state *state,
    const struct eta_fan_ramp_input *input, struct eta_fan_ramp_output *output);

#endif /* ETA_FAN_POLICY_CORE_H */
