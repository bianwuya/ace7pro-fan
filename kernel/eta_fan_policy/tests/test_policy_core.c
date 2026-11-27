/* Offline userspace regression only; no device access and no kernel execution. */
#include "../policy_core.h"
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned long checks;
#define CHECK(expression) do { \
    ++checks; \
    if (!(expression)) { \
        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #expression); \
        exit(EXIT_FAILURE); \
    } \
} while (0)
#define EQ(actual, expected) do { \
    eta_u64 a_ = (eta_u64)(actual), e_ = (eta_u64)(expected); \
    ++checks; \
    if (a_ != e_) { \
        fprintf(stderr, "FAIL %s:%d: %s=%" PRIu64 " expected %s=%" PRIu64 "\n", \
            __FILE__, __LINE__, #actual, (uint64_t)a_, #expected, (uint64_t)e_); \
        exit(EXIT_FAILURE); \
    } \
} while (0)
#define RUN(test) do { \
    unsigned long start_ = checks; \
    test(); \
    printf("PASS %-38s %lu assertions\n", #test, checks - start_); \
} while (0)

static struct eta_fan_config config_example(void)
{
    struct eta_fan_config config = {0};

    /* User's selected daily settings are a FIXTURE, never hardcoded policy. */
    config.version = 1;
    config.daily.enabled = ETA_FAN_YES;
    config.daily.start_mc = 36000;
    config.daily.floor = 10;
    config.daily.weight[ETA_FAN_BATTERY] = 60000000;
    config.daily.weight[ETA_FAN_SURROUND] = 40000000;
    config.charge.enabled = ETA_FAN_YES;
    config.charge.start_mc = 33000;
    config.charge.floor = 6;
    config.charge.gain_milli = 2000;
    config.charge.ceiling = 20;
    /* ChargeCurve defaults + legacy battery-only FusionTemp, canonical order. */
    config.charge.weight[ETA_FAN_BATTERY] = ETA_FAN_WEIGHT_UNIT;
    return config;
}

static void at(struct eta_fan_policy_input *input, eta_u64 now, eta_s32 temperature)
{
    eta_u32 i;

    input->now_ms = now;
    input->sample.at_ms = now;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i)
        input->sample.temperature_mc[i] = temperature;
}

static void power_at(struct eta_fan_policy_input *input, enum eta_fan_power power,
    eta_u64 epoch)
{
    input->power = power;
    input->sample.power = power;
    input->power_epoch = epoch;
    input->sample.power_epoch = epoch;
}

static struct eta_fan_policy_input input_example(eta_u64 now, eta_s32 temperature)
{
    struct eta_fan_policy_input input = {0};

    at(&input, now, temperature);
    power_at(&input, ETA_POWER_BATTERY, 1);
    input.channel_generation = 1;
    input.sample.channel_generation = 1;
    input.sample.valid_mask = ETA_FAN_ALL_CHANNELS;
    input.main_enabled = ETA_FAN_YES;
    input.mode = ETA_MODE_AUTO;
    input.auto_enabled = ETA_FAN_YES;
    input.ui_clear = ETA_FAN_YES;
    input.protection_clear = ETA_FAN_YES;
    input.abort_clear = ETA_FAN_YES;
    input.noise_clear = ETA_FAN_YES;
    input.handle_clear = ETA_FAN_YES;
    input.hardware_healthy = ETA_FAN_YES;
    input.common_scene = ETA_FAN_YES;
    input.thermal_status_valid = ETA_FAN_YES;
    return input;
}

static struct eta_fan_policy_output policy(struct eta_fan_policy_state *state,
    const struct eta_fan_config *config, const struct eta_fan_policy_input *input)
{
    struct eta_fan_policy_output output;

    eta_fan_policy_step(state, config, input, &output);
    return output;
}

static struct eta_fan_ramp_output ramp(struct eta_fan_ramp_state *state,
    eta_s32 target, eta_u64 now, eta_u32 hard_exit)
{
    struct eta_fan_ramp_input input = {0};
    struct eta_fan_ramp_output output;

    input.target = target;
    input.now_ms = now;
    input.hard_exit = hard_exit;
    eta_fan_normal_step(state, &input, &output);
    return output;
}

static void denied(const struct eta_fan_policy_output *output, enum eta_fan_reason reason)
{
    EQ(output->reason, reason);
    EQ(output->has_target, 0);
    EQ(output->target, 0);
    EQ(output->active, 0);
    EQ(output->cooling, 0);
    EQ(output->hard_exit, 1);
}

static void fresh_deny(struct eta_fan_config *config,
    struct eta_fan_policy_input *input, enum eta_fan_reason reason)
{
    struct eta_fan_policy_state state = {0};
    struct eta_fan_policy_output output = policy(&state, config, input);

    denied(&output, reason);
    EQ(state.context_valid, 0);
}

static void test_types_and_arguments(void)
{
    struct eta_fan_policy_state state = {0};
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input = input_example(0, 37000);
    struct eta_fan_policy_output output;
    struct eta_fan_fusion fusion;
    struct eta_fan_ramp_state slew = {0};
    struct eta_fan_ramp_output result;
    enum eta_fan_reason reason;

    EQ(sizeof(eta_s32), 4);
    EQ(sizeof(eta_u32), 4);
    EQ(sizeof(eta_u64), 8);
    output = policy(NULL, &config, &input);
    denied(&output, ETA_REASON_ARGUMENT_INVALID);
    output = policy(&state, &config, NULL);
    denied(&output, ETA_REASON_ARGUMENT_INVALID);
    output = policy(&state, NULL, &input);
    denied(&output, ETA_REASON_CONFIG_UNAVAILABLE);
    EQ(eta_fan_fuse(NULL, &input.sample, &fusion), ETA_REASON_ARGUMENT_INVALID);
    EQ(eta_fan_fuse(config.daily.weight, NULL, &fusion), ETA_REASON_ARGUMENT_INVALID);
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, NULL), ETA_REASON_ARGUMENT_INVALID);
    eta_fan_normal_step(NULL, NULL, &result);
    EQ(result.reason, ETA_RAMP_ARGUMENT_INVALID);
    EQ(result.release, 1);
    eta_fan_normal_seed(&slew, 20, 0);
    eta_fan_normal_step(&slew, NULL, &result);
    EQ(result.reason, ETA_RAMP_ARGUMENT_INVALID);
    EQ(result.output_changed, 1);
    EQ(slew.requested_level, 0);
    eta_fan_policy_step(&state, &config, &input, NULL);
    eta_fan_normal_step(&slew, NULL, NULL);
    eta_fan_policy_reset(NULL);
    eta_fan_normal_reset(NULL);
    eta_fan_normal_seed(NULL, 10, 0);
    for (reason = ETA_REASON_READY; reason <= ETA_REASON_SAMPLE_GAP; ++reason)
        CHECK(strcmp(eta_fan_reason_name(reason), "unknown_reason") != 0);
    CHECK(strcmp(eta_fan_reason_name((enum eta_fan_reason)1000), "unknown_reason") == 0);
    input = (struct eta_fan_policy_input){0};
    fresh_deny(&config, &input, ETA_REASON_POWER_UNKNOWN);
}

