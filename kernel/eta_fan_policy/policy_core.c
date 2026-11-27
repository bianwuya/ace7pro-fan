#include "policy_core.h"

static eta_u32 flag_known(enum eta_fan_flag flag)
{
    return flag == ETA_FAN_NO || flag == ETA_FAN_YES;
}

static eta_u32 temperature_valid(eta_s32 temperature_mc)
{
    return temperature_mc >= 0 && temperature_mc <= 65000;
}

static eta_u32 weights_valid(const eta_u32 weight[ETA_FAN_CHANNELS])
{
    eta_u32 i;

    for (i = 0; i < ETA_FAN_CHANNELS; ++i)
        if (weight[i] > ETA_FAN_WEIGHT_MAX)
            return 0;
    return 1;
}

enum eta_fan_reason eta_fan_fuse(const eta_u32 weight[ETA_FAN_CHANNELS],
    const struct eta_fan_sample *sample, struct eta_fan_fusion *fusion)
{
    eta_u32 i;

    if (!fusion)
        return ETA_REASON_ARGUMENT_INVALID;
    *fusion = (struct eta_fan_fusion){0};
    if (!weight || !sample)
        return ETA_REASON_ARGUMENT_INVALID;
    if (!weights_valid(weight))
        return ETA_REASON_WEIGHT_INVALID;
    if ((sample->valid_mask & ~ETA_FAN_ALL_CHANNELS) != 0)
        return ETA_REASON_SAMPLE_INVALID;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i) {
        if (weight[i] == 0 || (sample->valid_mask & (1U << i)) == 0 ||
            !temperature_valid(sample->temperature_mc[i]))
            continue;
        fusion->contributor_mask |= 1U << i;
        fusion->total_weight += (eta_u64)weight[i];
        fusion->weighted_mc += (eta_u64)weight[i] *
            (eta_u64)sample->temperature_mc[i];
    }
    if (fusion->total_weight == 0)
        return ETA_REASON_NO_CONTRIBUTORS;
    fusion->temperature_mc = (eta_s32)(fusion->weighted_mc / fusion->total_weight);
    fusion->valid = 1;
    return ETA_REASON_READY;
}

void eta_fan_policy_reset(struct eta_fan_policy_state *state)
{
    if (state)
        *state = (struct eta_fan_policy_state){0};
}

static eta_u32 settings_equal(const struct eta_fan_settings *left,
    const struct eta_fan_settings *right)
{
    eta_u32 i;

    if (left->enabled != right->enabled || left->start_mc != right->start_mc ||
        left->floor != right->floor || left->ceiling != right->ceiling ||
        left->gain_milli != right->gain_milli)
        return 0;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i)
        if (left->weight[i] != right->weight[i])
            return 0;
    return 1;
}

static enum eta_fan_reason settings_validate(const struct eta_fan_settings *settings,
    enum eta_fan_policy_kind policy)
{
    eta_u32 i, positive = 0;

    if (!flag_known(settings->enabled))
        return ETA_REASON_SETTING_UNKNOWN;
    if (settings->enabled == ETA_FAN_NO)
        return ETA_REASON_SETTING_DISABLED;
    if (settings->start_mc < 20000 || settings->start_mc > 50000 ||
        settings->floor < 1 || settings->floor > ETA_FAN_MAX_LEVEL)
        return ETA_REASON_CONFIG_INVALID;
    if (policy == ETA_POLICY_CHARGE &&
        (settings->gain_milli < 1 || settings->gain_milli > ETA_FAN_GAIN_MAX ||
         settings->ceiling < settings->floor || settings->ceiling > ETA_FAN_MAX_LEVEL))
        return ETA_REASON_CONFIG_INVALID;
    if (!weights_valid(settings->weight))
        return ETA_REASON_WEIGHT_INVALID;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i)
        positive |= settings->weight[i] != 0;
    return positive ? ETA_REASON_READY : ETA_REASON_NO_CONTRIBUTORS;
}

