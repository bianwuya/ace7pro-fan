/* One-shot, read-only kernel observer. NO fan/PWM control, registration,
 * persistent resources, worker/timer/sysfs/proc, or calls into the OEM module.
 * Returning -EAGAIN deliberately makes init fail and releases this module.
 */
#include "../driver-adapter/target_abi.h"
#include "../policy_core.h"
extern int _printk_deferred(const char *format,...);
#define OBS "\0016ETA_FAN_OBSERVER_V1 "
#define ITERATIONS 3U

static struct eta_fan_config observer_config(void)
{
    struct eta_fan_config c={0};
    c.version=1;
    c.daily.enabled=ETA_FAN_YES;c.daily.start_mc=36000;c.daily.floor=10;
    c.daily.weight[ETA_FAN_BATTERY]=60000000;c.daily.weight[ETA_FAN_SURROUND]=40000000;
    c.charge.enabled=ETA_FAN_YES;c.charge.start_mc=33000;c.charge.floor=6;
    c.charge.gain_milli=2000;c.charge.ceiling=20;
    c.charge.weight[ETA_FAN_BATTERY]=ETA_FAN_WEIGHT_UNIT;
    return c;
}
static void observer_gates(struct eta_fan_policy_input *in)
{
    in->main_enabled=ETA_FAN_YES;in->mode=ETA_MODE_AUTO;in->auto_enabled=ETA_FAN_YES;
    in->ui_clear=ETA_FAN_YES;in->protection_clear=ETA_FAN_YES;in->abort_clear=ETA_FAN_YES;
    in->noise_clear=ETA_FAN_YES;in->handle_clear=ETA_FAN_YES;in->hardware_healthy=ETA_FAN_YES;
    in->common_scene=ETA_FAN_YES;in->thermal_status_valid=ETA_FAN_YES;in->thermal_status=0;
    in->power_epoch=1;in->channel_generation=1;
}
int __attribute__((section(".init.text"))) init_module(void)
{
    static const char *const zones[4]={"battery","board_temp","fan_temp","svooc_mos_btb_usr"};
    struct eta_fan_config config=observer_config();
    struct eta_fan_policy_state policy[2]={{0}};
    struct eta_fan_ramp_state ramp[2]={{0}};
    unsigned int iteration,channel,scenario,errors=0;
    long long begin=ktime_get();
    _printk_deferred(OBS "BEGIN observe_only=1 gates=synthetic power=synthetic iterations=%u no_actuation=1 init_will_return=-11\n",ITERATIONS);
    _printk_deferred(OBS "CONFIG daily_start_mc=36000 daily_floor=10 daily_weights=60:0:40:0 charge_start_mc=33000 charge_floor=6 charge_gain_milli=2000 charge_ceiling=20 charge_weights=1:0:0:0 charge_other_settings=fixture_not_live_settings\n");
    for(iteration=0;iteration<ITERATIONS;++iteration) {
        struct eta_fan_policy_input input={0};
        long long started=ktime_get();
        input.sample.at_ms=(eta_u64)started/1000000ULL;
        for(channel=0;channel<4;++channel) {
            struct thermal_zone_device *tz=thermal_zone_get_zone_by_name(zones[channel]);
            long error=0;int temperature;
            if(!tz) error=-19;
            else if((unsigned long)tz>=(unsigned long)-4095L) error=(long)tz;
            else error=thermal_zone_get_temp(tz,&temperature);
            if(error) {
                ++errors;
                _printk_deferred(OBS "SENSOR iteration=%u channel=%u name=%s valid=0 errno=%ld\n",iteration,channel,zones[channel],error);
            } else {
                input.sample.valid_mask|=1U<<channel;
                input.sample.temperature_mc[channel]=temperature;
                _printk_deferred(OBS "SENSOR iteration=%u channel=%u name=%s valid=1 temp_mc=%d source=thermal_api_not_claimed_silicon_raw\n",iteration,channel,zones[channel],temperature);
            }
        }
        input.now_ms=(eta_u64)ktime_get()/1000000ULL;observer_gates(&input);
        for(scenario=0;scenario<2;++scenario) {
            struct eta_fan_policy_output output={0};
            struct eta_fan_ramp_input ri={0};struct eta_fan_ramp_output ro={0};
            input.power=scenario?ETA_POWER_CHARGING:ETA_POWER_BATTERY;
            input.sample.power=input.power;input.sample.power_epoch=1;input.sample.channel_generation=1;
            eta_fan_policy_step(&policy[scenario],&config,&input,&output);
            ri.now_ms=input.now_ms;ri.target=(eta_s32)output.target;ri.hard_exit=output.hard_exit || !output.has_target;
            eta_fan_normal_step(&ramp[scenario],&ri,&ro);
            _printk_deferred(OBS "ALGORITHM iteration=%u scenario=%s observe_only=1 gates=synthetic power=synthetic valid_mask=%u has_target=%u target=%u output=%u active=%u cooling=%u hard_exit=%u release=%u reason=%u ramp_reason=%u fusion_mc=%d now_ms=%llu iteration_elapsed_ns=%lld\n",
                iteration,scenario?"charge":"daily",input.sample.valid_mask,output.has_target,output.target,ro.requested_level,output.active,output.cooling,output.hard_exit,ro.release,(unsigned int)output.reason,(unsigned int)ro.reason,output.fusion.temperature_mc,(unsigned long long)input.now_ms,ktime_get()-started);
        }
    }
    _printk_deferred(OBS "SELFTEST_COMPLETE iterations=%u sensor_errors=%u elapsed_ns=%lld return_errno=-11 observe_only=1 control_writes=0 async_resources=0\n",ITERATIONS,errors,ktime_get()-begin);
    return -11;
}
void __attribute__((section(".exit.text"))) cleanup_module(void) { }