static void test_fusion_boundaries(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input = input_example(0, 0);
    struct eta_fan_fusion fusion;
    eta_u32 i;

    input.sample.temperature_mc[0] = 36000;
    input.sample.temperature_mc[1] = 54000;
    input.sample.temperature_mc[2] = 40000;
    input.sample.temperature_mc[3] = 54000;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 37600);
    EQ(fusion.contributor_mask, 5);
    EQ(fusion.total_weight, 100000000);
    EQ(fusion.weighted_mc, UINT64_C(3760000000000));
    input.sample.valid_mask = 14; /* missing weighted battery: renormalize */
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 40000);
    EQ(fusion.contributor_mask, 4);
    input.sample.valid_mask = 1;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 36000);
    EQ(fusion.contributor_mask, 1);
    input.sample.valid_mask = 10; /* only zero-weight channels available */
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_NO_CONTRIBUTORS);
    EQ(fusion.valid, 0);
    EQ(fusion.total_weight, 0);
    input.sample.valid_mask = 0;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_NO_CONTRIBUTORS);
    input.sample.valid_mask = 31;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_SAMPLE_INVALID);
    input.sample.valid_mask = 15;
    for (i = 0; i < 4; ++i)
        config.daily.weight[i] = ETA_FAN_WEIGHT_MAX;
    at(&input, 0, 65000);
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 65000);
    EQ(fusion.total_weight, UINT64_C(400000000));
    EQ(fusion.weighted_mc, UINT64_C(26000000000000));
    CHECK(fusion.weighted_mc * ETA_FAN_GAIN_MAX < (eta_u64)INT64_MAX);
    at(&input, 0, 0);
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.valid, 1);
    EQ(fusion.temperature_mc, 0);
    input.sample.temperature_mc[0] = INT32_MIN;
    input.sample.temperature_mc[1] = INT32_MAX;
    input.sample.temperature_mc[2] = -1;
    input.sample.temperature_mc[3] = 65001;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_NO_CONTRIBUTORS);
    at(&input, 0, 30000);
    config.daily.weight[0] = UINT32_MAX;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_WEIGHT_INVALID);
    config.daily.weight[0] = ETA_FAN_WEIGHT_MAX + 1;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_WEIGHT_INVALID);
    for (i = 0; i < 4; ++i)
        config.daily.weight[i] = 0;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_NO_CONTRIBUTORS);
    config.daily.weight[0] = 1; /* smallest representable positive setting */
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 30000);
    EQ(fusion.total_weight, 1);
    config.daily.weight[0] = 1;
    config.daily.weight[2] = 1;
    input.sample.temperature_mc[0] = 36000;
    input.sample.temperature_mc[2] = 36001;
    EQ(eta_fan_fuse(config.daily.weight, &input.sample, &fusion), ETA_REASON_READY);
    EQ(fusion.temperature_mc, 36000);
    EQ(fusion.weighted_mc, 72001);
    EQ(fusion.total_weight, 2);
}

static void test_raw_sensor_guards(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_output output;
    eta_u32 channel;
    const eta_s32 invalid[] = {-1, 65001, INT32_MIN, INT32_MAX};
    size_t i;

    for (channel = 0; channel < 4; ++channel) {
        eta_s32 limit = channel == 0 ? 45000 : 55000;

        input = input_example(0, 37000);
        input.sample.temperature_mc[channel] = limit - 1;
        state = (struct eta_fan_policy_state){0};
        output = policy(&state, &config, &input);
        EQ(output.has_target, 1);
        EQ(output.hard_exit, 0);
        input.sample.temperature_mc[channel] = limit;
        fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_GUARD);
        ++input.sample.temperature_mc[channel];
        fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_GUARD);
        input = input_example(0, 37000);
        input.sample.valid_mask &= ~(1U << channel);
        fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_MISSING);
        for (i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
            input = input_example(0, 37000);
            input.sample.temperature_mc[channel] = invalid[i];
            fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_INVALID);
        }
        /* Repeat guards while charging: zero-weight sensors are NOT exempt. */
        input = input_example(0, 34000);
        power_at(&input, ETA_POWER_CHARGING, 1);
        input.sample.temperature_mc[channel] = limit;
        fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_GUARD);
        input.sample.valid_mask &= ~(1U << channel);
        fresh_deny(&config, &input, ETA_REASON_TEMPERATURE_MISSING);
    }
    input = input_example(0, 0);
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    EQ(output.has_target, 1); /* measured zero is valid, missing is not zero */
    EQ(output.target, 0);
    input.sample.valid_mask = 31;
    fresh_deny(&config, &input, ETA_REASON_SAMPLE_INVALID);
    input = input_example(0, 37000);
    input.thermal_status_valid = ETA_FAN_UNKNOWN;
    fresh_deny(&config, &input, ETA_REASON_THERMAL_UNKNOWN);
    input.thermal_status_valid = ETA_FAN_NO;
    fresh_deny(&config, &input, ETA_REASON_THERMAL_UNKNOWN);
    input.thermal_status_valid = ETA_FAN_YES;
    for (i = 1; i <= 6; ++i) {
        input.thermal_status = (eta_s32)i;
        fresh_deny(&config, &input, ETA_REASON_THERMAL_BLOCKED);
    }
    input.thermal_status = -1;
    fresh_deny(&config, &input, ETA_REASON_THERMAL_BLOCKED);
}