static enum eta_fan_reason permission_check(const struct eta_fan_policy_input *input,
    eta_u32 *mask)
{
    enum eta_fan_flag gates[7];
    eta_u32 i, unknown = 0, blocked = 0;

    if (!flag_known(input->main_enabled))
        return ETA_REASON_SWITCH_UNKNOWN;
    if (input->main_enabled == ETA_FAN_NO)
        return ETA_REASON_SWITCH_OFF;
    if (input->mode == ETA_MODE_UNKNOWN)
        return ETA_REASON_MODE_UNKNOWN;
    if (input->mode != ETA_MODE_AUTO)
        return ETA_REASON_MODE_BLOCKED;
    if (!flag_known(input->auto_enabled))
        return ETA_REASON_AUTO_UNKNOWN;
    if (input->auto_enabled == ETA_FAN_NO)
        return ETA_REASON_AUTO_OFF;
    gates[0] = input->ui_clear;
    gates[1] = input->protection_clear;
    gates[2] = input->abort_clear;
    gates[3] = input->noise_clear;
    gates[4] = input->handle_clear;
    gates[5] = input->hardware_healthy;
    gates[6] = input->common_scene;
    for (i = 0; i < 7; ++i) {
        if (!flag_known(gates[i]))
            unknown |= 1U << i;
        else if (gates[i] != ETA_FAN_YES)
            blocked |= 1U << i;
    }
    *mask = unknown | blocked;
    if (unknown)
        return ETA_REASON_GATE_UNKNOWN;
    if (blocked)
        return ETA_REASON_GATE_BLOCKED;
    return ETA_REASON_READY;
}

static enum eta_fan_reason sample_check(const struct eta_fan_policy_input *input)
{
    eta_u32 i;

    if (input->sample.power != input->power ||
        input->sample.power_epoch != input->power_epoch)
        return ETA_REASON_POWER_SAMPLE_MISMATCH;
    if (input->sample.channel_generation != input->channel_generation)
        return ETA_REASON_CHANNEL_SAMPLE_MISMATCH;
    if (input->sample.at_ms > input->now_ms)
        return ETA_REASON_SAMPLE_FUTURE;
    /* Ordered unsigned subtraction: never wrap even at the ends of u64 time. */
    if (input->now_ms - input->sample.at_ms >= ETA_FAN_FRESH_MS)
        return ETA_REASON_SAMPLE_STALE;
    if ((input->sample.valid_mask & ~ETA_FAN_ALL_CHANNELS) != 0)
        return ETA_REASON_SAMPLE_INVALID;
    if (input->thermal_status_valid != ETA_FAN_YES)
        return ETA_REASON_THERMAL_UNKNOWN;
    if (input->thermal_status != 0)
        return ETA_REASON_THERMAL_BLOCKED;
    if (input->sample.valid_mask != ETA_FAN_ALL_CHANNELS)
        return ETA_REASON_TEMPERATURE_MISSING;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i)
        if (!temperature_valid(input->sample.temperature_mc[i]))
            return ETA_REASON_TEMPERATURE_INVALID;
    for (i = 0; i < ETA_FAN_CHANNELS; ++i) {
        eta_s32 limit = i == ETA_FAN_BATTERY ? 45000 : 55000;

        if (input->sample.temperature_mc[i] >= limit)
            return ETA_REASON_TEMPERATURE_GUARD;
    }
    return ETA_REASON_READY;
}

static void policy_exit(struct eta_fan_policy_state *state,
    struct eta_fan_policy_output *output, enum eta_fan_reason reason)
{
    eta_fan_policy_reset(state);
    output->reason = reason;
    output->target = 0;
    output->has_target = 0;
    output->active = 0;
    output->cooling = 0;
    output->hard_exit = 1;
}

/* OEM ordinary/default step thresholds only, applied to OUR four-channel
 * fusion, not ShellTemp/KP/power or the game table. Source: target horae
 * fan_auto_control_model.stepwise_config.default and decomp2.txt:737-791.
 * Keep the step latch independent of both the configured floor and ramp.
 */
static eta_u32 daily_level(struct eta_fan_policy_state *state,
    const struct eta_fan_settings *settings, const struct eta_fan_fusion *fusion)
{
    static const eta_u32 trigger_mc[] = {35000, 37000, 39000, 40000, 41000, 43000};
    static const eta_u32 clear_mc[] = {33000, 35000, 37000, 39000, 40000, 41000};
    static const eta_u32 level[] = {6, 10, 14, 16, 18, 20};
    const eta_u32 count = (eta_u32)(sizeof(level) / sizeof(level[0]));
    eta_u32 step = state->daily_step, target;

    /* Choose one direction per sample, allowing multiple steps within it.
     * Never re-run ascent after descent: some clear values equal an earlier
     * trigger. Compare the exact rational mean, not displayed/truncated mC.
     */
    if (step < count && fusion->weighted_mc >=
        (eta_u64)trigger_mc[step] * fusion->total_weight) {
        do {
            ++step;
        } while (step < count && fusion->weighted_mc >=
            (eta_u64)trigger_mc[step] * fusion->total_weight);
    } else {
        while (step > 0 && fusion->weighted_mc <=
            (eta_u64)clear_mc[step - 1U] * fusion->total_weight)
            --step;
    }
    state->daily_step = step;
    target = step ? level[step - 1U] : 0;
    return target > settings->floor ? target : settings->floor;
}