static void test_permissions_and_settings(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_output output;
    enum eta_fan_flag flags[] = {ETA_FAN_UNKNOWN, ETA_FAN_NO, (enum eta_fan_flag)99};
    size_t i;
    eta_u32 gate;

    for (i = 0; i < sizeof(flags) / sizeof(flags[0]); ++i) {
        input = input_example(0, 37000);
        input.main_enabled = flags[i];
        fresh_deny(&config, &input, flags[i] == ETA_FAN_NO ? ETA_REASON_SWITCH_OFF : ETA_REASON_SWITCH_UNKNOWN);
        input = input_example(0, 37000);
        input.auto_enabled = flags[i];
        fresh_deny(&config, &input, flags[i] == ETA_FAN_NO ? ETA_REASON_AUTO_OFF : ETA_REASON_AUTO_UNKNOWN);
        for (gate = 0; gate < 7; ++gate) {
            input = input_example(0, 37000);
            switch (gate) {
            case 0: input.ui_clear = flags[i]; break;
            case 1: input.protection_clear = flags[i]; break;
            case 2: input.abort_clear = flags[i]; break;
            case 3: input.noise_clear = flags[i]; break;
            case 4: input.handle_clear = flags[i]; break;
            case 5: input.hardware_healthy = flags[i]; break;
            default: input.common_scene = flags[i]; break;
            }
            state = (struct eta_fan_policy_state){0};
            output = policy(&state, &config, &input);
            denied(&output, flags[i] == ETA_FAN_NO ? ETA_REASON_GATE_BLOCKED : ETA_REASON_GATE_UNKNOWN);
            EQ(output.gate_mask, 1U << gate);
        }
    }
    input = input_example(0, 37000);
    input.protection_clear = ETA_FAN_NO;
    input.handle_clear = ETA_FAN_UNKNOWN;
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_GATE_UNKNOWN);
    EQ(output.gate_mask, ETA_GATE_PROTECTION_CLEAR | ETA_GATE_HANDLE_CLEAR);
    input = input_example(0, 37000);
    input.mode = ETA_MODE_UNKNOWN;
    fresh_deny(&config, &input, ETA_REASON_MODE_UNKNOWN);
    input.mode = ETA_MODE_MANUAL;
    fresh_deny(&config, &input, ETA_REASON_MODE_BLOCKED);
    input.mode = ETA_MODE_OTHER;
    fresh_deny(&config, &input, ETA_REASON_MODE_BLOCKED);
    input.mode = (enum eta_fan_mode)99;
    fresh_deny(&config, &input, ETA_REASON_MODE_BLOCKED);
    input = input_example(0, 37000);
    config.daily.enabled = ETA_FAN_UNKNOWN;
    fresh_deny(&config, &input, ETA_REASON_SETTING_UNKNOWN);
    config.daily.enabled = ETA_FAN_NO;
    fresh_deny(&config, &input, ETA_REASON_SETTING_DISABLED);
    config = config_example();
    config.daily.start_mc = 19999;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.daily.start_mc = 50001;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.daily.start_mc = INT32_MIN;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.daily.start_mc = INT32_MAX;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config = config_example();
    config.daily.floor = 0;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.daily.floor = 21;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.daily.floor = UINT32_MAX;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config = config_example();
    config.daily.weight[1] = ETA_FAN_WEIGHT_MAX + 1;
    fresh_deny(&config, &input, ETA_REASON_WEIGHT_INVALID);
    config = config_example();
    memset(config.daily.weight, 0, sizeof(config.daily.weight));
    fresh_deny(&config, &input, ETA_REASON_NO_CONTRIBUTORS);
    config = config_example();
    power_at(&input, ETA_POWER_CHARGING, 1);
    config.charge.gain_milli = 0;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.charge.gain_milli = 8001;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.charge.gain_milli = UINT32_MAX;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config = config_example();
    config.charge.ceiling = 5;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.charge.ceiling = 21;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    config.charge.ceiling = 0;
    fresh_deny(&config, &input, ETA_REASON_CONFIG_INVALID);
    /* Legal endpoints, including custom non-user settings. */
    config = config_example();
    config.charge.start_mc = 20000;
    config.charge.floor = 1;
    config.charge.ceiling = 1;
    config.charge.gain_milli = 1;
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    EQ(output.target, 1);
    EQ(output.has_target, 1);
    config.charge.start_mc = 50000;
    config.charge.floor = 20;
    config.charge.ceiling = 20;
    config.charge.gain_milli = 8000;
    config.charge.weight[0] = 0;
    config.charge.weight[2] = 1;
    input.sample.temperature_mc[2] = 50000;
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    EQ(output.has_target, 1);
}

static void test_daily_threshold_and_hysteresis(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_state state = {0};
    struct eta_fan_policy_input input = input_example(0, 36000);
    struct eta_fan_policy_output output = policy(&state, &config, &input);
    const eta_u64 cooling_times[] = {5000, 15000, 25000, 34999, 35000};
    size_t i;

    EQ(output.target, 0); /* daily >, not >= */
    EQ(output.reason, ETA_REASON_BELOW_THRESHOLD);
    EQ(output.has_target, 1);
    at(&input, 1, 36001);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    EQ(output.active, 1);
    at(&input, 2, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    EQ(output.cooling, 0);
    at(&input, 3, 35001);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    EQ(output.cooling, 0);
    for (i = 0; i < sizeof(cooling_times) / sizeof(cooling_times[0]); ++i) {
        at(&input, cooling_times[i], 35000); /* <= is significant */
        output = policy(&state, &config, &input);
        EQ(output.has_target, 1);
        EQ(output.target, i == 4 ? 0 : 10);
        EQ(output.cooling, i == 4 ? 0 : 1);
        EQ(output.reason, i == 4 ? ETA_REASON_BELOW_THRESHOLD : ETA_REASON_COOLING);
    }
    at(&input, 35001, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0); /* no re-start on equality */
    at(&input, 35002, 36001);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 40000, 34999);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 1);
    at(&input, 45000, 35001);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 0); /* brief warm crossing cancels prior 30s timer */
    at(&input, 50000, 35000);
    output = policy(&state, &config, &input);
    EQ(state.cooling_since_ms, 50000);
    at(&input, 60000, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    /* Duplicate sample at a later call cannot advance the cooling timer. */
    input.now_ms = 64999;
    output = policy(&state, &config, &input);
    EQ(state.last_sample_ms, 60000);
    EQ(output.target, 10);
    at(&input, 70000, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 80000, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    /* Exact rational comparison must not lose a positive sub-mC remainder. */
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 36000);
    config.daily.weight[0] = 1;
    config.daily.weight[2] = 1;
    input.sample.temperature_mc[2] = 36001;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 36000);
    EQ(output.target, 10);
    at(&input, 5000, 35000);
    input.sample.temperature_mc[2] = 35001;
    output = policy(&state, &config, &input);
    EQ(output.cooling, 0); /* exact mean >35000 although display truncates */
    /* Daily floor 1 is a valid demand, normal physical slew still floors at 6. */
    config.daily.floor = 1;
    config.daily.start_mc = 20000;
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 20001);
    output = policy(&state, &config, &input);
    EQ(output.target, 1);
    config.daily.floor = 20;
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
}

static void test_daily_normal_steps_and_fusion(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_state state = {0};
    struct eta_fan_policy_state independent = {0};
    struct eta_fan_policy_input input = input_example(0, 39000);
    struct eta_fan_policy_output output;
    const eta_s32 rising[] = {34999, 35000, 36999, 37000, 38999, 39000,
        39999, 40000, 40999, 41000, 42999, 43000};
    const eta_u32 rise_levels[] = {0, 6, 6, 10, 10, 14, 14, 16, 16, 18, 18, 20};
    const eta_u32 rise_steps[] = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6};
    const eta_s32 falling[] = {42000, 41001, 41000, 40001, 40000, 39001,
        39000, 37001, 37000, 35001, 35000, 34000, 33001, 33000, 34000};
    const eta_u32 fall_levels[] = {20, 20, 18, 18, 16, 16, 14, 14, 10, 10, 6, 6, 6, 0, 0};
    const eta_u32 fall_steps[] = {6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 1, 0, 0};
    eta_u32 floor, repeat, channel;
    eta_u64 now;
    size_t i;

    /* All four measured channels contribute: exact mean = 40.597 C. */
    for (channel = 0; channel < ETA_FAN_CHANNELS; ++channel)
        config.daily.weight[channel] = 1;
    input.sample.temperature_mc[1] = 42000;
    input.sample.temperature_mc[2] = 41000;
    input.sample.temperature_mc[3] = 40388;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 40597);
    EQ(output.fusion.contributor_mask, ETA_FAN_ALL_CHANNELS);
    EQ(output.target, 16);
    EQ(state.daily_step, 4);
    at(&input, 1, 41000);
    output = policy(&state, &config, &input);
    EQ(output.target, 18);
    at(&input, 2, 43000);
    output = policy(&state, &config, &input);
    EQ(output.target, 20); /* crosses straight to the top ordinary step */
    at(&input, 3, 42000);
    output = policy(&state, &config, &input);
    EQ(output.target, 20); /* falling hysteresis band */
    output = policy(&independent, &config, &input);
    EQ(output.target, 18); /* another state must not inherit the high step */
    at(&input, 4, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10); /* multi-step descent, configured floor remains */
    EQ(state.daily_step, 1);
    EQ(output.cooling, 1);

    /* Every floor, including 20, is only a minimum; never the step state.
     * Repeated exact clear/earlier-trigger equalities must settle, not bounce.
     */
    config.daily.start_mc = 20000;
    for (floor = 1; floor <= ETA_FAN_MAX_LEVEL; ++floor) {
        config.daily.floor = floor;
        state = (struct eta_fan_policy_state){0};
        now = 0;
        for (i = 0; i < sizeof(rising) / sizeof(rising[0]); ++i) {
            at(&input, now++, rising[i]);
            output = policy(&state, &config, &input);
            EQ(output.target, floor > rise_levels[i] ? floor : rise_levels[i]);
            EQ(state.daily_step, rise_steps[i]);
            EQ(output.active, 1);
            EQ(output.hard_exit, 0);
        }
        for (i = 0; i < sizeof(falling) / sizeof(falling[0]); ++i) {
            for (repeat = 0; repeat < 3; ++repeat) {
                at(&input, now++, falling[i]);
                output = policy(&state, &config, &input);
                EQ(output.target, floor > fall_levels[i] ? floor : fall_levels[i]);
                EQ(state.daily_step, fall_steps[i]);
                EQ(output.active, 1);
                EQ(output.hard_exit, 0);
            }
        }
    }

    /* Fractions below a trigger / above a clear survive display truncation. */
    config = config_example();
    for (channel = 0; channel < ETA_FAN_CHANNELS; ++channel)
        config.daily.weight[channel] = ETA_FAN_WEIGHT_MAX;
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 41000);
    input.sample.temperature_mc[0] = 40999;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 40999);
    EQ(output.target, 16); /* exact 40999.75 < trigger 41000 */
    at(&input, 1, 41000);
    output = policy(&state, &config, &input);
    EQ(output.target, 18);
    at(&input, 2, 43000);
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    at(&input, 3, 41000);
    input.sample.temperature_mc[3] = 41001;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 41000);
    EQ(output.target, 20); /* exact 41000.25 > clear 41000 */
    at(&input, 4, 41000);
    output = policy(&state, &config, &input);
    EQ(output.target, 18);
    at(&input, 5, 40000);
    input.sample.temperature_mc[3] = 40001;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 40000);
    EQ(output.target, 18);
    at(&input, 6, 40000);
    output = policy(&state, &config, &input);
    EQ(output.target, 16);
}

static void test_daily_step_reset_and_charge_isolation(void)
{
    struct eta_fan_config config;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_input input;
    struct eta_fan_policy_output output;
    enum eta_fan_reason reason;
    eta_u32 variant;

    for (variant = 0; variant < 14; ++variant) {
        config = config_example();
        state = (struct eta_fan_policy_state){0};
        input = input_example(10000, 43000);
        output = policy(&state, &config, &input);
        EQ(output.target, 20);
        EQ(state.daily_step, 6);
        at(&input, 11000, 42000);
        output = policy(&state, &config, &input);
        EQ(output.target, 20);
        at(&input, 12000, 42000);
        reason = ETA_REASON_CONTEXT_CHANGED;
        switch (variant) {
        case 0: ++config.version; break;
        case 1: config.daily.floor = 12; break;
        case 2: config.daily.weight[0] = 1; break;
        case 3: ++input.power_epoch; ++input.sample.power_epoch; break;
        case 4: ++input.channel_generation; ++input.sample.channel_generation; break;
        case 5: power_at(&input, ETA_POWER_CHARGING, 2); break;
        case 6: power_at(&input, ETA_POWER_EXTERNAL_IDLE, 2);
            reason = ETA_REASON_EXTERNAL_IDLE; break;
        case 7: input.protection_clear = ETA_FAN_NO;
            reason = ETA_REASON_GATE_BLOCKED; break;
        case 8: input.sample.valid_mask = 7;
            reason = ETA_REASON_TEMPERATURE_MISSING; break;
        case 9: input.now_ms = 26000;
            reason = ETA_REASON_SAMPLE_GAP; break;
        case 10: input.now_ms = input.sample.at_ms + ETA_FAN_FRESH_MS;
            reason = ETA_REASON_SAMPLE_STALE; break;
        case 11: at(&input, 10999, 42000);
            reason = ETA_REASON_TIME_REVERSED; break;
        case 12: input.main_enabled = ETA_FAN_NO;
            reason = ETA_REASON_SWITCH_OFF; break;
        default: config.daily.enabled = ETA_FAN_NO;
            reason = ETA_REASON_SETTING_DISABLED; break;
        }
        output = policy(&state, &config, &input);
        denied(&output, reason);
        EQ(state.daily_step, 0);
        EQ(state.context_valid, 0);
        EQ(state.active, 0);
        if (variant == 5) {
            at(&input, 13000, 37000);
            output = policy(&state, &config, &input);
            EQ(output.policy, ETA_POLICY_CHARGE);
            EQ(output.target, 14); /* same existing charge curve, not daily 20 */
            EQ(state.daily_step, 0);
            at(&input, 14000, 42000);
            power_at(&input, ETA_POWER_BATTERY, 3);
            output = policy(&state, &config, &input);
            denied(&output, ETA_REASON_CONTEXT_CHANGED);
        }
        config.daily.enabled = ETA_FAN_YES;
        input = input_example(15000, 42000);
        output = policy(&state, &config, &input);
        EQ(output.target, 18); /* fresh 42 C may not retain the pre-reset 20 */
        EQ(state.daily_step, 5);
    }
}