static eta_u32 charge_level(const struct eta_fan_settings *settings,
    const struct eta_fan_fusion *fusion)
{
    eta_u64 threshold = (eta_u64)settings->start_mc * fusion->total_weight;
    eta_u64 denominator, increment;

    /* Anything below start rounds/clamps back to floor while latched. */
    if (fusion->weighted_mc <= threshold)
        return settings->floor;
    denominator = fusion->total_weight * 1000000U;
    /* raw = floor + gain_milli/1000 * delta_mC/1000.
     * For a positive increment Java Math.round is floor(increment + 1/2).
     * Calculate using the EXACT weighted mean, without intermediate mC loss.
     * Worst product <= 4*100000000*65000*8000 = 208000000000000000;
     * + denominator/2 <= 200000000000000. Both safely fit unsigned AND s64.
     */
    increment = ((fusion->weighted_mc - threshold) * settings->gain_milli +
        denominator / 2U) / denominator;
    if (increment >= (eta_u64)(settings->ceiling - settings->floor))
        return settings->ceiling;
    return settings->floor + (eta_u32)increment;
}

void eta_fan_policy_step(struct eta_fan_policy_state *state,
    const struct eta_fan_config *config, const struct eta_fan_policy_input *input,
    struct eta_fan_policy_output *output)
{
    const struct eta_fan_settings *settings;
    enum eta_fan_reason reason;
    eta_u64 start, stop;
    eta_u32 cool_condition;

    if (!output)
        return;
    *output = (struct eta_fan_policy_output){0};
    if (!state || !input) {
        policy_exit(state, output, ETA_REASON_ARGUMENT_INVALID);
        return;
    }
    if (input->power == ETA_POWER_BATTERY)
        output->policy = ETA_POLICY_DAILY;
    else if (input->power == ETA_POWER_CHARGING)
        output->policy = ETA_POLICY_CHARGE;
    else {
        policy_exit(state, output, input->power == ETA_POWER_EXTERNAL_IDLE ?
            ETA_REASON_EXTERNAL_IDLE : ETA_REASON_POWER_UNKNOWN);
        return;
    }
    reason = permission_check(input, &output->gate_mask);
    if (reason != ETA_REASON_READY) {
        policy_exit(state, output, reason);
        return;
    }
    if (!config) {
        policy_exit(state, output, ETA_REASON_CONFIG_UNAVAILABLE);
        return;
    }
    settings = output->policy == ETA_POLICY_DAILY ? &config->daily : &config->charge;
    reason = settings_validate(settings, output->policy);
    if (reason != ETA_REASON_READY) {
        policy_exit(state, output, reason);
        return;
    }
    reason = sample_check(input);
    if (reason != ETA_REASON_READY) {
        policy_exit(state, output, reason);
        return;
    }
    reason = eta_fan_fuse(settings->weight, &input->sample, &output->fusion);
    if (reason != ETA_REASON_READY) {
        policy_exit(state, output, reason);
        return;
    }
    if (state->context_valid) {
        if (state->power != input->power || state->power_epoch != input->power_epoch)
            output->reset_flags |= ETA_RESET_POWER;
        if (state->config_version != config->version ||
            !settings_equal(&state->settings, settings))
            output->reset_flags |= ETA_RESET_CONFIG;
        if (state->channel_generation != input->channel_generation ||
            state->contributor_mask != output->fusion.contributor_mask)
            output->reset_flags |= ETA_RESET_CHANNELS;
        if (output->reset_flags) {
            policy_exit(state, output, ETA_REASON_CONTEXT_CHANGED);
            return;
        }
        if (input->now_ms < state->last_now_ms || input->sample.at_ms < state->last_sample_ms) {
            policy_exit(state, output, ETA_REASON_TIME_REVERSED);
            return;
        }
        if (input->now_ms - state->last_now_ms >= ETA_FAN_FRESH_MS ||
            input->sample.at_ms - state->last_sample_ms >= ETA_FAN_FRESH_MS) {
            policy_exit(state, output, ETA_REASON_SAMPLE_GAP);
            return;
        }
    }
    start = (eta_u64)settings->start_mc * output->fusion.total_weight;
    stop = (eta_u64)(settings->start_mc - 1000) * output->fusion.total_weight;
    if (!state->active) {
        state->active = output->policy == ETA_POLICY_DAILY ?
            output->fusion.weighted_mc > start : output->fusion.weighted_mc >= start;
        state->cooling = 0;
    } else {
        cool_condition = output->policy == ETA_POLICY_DAILY ?
            output->fusion.weighted_mc <= stop : output->fusion.weighted_mc < stop;
        if (cool_condition) {
            if (!state->cooling) {
                state->cooling = 1;
                state->cooling_since_ms = input->sample.at_ms;
            }
            if (input->sample.at_ms - state->cooling_since_ms >= ETA_FAN_COOL_MS) {
                state->active = 0;
                state->cooling = 0;
            }
        } else {
            state->cooling = 0;
        }
    }
    state->context_valid = 1;
    state->power = input->power;
    state->power_epoch = input->power_epoch;
    state->channel_generation = input->channel_generation;
    state->config_version = config->version;
    state->settings = *settings;
    state->contributor_mask = output->fusion.contributor_mask;
    state->last_sample_ms = input->sample.at_ms;
    state->last_now_ms = input->now_ms;
    output->has_target = 1;
    output->active = state->active;
    output->cooling = state->cooling;
    if (!state->active) {
        state->daily_step = 0;
        output->target = 0;
    } else {
        output->target = output->policy == ETA_POLICY_DAILY ?
            daily_level(state, settings, &output->fusion) :
            charge_level(settings, &output->fusion);
    }
    output->reason = !state->active ? ETA_REASON_BELOW_THRESHOLD :
        state->cooling ? ETA_REASON_COOLING : ETA_REASON_READY;
}