static void test_daily_step_stop_and_floor20(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_state state = {0};
    struct eta_fan_policy_input input = input_example(0, 43000);
    struct eta_fan_policy_output output = policy(&state, &config, &input);
    const eta_u64 times[] = {1000, 11000, 21000, 30999, 31000};
    size_t i;

    EQ(output.target, 20);
    for (i = 0; i < sizeof(times) / sizeof(times[0]); ++i) {
        at(&input, times[i], 35000);
        output = policy(&state, &config, &input);
        EQ(output.target, i == 4 ? 0 : 10);
        EQ(output.cooling, i == 4 ? 0 : 1);
        EQ(output.active, i == 4 ? 0 : 1);
        EQ(state.daily_step, i == 4 ? 0 : 1);
    }
    at(&input, 31001, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0); /* stopping does not relax strict start equality */
    EQ(state.daily_step, 0);
    at(&input, 31002, 36001);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    EQ(state.daily_step, 1);
    config.daily.floor = 20;
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    at(&input, 1, 36001);
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    EQ(state.daily_step, 1); /* floor must not force the top step latch */
    at(&input, 2, 43000);
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    EQ(state.daily_step, 6);
    at(&input, 3, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    EQ(state.daily_step, 1);
    eta_fan_policy_reset(&state);
    EQ(state.daily_step, 0);
}

static void test_charge_curve_and_hysteresis(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_output output;
    const eta_s32 temperatures[] = {32999, 33000, 33249, 33250, 33749, 33750,
        34000, 35000, 37000, 39000, 39749, 39750, 40000, 44999};
    const eta_u32 expected[] = {0, 6, 6, 7, 7, 8, 8, 10, 14, 18, 19, 20, 20, 20};
    const eta_u64 times[] = {5000, 15000, 25000, 34999, 35000};
    size_t i;

    for (i = 0; i < sizeof(temperatures) / sizeof(temperatures[0]); ++i) {
        state = (struct eta_fan_policy_state){0};
        input = input_example(0, temperatures[i]);
        power_at(&input, ETA_POWER_CHARGING, 1);
        output = policy(&state, &config, &input);
        EQ(output.has_target, 1);
        EQ(output.policy, ETA_POLICY_CHARGE);
        EQ(output.target, expected[i]);
    }
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 33000);
    power_at(&input, ETA_POWER_CHARGING, 1);
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    at(&input, 1, 32000); /* equality is NOT charge cooling */
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    EQ(output.cooling, 0);
    for (i = 0; i < sizeof(times) / sizeof(times[0]); ++i) {
        at(&input, times[i], 31999);
        output = policy(&state, &config, &input);
        EQ(output.has_target, 1);
        EQ(output.target, i == 4 ? 0 : 6);
        EQ(output.cooling, i == 4 ? 0 : 1);
    }
    at(&input, 35001, 32999);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    at(&input, 35002, 33000);
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    at(&input, 40000, 31000);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 1);
    at(&input, 45000, 32000);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 0);
    at(&input, 50000, 31999);
    output = policy(&state, &config, &input);
    EQ(state.cooling_since_ms, 50000);
    at(&input, 60000, 31999);
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    at(&input, 70000, 31999);
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    at(&input, 80000, 31999);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    /* Half-up rounding BEFORE display truncation, not banker rounding. */
    config.charge.weight[0] = 1;
    config.charge.weight[2] = 1;
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 33249);
    power_at(&input, ETA_POWER_CHARGING, 1);
    input.sample.temperature_mc[2] = 33250;
    output = policy(&state, &config, &input);
    EQ(output.fusion.temperature_mc, 33249);
    EQ(output.target, 6); /* exact 33249.5 => raw 6.499 */
    at(&input, 1, 33250);
    output = policy(&state, &config, &input);
    EQ(output.target, 7);
    at(&input, 2, 33750);
    output = policy(&state, &config, &input);
    EQ(output.target, 8); /* raw 7.5 */
    /* Configurable curve is not permanently tied to 33 C / 6 / 2 / 20. */
    config.charge.start_mc = 30000;
    config.charge.floor = 4;
    config.charge.ceiling = 12;
    config.charge.gain_milli = 1500;
    state = (struct eta_fan_policy_state){0};
    at(&input, 0, 31000);
    output = policy(&state, &config, &input);
    EQ(output.target, 6); /* round(4+1.5)=6 */
    at(&input, 1, 40000);
    output = policy(&state, &config, &input);
    EQ(output.target, 12);
    /* Maximum legal weights and gain prove multiply/add bounds under UBSan. */
    config.charge.start_mc = 20000;
    config.charge.gain_milli = 8000;
    config.charge.floor = 1;
    config.charge.ceiling = 20;
    for (i = 0; i < 4; ++i)
        config.charge.weight[i] = ETA_FAN_WEIGHT_MAX;
    state = (struct eta_fan_policy_state){0};
    at(&input, UINT64_MAX, 54999);
    input.sample.temperature_mc[0] = 44999;
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    EQ(output.fusion.weighted_mc, UINT64_C(20999600000000));
    EQ(output.has_target, 1);
}

static void test_freshness_and_time(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input = input_example(14999, 37000);
    struct eta_fan_policy_state state = {0};
    struct eta_fan_policy_output output;

    input.sample.at_ms = 0;
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    EQ(output.has_target, 1);
    input.now_ms = 15000;
    fresh_deny(&config, &input, ETA_REASON_SAMPLE_STALE);
    input.now_ms = UINT64_MAX;
    fresh_deny(&config, &input, ETA_REASON_SAMPLE_STALE);
    input = input_example(0, 37000);
    input.sample.at_ms = 1;
    fresh_deny(&config, &input, ETA_REASON_SAMPLE_FUTURE);
    input.sample.at_ms = UINT64_MAX;
    fresh_deny(&config, &input, ETA_REASON_SAMPLE_FUTURE);
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 14999, 35000);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 1);
    at(&input, 29999, 35000);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_SAMPLE_GAP);
    EQ(state.context_valid, 0);
    at(&input, 30000, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0); /* old hysteresis lock was released */
    state = (struct eta_fan_policy_state){0};
    input = input_example(10000, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 9999, 37000);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_TIME_REVERSED);
    at(&input, 10000, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    at(&input, 11000, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    input.now_ms = 12000;
    input.sample.at_ms = 10999;
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_TIME_REVERSED);
    /* Fresh current sample, but >=15s call gap must still release. */
    state = (struct eta_fan_policy_state){0};
    input = input_example(10000, 37000);
    input.sample.at_ms = 1;
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 25000, 37000);
    input.sample.at_ms = 14000;
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_SAMPLE_GAP);
    /* Sample gap >=15s despite call gap <15s must independently release. */
    state = (struct eta_fan_policy_state){0};
    input = input_example(10000, 37000);
    input.sample.at_ms = 0;
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 15000, 37000);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_SAMPLE_GAP);
    state = (struct eta_fan_policy_state){0};
    input = input_example(UINT64_MAX - 10000, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, UINT64_MAX, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 0, 37000); /* explicit wrap treated as clock reversal */
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_TIME_REVERSED);
}

static void test_power_and_context_isolation(void)
{
    struct eta_fan_config config;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_input input;
    struct eta_fan_policy_output output;
    eta_u32 variant;

    for (variant = 0; variant < 9; ++variant) {
        config = config_example();
        state = (struct eta_fan_policy_state){0};
        input = input_example(0, 37000);
        output = policy(&state, &config, &input);
        EQ(output.target, 10);
        at(&input, 5000, 35000);
        output = policy(&state, &config, &input);
        EQ(output.cooling, 1);
        at(&input, 10000, 35000);
        switch (variant) {
        case 0: ++config.version; break;
        case 1: config.daily.floor = 12; break;
        case 2: config.daily.start_mc = 37000; break;
        case 3: config.daily.weight[0] = 50; break;
        case 4: ++input.power_epoch; ++input.sample.power_epoch; break;
        case 5: ++input.channel_generation; ++input.sample.channel_generation; break;
        case 6: power_at(&input, ETA_POWER_CHARGING, 2); break;
        case 7: config.daily.gain_milli = 1; break; /* conservative change detection */
        default: config.daily.ceiling = 20; break;
        }
        output = policy(&state, &config, &input);
        denied(&output, ETA_REASON_CONTEXT_CHANGED);
        CHECK(output.reset_flags != 0);
        if (variant == 4 || variant == 6)
            CHECK(output.reset_flags & ETA_RESET_POWER);
        else if (variant == 5)
            CHECK(output.reset_flags & ETA_RESET_CHANNELS);
        else
            CHECK(output.reset_flags & ETA_RESET_CONFIG);
        EQ(state.context_valid, 0);
        at(&input, 10001, variant == 6 ? 32999 : 35000);
        output = policy(&state, &config, &input);
        EQ(output.target, 0);
        EQ(output.cooling, 0);
        EQ(output.has_target, 1);
    }
    config = config_example();
    input = input_example(0, 33000);
    power_at(&input, ETA_POWER_CHARGING, 1);
    state = (struct eta_fan_policy_state){0};
    output = policy(&state, &config, &input);
    EQ(output.target, 6);
    at(&input, 5000, 33000);
    power_at(&input, ETA_POWER_BATTERY, 2);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_CONTEXT_CHANGED);
    at(&input, 5001, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0); /* charge latch not imported into daily */
    input = input_example(0, 37000);
    input.sample.power = ETA_POWER_CHARGING;
    fresh_deny(&config, &input, ETA_REASON_POWER_SAMPLE_MISMATCH);
    input = input_example(0, 37000);
    ++input.sample.power_epoch;
    fresh_deny(&config, &input, ETA_REASON_POWER_SAMPLE_MISMATCH);
    input = input_example(0, 37000);
    ++input.sample.channel_generation;
    fresh_deny(&config, &input, ETA_REASON_CHANNEL_SAMPLE_MISMATCH);
    input = input_example(0, 37000);
    power_at(&input, ETA_POWER_EXTERNAL_IDLE, 2);
    fresh_deny(&config, &input, ETA_REASON_EXTERNAL_IDLE);
    power_at(&input, ETA_POWER_UNKNOWN, 3);
    fresh_deny(&config, &input, ETA_REASON_POWER_UNKNOWN);
    power_at(&input, (enum eta_fan_power)99, 4);
    fresh_deny(&config, &input, ETA_REASON_POWER_UNKNOWN);
    /* A valid zero-demand decision is distinguishable from release. */
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 33000);
    output = policy(&state, &config, &input);
    EQ(output.has_target, 1);
    EQ(output.target, 0);
    EQ(output.hard_exit, 0);
    power_at(&input, ETA_POWER_EXTERNAL_IDLE, 2);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_EXTERNAL_IDLE);
    power_at(&input, ETA_POWER_BATTERY, 3);
    at(&input, 1, 36000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
    EQ(output.cooling, 0);
    /* Missing a zero-weight channel releases a previously active latch. */
    at(&input, 2, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    input.sample.valid_mask &= ~2U;
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_TEMPERATURE_MISSING);
    input.sample.valid_mask = 15;
    at(&input, 3, 35000);
    output = policy(&state, &config, &input);
    EQ(output.target, 0);
}

static void test_all_power_transitions_and_context_mask(void)
{
    const enum eta_fan_power powers[] = {ETA_POWER_BATTERY, ETA_POWER_CHARGING,
        ETA_POWER_EXTERNAL_IDLE, ETA_POWER_UNKNOWN};
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_output output;
    size_t from, to;

    for (from = 0; from < 4; ++from) {
        for (to = 0; to < 4; ++to) {
            state = (struct eta_fan_policy_state){0};
            input = input_example(0, 44000);
            power_at(&input, powers[from], 1);
            output = policy(&state, &config, &input);
            EQ(output.has_target, from < 2 ? 1 : 0);
            at(&input, 5000, 33000);
            power_at(&input, powers[to], from == to ? 1 : 2);
            output = policy(&state, &config, &input);
            if (to >= 2) {
                denied(&output, to == 2 ? ETA_REASON_EXTERNAL_IDLE : ETA_REASON_POWER_UNKNOWN);
                power_at(&input, ETA_POWER_BATTERY, 3);
                at(&input, 5001, 36000);
                output = policy(&state, &config, &input);
                EQ(output.target, 0);
                EQ(output.cooling, 0);
            } else if (from < 2 && from != to) {
                denied(&output, ETA_REASON_CONTEXT_CHANGED);
                CHECK(output.reset_flags & ETA_RESET_POWER);
                at(&input, 5001, to == 0 ? 36000 : 32999);
                output = policy(&state, &config, &input);
                EQ(output.target, 0);
                EQ(output.cooling, 0);
                EQ(output.has_target, 1);
            } else {
                EQ(output.has_target, 1);
                EQ(output.hard_exit, 0);
                EQ(output.target, from == to ? (to == 0 ? 10 : 6) : (to == 0 ? 0 : 6));
            }
        }
    }
    state = (struct eta_fan_policy_state){0};
    input = input_example(0, 37000);
    output = policy(&state, &config, &input);
    EQ(output.target, 10);
    at(&input, 5000, 35000);
    output = policy(&state, &config, &input);
    EQ(output.cooling, 1);
    config.daily.weight[1] = 1; /* zero -> positive: new fusion cohort */
    at(&input, 10000, 35000);
    output = policy(&state, &config, &input);
    denied(&output, ETA_REASON_CONTEXT_CHANGED);
    EQ(output.reset_flags, ETA_RESET_CONFIG | ETA_RESET_CHANNELS);
    EQ(state.active, 0);
    EQ(state.cooling, 0);
}