const char *eta_fan_reason_name(enum eta_fan_reason reason)
{
    switch (reason) {
    case ETA_REASON_READY: return "ready";
    case ETA_REASON_COOLING: return "cooling";
    case ETA_REASON_BELOW_THRESHOLD: return "below_threshold";
    case ETA_REASON_ARGUMENT_INVALID: return "argument_invalid";
    case ETA_REASON_POWER_UNKNOWN: return "power_unknown";
    case ETA_REASON_EXTERNAL_IDLE: return "external_idle";
    case ETA_REASON_POWER_SAMPLE_MISMATCH: return "power_sample_mismatch";
    case ETA_REASON_CHANNEL_SAMPLE_MISMATCH: return "channel_sample_mismatch";
    case ETA_REASON_SWITCH_UNKNOWN: return "switch_unknown";
    case ETA_REASON_SWITCH_OFF: return "switch_off";
    case ETA_REASON_MODE_UNKNOWN: return "mode_unknown";
    case ETA_REASON_MODE_BLOCKED: return "mode_blocked";
    case ETA_REASON_AUTO_UNKNOWN: return "auto_unknown";
    case ETA_REASON_AUTO_OFF: return "auto_off";
    case ETA_REASON_GATE_UNKNOWN: return "gate_unknown";
    case ETA_REASON_GATE_BLOCKED: return "gate_blocked";
    case ETA_REASON_CONFIG_UNAVAILABLE: return "config_unavailable";
    case ETA_REASON_CONFIG_INVALID: return "config_invalid";
    case ETA_REASON_SETTING_UNKNOWN: return "setting_unknown";
    case ETA_REASON_SETTING_DISABLED: return "setting_disabled";
    case ETA_REASON_THERMAL_UNKNOWN: return "thermal_unknown";
    case ETA_REASON_THERMAL_BLOCKED: return "thermal_blocked";
    case ETA_REASON_SAMPLE_FUTURE: return "sample_future";
    case ETA_REASON_SAMPLE_STALE: return "sample_stale";
    case ETA_REASON_SAMPLE_INVALID: return "sample_invalid";
    case ETA_REASON_TEMPERATURE_MISSING: return "temperature_missing";
    case ETA_REASON_TEMPERATURE_INVALID: return "temperature_invalid";
    case ETA_REASON_TEMPERATURE_GUARD: return "temperature_guard";
    case ETA_REASON_WEIGHT_INVALID: return "weight_invalid";
    case ETA_REASON_NO_CONTRIBUTORS: return "no_contributors";
    case ETA_REASON_CONTEXT_CHANGED: return "context_changed";
    case ETA_REASON_TIME_REVERSED: return "time_reversed";
    case ETA_REASON_SAMPLE_GAP: return "sample_gap";
    default: return "unknown_reason";
    }
}

static eta_u32 clamp_level(eta_s32 level)
{
    if (level <= 0)
        return 0;
    if (level > (eta_s32)ETA_FAN_MAX_LEVEL)
        return ETA_FAN_MAX_LEVEL;
    return (eta_u32)level;
}

void eta_fan_normal_reset(struct eta_fan_ramp_state *state)
{
    if (state)
        *state = (struct eta_fan_ramp_state){0};
}

void eta_fan_normal_seed(struct eta_fan_ramp_state *state,
    eta_s32 requested_level, eta_u64 now_ms)
{
    if (!state)
        return;
    state->initialized = 1;
    state->requested_level = clamp_level(requested_level);
    state->last_change_seconds = now_ms / 1000U;
    state->last_call_ms = now_ms;
}

static void ramp_exit(struct eta_fan_ramp_state *state,
    struct eta_fan_ramp_output *output, enum eta_fan_ramp_reason reason)
{
    output->output_changed = state && state->requested_level != 0;
    output->requested_level = 0;
    output->release = 1;
    output->reason = reason;
    eta_fan_normal_reset(state);
}

void eta_fan_normal_step(struct eta_fan_ramp_state *state,
    const struct eta_fan_ramp_input *input, struct eta_fan_ramp_output *output)
{
    eta_u32 before, wanted, rate_target, next;
    eta_u64 seconds, elapsed, delta;

    if (!output)
        return;
    *output = (struct eta_fan_ramp_output){0};
    if (!state || !input) {
        ramp_exit(state, output, ETA_RAMP_ARGUMENT_INVALID);
        return;
    }
    if (input->hard_exit) {
        ramp_exit(state, output, ETA_RAMP_HARD_EXIT);
        return;
    }
    if (state->initialized && input->now_ms < state->last_call_ms) {
        ramp_exit(state, output, ETA_RAMP_TIME_REVERSED);
        return;
    }
    if (state->initialized && input->now_ms - state->last_call_ms >= ETA_FAN_FRESH_MS) {
        ramp_exit(state, output, ETA_RAMP_SAMPLE_GAP);
        return;
    }
    seconds = input->now_ms / 1000U;
    wanted = clamp_level(input->target);
    if (wanted && wanted < ETA_FAN_NORMAL_MIN)
        wanted = ETA_FAN_NORMAL_MIN;
    before = state->requested_level;
    next = before;
    output->reason = ETA_RAMP_STEADY;
    if (before == 0 && wanted > 0) {
        next = ETA_FAN_NORMAL_MIN;
        output->direction = ETA_DIRECTION_UP;
        output->reason = ETA_RAMP_STARTED;
        state->last_change_seconds = seconds;
    } else if (wanted == 0 && before <= ETA_FAN_NORMAL_MIN) {
        next = 0;
        output->reason = before ? ETA_RAMP_STOPPED : ETA_RAMP_STEADY;
        output->direction = before ? ETA_DIRECTION_DOWN : ETA_DIRECTION_HOLD;
        state->last_change_seconds = seconds;
    } else {
        rate_target = wanted == 0 ? ETA_FAN_NORMAL_MIN : wanted;
        if (rate_target == before) {
            /* Equal target consumes idle time, but suggests NO output write. */
            state->last_change_seconds = seconds;
        } else {
            output->direction = rate_target > before ? ETA_DIRECTION_UP : ETA_DIRECTION_DOWN;
            elapsed = seconds - state->last_change_seconds;
            if (elapsed < 1)
                elapsed = 1;
            delta = elapsed / (wanted > before ? 5U : 10U);
            if (delta == 0) {
                output->reason = ETA_RAMP_PENDING;
                /* No timestamp change: integer seconds accumulate until a step. */
            } else {
                if (rate_target > before) {
                    next = delta >= (eta_u64)(rate_target - before) ? rate_target :
                        before + (eta_u32)delta;
                } else {
                    next = delta >= (eta_u64)(before - rate_target) ? rate_target :
                        before - (eta_u32)delta;
                }
                state->last_change_seconds = seconds;
                output->reason = ETA_RAMP_MOVED;
            }
        }
    }
    state->initialized = 1;
    state->requested_level = next;
    state->last_call_ms = input->now_ms;
    output->requested_level = next;
    output->output_changed = next != before;
}