static void test_normal_ramp_quantization(void)
{
    struct eta_fan_ramp_state state = {0};
    struct eta_fan_ramp_output output;
    eta_u64 now;

    output = ramp(&state, 20, 0, 0);
    EQ(output.requested_level, 6);
    EQ(output.output_changed, 1);
    EQ(output.reason, ETA_RAMP_STARTED);
    EQ(state.last_change_seconds, 0); /* 0 is a valid initialized timestamp */
    output = ramp(&state, 20, 999, 0);
    EQ(output.requested_level, 6);
    EQ(output.output_changed, 0);
    EQ(output.reason, ETA_RAMP_PENDING);
    EQ(state.last_change_seconds, 0);
    output = ramp(&state, 20, 4999, 0);
    EQ(output.requested_level, 6);
    EQ(state.last_change_seconds, 0);
    output = ramp(&state, 20, 5000, 0);
    EQ(output.requested_level, 7);
    EQ(output.output_changed, 1);
    EQ(state.last_change_seconds, 5);
    output = ramp(&state, 20, 10000, 0);
    EQ(output.requested_level, 8);
    output = ramp(&state, 20, 22000, 0);
    EQ(output.requested_level, 10); /* trunc(12/5)=2 */
    EQ(state.last_change_seconds, 22);
    output = ramp(&state, 20, 24999, 0);
    EQ(output.requested_level, 10); /* no carried .4 remainder */
    output = ramp(&state, 20, 27000, 0);
    EQ(output.requested_level, 11);
    eta_fan_normal_seed(&state, 10, 0);
    output = ramp(&state, 6, 5000, 0);
    EQ(output.requested_level, 10);
    EQ(output.output_changed, 0);
    EQ(state.last_change_seconds, 0);
    output = ramp(&state, 6, 9999, 0);
    EQ(output.requested_level, 10);
    output = ramp(&state, 6, 10000, 0);
    EQ(output.requested_level, 9);
    EQ(output.output_changed, 1);
    EQ(state.last_change_seconds, 10);
    /* Same target resets time; never bank many quiet ticks for a jump. */
    eta_fan_normal_seed(&state, 10, 0);
    for (now = 10000; now <= 100000; now += 10000) {
        output = ramp(&state, 10, now, 0);
        EQ(output.requested_level, 10);
        EQ(output.output_changed, 0);
        EQ(state.last_change_seconds, now / 1000);
    }
    output = ramp(&state, 20, 104999, 0);
    EQ(output.requested_level, 10);
    output = ramp(&state, 20, 105000, 0);
    EQ(output.requested_level, 11);
    /* Integer seconds, not fractional elapsed milliseconds. */
    eta_fan_normal_seed(&state, 10, 1999);
    output = ramp(&state, 20, 5999, 0);
    EQ(output.requested_level, 10); /* 5-1 = 4 sec */
    output = ramp(&state, 20, 6000, 0);
    EQ(output.requested_level, 11); /* 6-1 = 5 sec */
    /* Pending target/direction changes must not restart the timestamp. */
    eta_fan_normal_seed(&state, 10, 0);
    output = ramp(&state, 20, 4000, 0);
    EQ(output.direction, ETA_DIRECTION_UP);
    EQ(output.requested_level, 10);
    output = ramp(&state, 6, 5000, 0);
    EQ(output.direction, ETA_DIRECTION_DOWN);
    EQ(output.output_changed, 0);
    EQ(state.last_change_seconds, 0);
    output = ramp(&state, 6, 10000, 0);
    EQ(output.requested_level, 9);
    /* Repeated 7-second ticks each truncate: not 1 then 2. */
    eta_fan_normal_seed(&state, 6, 0);
    output = ramp(&state, 20, 7000, 0);
    EQ(output.requested_level, 7);
    output = ramp(&state, 20, 14000, 0);
    EQ(output.requested_level, 8);
    /* Clamp overshoots to target; no minimum forced one-step movement. */
    eta_fan_normal_seed(&state, 19, 0);
    output = ramp(&state, INT32_MAX, 14000, 0);
    EQ(output.requested_level, 20);
    output = ramp(&state, 20, 14001, 0);
    EQ(output.output_changed, 0);
}

static void test_normal_stop_exit_and_derate(void)
{
    struct eta_fan_ramp_state state = {0};
    struct eta_fan_ramp_output output;
    const eta_u32 stopping[] = {8, 7, 7, 6, 0};
    size_t i;
    eta_u32 observed_effective;

    eta_fan_normal_seed(&state, 8, 0);
    for (i = 0; i < sizeof(stopping) / sizeof(stopping[0]); ++i) {
        output = ramp(&state, 0, (eta_u64)(i + 1) * 5000, 0);
        EQ(output.requested_level, stopping[i]);
        EQ(output.output_changed, i == 0 || i == 2 ? 0 : 1);
    }
    EQ(output.reason, ETA_RAMP_STOPPED);
    output = ramp(&state, 0, 25001, 0);
    EQ(output.output_changed, 0);
    output = ramp(&state, 20, 25002, 0);
    EQ(output.requested_level, 6);
    eta_fan_normal_seed(&state, 7, 0);
    output = ramp(&state, 0, 10000, 0);
    EQ(output.requested_level, 6); /* not 0 on the same call */
    output = ramp(&state, 0, 10000, 0);
    EQ(output.requested_level, 0); /* NEXT call even if same integer second */
    eta_fan_normal_seed(&state, 6, 0);
    output = ramp(&state, 0, 1, 0);
    EQ(output.requested_level, 0);
    eta_fan_normal_reset(&state);
    output = ramp(&state, 1, 0, 0);
    EQ(output.requested_level, 6); /* positive physical normal floor */
    eta_fan_normal_seed(&state, 10, 0);
    output = ramp(&state, 1, 10000, 0);
    EQ(output.requested_level, 9);
    eta_fan_normal_seed(&state, -1, 0);
    EQ(state.requested_level, 0);
    eta_fan_normal_seed(&state, INT32_MAX, 0);
    EQ(state.requested_level, 20);
    output = ramp(&state, INT32_MIN, 5000, 0);
    EQ(output.requested_level, 20); /* clipped to stop; downward pending */
    output = ramp(&state, 0, 5001, 1);
    EQ(output.requested_level, 0);
    EQ(output.output_changed, 1);
    EQ(output.release, 1);
    EQ(output.reason, ETA_RAMP_HARD_EXIT);
    output = ramp(&state, 20, 5002, 0);
    EQ(output.requested_level, 6); /* hard exit clears all ramp continuity */
    output = ramp(&state, 20, 5001, 0);
    EQ(output.reason, ETA_RAMP_TIME_REVERSED);
    EQ(output.requested_level, 0);
    EQ(output.release, 1);
    eta_fan_normal_seed(&state, 6, 0);
    output = ramp(&state, 20, 14999, 0);
    EQ(output.requested_level, 8);
    eta_fan_normal_seed(&state, 6, 0);
    output = ramp(&state, 20, 15000, 0);
    EQ(output.reason, ETA_RAMP_SAMPLE_GAP);
    EQ(output.requested_level, 0);
    EQ(output.release, 1);
    output = ramp(&state, 20, 15001, 0);
    EQ(output.requested_level, 6);
    eta_fan_normal_seed(&state, 10, UINT64_MAX - 10000);
    output = ramp(&state, 20, UINT64_MAX, 0);
    EQ(output.requested_level, 12);
    output = ramp(&state, 20, 0, 0);
    EQ(output.requested_level, 0);
    EQ(output.reason, ETA_RAMP_TIME_REVERSED);
    /* External effective telemetry is intentionally NOT an API input. */
    eta_fan_normal_seed(&state, 18, 0);
    observed_effective = 12;
    for (i = 1; i <= 20; ++i) {
        output = ramp(&state, 18, (eta_u64)i * 5000, 0);
        EQ(output.requested_level, 18);
        EQ(output.output_changed, 0);
        EQ(state.requested_level, 18);
        CHECK(observed_effective < output.requested_level);
        observed_effective = i % 2 ? 10 : 12;
    }
    output = ramp(&state, 20, 105000, 0);
    EQ(output.requested_level, 19); /* next step is from requested 18, NOT eff */
    EQ(output.output_changed, 1);
}

static void test_layers_and_immediate_safety_exit(void)
{
    struct eta_fan_config config = config_example();
    struct eta_fan_policy_input input = input_example(0, 44000);
    struct eta_fan_policy_state state = {0};
    struct eta_fan_ramp_state slew = {0};
    struct eta_fan_policy_output output;
    struct eta_fan_ramp_output result;
    eta_u32 variant;

    power_at(&input, ETA_POWER_CHARGING, 1);
    output = policy(&state, &config, &input);
    EQ(output.target, 20);
    result = ramp(&slew, (eta_s32)output.target, input.now_ms, output.hard_exit);
    EQ(result.requested_level, 6); /* policy 20 is NOT first output 20 */
    at(&input, 5000, 44000);
    output = policy(&state, &config, &input);
    result = ramp(&slew, (eta_s32)output.target, input.now_ms, output.hard_exit);
    EQ(result.requested_level, 7);
    for (variant = 0; variant < 9; ++variant) {
        config = config_example();
        state = (struct eta_fan_policy_state){0};
        input = input_example(0, 44000);
        output = policy(&state, &config, &input);
        EQ(output.target, 20); /* ordinary top step, not floor-only demand */
        eta_fan_normal_seed(&slew, 20, 0);
        at(&input, 1, 44000);
        switch (variant) {
        case 0: input.protection_clear = ETA_FAN_NO; break;
        case 1: input.handle_clear = ETA_FAN_NO; break;
        case 2: input.sample.temperature_mc[0] = 45000; break;
        case 3: input.hardware_healthy = ETA_FAN_UNKNOWN; break;
        case 4: input.sample.valid_mask = 0; break;
        case 5: input.noise_clear = ETA_FAN_NO; break;
        case 6: ++config.version; break;
        case 7: power_at(&input, ETA_POWER_EXTERNAL_IDLE, 2); break;
        default: input.mode = ETA_MODE_MANUAL; break;
        }
        output = policy(&state, &config, &input);
        EQ(output.hard_exit, 1);
        result = ramp(&slew, (eta_s32)output.target, input.now_ms, output.hard_exit);
        EQ(result.requested_level, 0); /* no 140s wait from 20 to 6 */
        EQ(result.release, 1);
        EQ(result.output_changed, 1);
        EQ(result.reason, ETA_RAMP_HARD_EXIT);
    }
}

static eta_u32 random_state = 0x13579bdfU;
static eta_u32 next_random(void)
{
    random_state = random_state * 1664525U + 1013904223U; /* specified u32 wrap */
    return random_state;
}

static void test_integer_oracles_and_large_weights(void)
{
    struct eta_fan_config config;
    struct eta_fan_policy_state state;
    struct eta_fan_policy_input input;
    struct eta_fan_policy_output output;
    struct eta_fan_fusion fusion;
    eta_u64 numerator, denominator, start, raw_increment;
    eta_u32 i, channel, expected;

    for (i = 0; i < 4096; ++i) {
        config = config_example();
        input = input_example(UINT64_MAX - (eta_u64)i, 0);
        power_at(&input, ETA_POWER_CHARGING, 1);
        numerator = denominator = 0;
        for (channel = 0; channel < 4; ++channel) {
            eta_u32 limit = channel == 0 ? 45000 : 55000;
            eta_u32 weight = next_random() % ETA_FAN_WEIGHT_MAX + 1;
            eta_u32 temperature = next_random() % limit;

            config.charge.weight[channel] = weight;
            input.sample.temperature_mc[channel] = (eta_s32)temperature;
            numerator += (eta_u64)weight * temperature;
            denominator += weight;
        }
        config.charge.start_mc = 20000 + (eta_s32)(next_random() % 30001U);
        config.charge.gain_milli = next_random() % 8000U + 1;
        config.charge.floor = next_random() % 20U + 1;
        config.charge.ceiling = config.charge.floor +
            next_random() % (21U - config.charge.floor);
        EQ(eta_fan_fuse(config.charge.weight, &input.sample, &fusion), ETA_REASON_READY);
        EQ(fusion.weighted_mc, numerator);
        EQ(fusion.total_weight, denominator);
        EQ(fusion.temperature_mc, numerator / denominator);
        start = (eta_u64)config.charge.start_mc * denominator;
        expected = 0;
        if (numerator >= start) {
            /* Independent quotient/remainder half-up oracle; no FP. */
            eta_u64 n = (numerator - start) * config.charge.gain_milli;
            eta_u64 d = denominator * 1000000U;
            raw_increment = n / d + (n % d >= (d + 1U) / 2U);
            expected = raw_increment >= config.charge.ceiling - config.charge.floor ?
                config.charge.ceiling : config.charge.floor + (eta_u32)raw_increment;
        }
        state = (struct eta_fan_policy_state){0};
        output = policy(&state, &config, &input);
        EQ(output.has_target, 1);
        EQ(output.hard_exit, 0);
        EQ(output.target, expected);
        CHECK(output.target <= ETA_FAN_MAX_LEVEL);
    }
    /* Full mC-resolution default charge curve sweep, independently rounded. */
    config = config_example();
    for (i = 0; i < 45000; ++i) {
        input = input_example(0, (eta_s32)i);
        power_at(&input, ETA_POWER_CHARGING, 1);
        state = (struct eta_fan_policy_state){0};
        output = policy(&state, &config, &input);
        expected = i < 33000 ? 0 : 6U + (2U * (i - 33000U) + 500U) / 1000U;
        if (expected > 20)
            expected = 20;
        EQ(output.target, expected);
        EQ(output.has_target, 1);
    }
}

int main(void)
{
    RUN(test_types_and_arguments);
    RUN(test_fusion_boundaries);
    RUN(test_raw_sensor_guards);
    RUN(test_permissions_and_settings);
    RUN(test_daily_threshold_and_hysteresis);
    RUN(test_daily_normal_steps_and_fusion);
    RUN(test_daily_step_reset_and_charge_isolation);
    RUN(test_daily_step_stop_and_floor20);
    RUN(test_charge_curve_and_hysteresis);
    RUN(test_freshness_and_time);
    RUN(test_power_and_context_isolation);
    RUN(test_all_power_transitions_and_context_mask);
    RUN(test_normal_ramp_quantization);
    RUN(test_normal_stop_exit_and_derate);
    RUN(test_layers_and_immediate_safety_exit);
    RUN(test_integer_oracles_and_large_weights);
    CHECK(checks >= 100);
    printf("PASS total: %lu assertions; pure userspace offline tests only\n", checks);
    return EXIT_SUCCESS;
}
