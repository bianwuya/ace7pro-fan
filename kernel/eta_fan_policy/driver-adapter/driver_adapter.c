/* Target-pinned NONRELEASE adapter. Offline candidate only; never auto-enable.
 * Original private fan state is opaque. All actuation uses original callbacks.
 */
#include "target_abi.h"
#include "config_protocol.h"
#ifndef ETA_FAN_CANDIDATE_LABEL
#define ETA_FAN_CANDIDATE_LABEL "NONRELEASE_UNLOADED"
#endif
#define ETA_SAMPLE_PERIOD_MS 1000U

extern int eta_original_oplus_fan_probe(struct platform_device *);
extern void eta_original_oplus_fan_remove(struct platform_device *);
extern void eta_original_oplus_fan_shutdown(struct platform_device *);
extern int eta_original_oplus_fan_suspend(struct device *);
extern int eta_original_oplus_fan_resume(struct device *);
extern struct attribute_group eta_original_oplus_fan_attrs_group;
#define ETA_SHOW(N) extern ssize_t eta_original_##N(struct device *,struct device_attribute *,char *)
#define ETA_STORE(N) extern ssize_t eta_original_##N(struct device *,struct device_attribute *,const char *,size_t)
ETA_SHOW(state_show); ETA_SHOW(level_show); ETA_SHOW(level_eff_show); ETA_SHOW(status_show); ETA_SHOW(present_show);
ETA_STORE(level_store); ETA_STORE(state_store);

struct eta_adapter_state {
    struct mutex lock;
    struct platform_device *pdev;
    struct device *led;
    struct device_attribute *level,*state,*effective,*status,*present;
    struct workqueue_struct *wq;
    struct delayed_work work;
    struct eta_adapter_config config;
    struct eta_fan_policy_state policy;
    struct eta_fan_ramp_state ramp;
    struct eta_fan_policy_output output;
    /* Read-only display evidence, never a control permission or target. */
    struct eta_fan_fusion telemetry_fusion;
    eta_u64 deadline,power_epoch,channel_generation,sample_at_ms,sample_completed_ms;
    enum eta_fan_power power;
    unsigned int live,blocked,group_added,owned,last_request,last_enabled,probing;
    unsigned int saved_oem_request,saved_oem_enabled,ready,temp_mask;
    unsigned int source_present_mask,source_online_mask,source_failed_mask,power_sample_valid;
    unsigned int readback_mask;
    int fault,last_effective,physical_request,physical_state,battery_status,battery_present;
    int temperature_mc[ETA_FAN_CHANNELS];
    char readback[4096];
};
static struct eta_adapter_state eta_state;
/* A CAS guards one initialized mutex/single target instance, never a guessed
 * kernel spinlock/atomic API. Compile -mno-outline-atomics; no new import. */
static unsigned int eta_instance;
/* Monotonic within this module instance, including PM and device rebind. */
static eta_u64 eta_owner_epoch;
static const struct attribute_group eta_policy_group;
static void eta_policy_work(struct work_struct *work);
static void eta_adapter_cleanup(void *opaque);

static eta_u64 eta_now(void) { return (eta_u64)ktime_get()/1000000ULL; }
static int eta_equal(const char *a,const char *b)
{
    unsigned int i=0; while(a[i] && a[i]==b[i]) ++i; return a[i]==b[i];
}
static int eta_equal_bytes(const void *a,const void *b,size_t n)
{
    const unsigned char *x=a,*y=b; size_t i;
    for(i=0;i<n;++i) if(x[i]!=y[i]) return 0;
    return 1;
}
static int eta_pointer_error(const void *p) { return !p || (unsigned long)p>=(unsigned long)-4095L; }
static struct device_attribute *eta_find_attribute(const struct attribute_group *group,const char *name)
{
    unsigned int i;
    if(!group || !group->attrs) return NULL;
    /* Original identity pins 16-pointer array including its terminator. */
    for(i=0;i<16 && group->attrs[i];++i)
        if(eta_equal(group->attrs[i]->name,name)) return (struct device_attribute *)group->attrs[i];
    return NULL;
}
static int eta_read_integer(ssize_t (*show)(struct device *,struct device_attribute *,char *),struct device_attribute *attr,int *out)
{
    ssize_t n; unsigned int i=0,value=0;
    if(!eta_state.led || !attr) return -19;
    n=show(eta_state.led,attr,eta_state.readback);
    if(n<1 || n>16) return -5;
    while(i<(unsigned int)n && eta_state.readback[i]>='0' && eta_state.readback[i]<='9') {
        value=value*10U+(unsigned int)(eta_state.readback[i++]-'0'); if(value>1000) return -5;
    }
    if(!i || (i!=(unsigned int)n && !(i+1==(unsigned int)n && eta_state.readback[i]=='\n'))) return -5;
    *out=(int)value; return 0;
}
static int eta_hardware_healthy(void)
{
    int present=0; ssize_t n;
    if(eta_read_integer(eta_original_present_show,eta_state.present,&present) || present!=1) return 0;
    n=eta_original_status_show(eta_state.led,eta_state.status,eta_state.readback);
    /* Exact original table index 0, not a guessed numeric/private field. */
    return n==7 && eta_equal(eta_state.readback,"NORMAL\n");
}
static int eta_original_request(unsigned int value)
{
    char b[4];size_t n;
    if(value>20) return -22;
    if(value>=10) { b[0]=(char)('0'+value/10);b[1]=(char)('0'+value%10);b[2]='\n';n=3; }
    else { b[0]=(char)('0'+value);b[1]='\n';n=2; }
    return eta_original_level_store(eta_state.led,eta_state.level,b,n)==(ssize_t)n ? 0 : -5;
}
static int eta_original_enable(unsigned int value)
{
    const char *b=value?"1\n":"0\n";
    return eta_original_state_store(eta_state.led,eta_state.state,b,2)==2 ? 0 : -5;
}
static void eta_owned_locked(unsigned int owned)
{
    if(eta_state.owned!=owned) { eta_state.owned=owned;++eta_owner_epoch; }
}
static void eta_release_locked(void)
{
    if(eta_state.owned) {
        /* Do this once while we still own the output; never stop a subsequent
         * manual/OEM owner. A full-owner zero state needs no redundant stop. */
        if(eta_state.last_enabled && eta_original_enable(0)) eta_state.fault=-5;
        eta_owned_locked(0);eta_state.last_enabled=0;
    }
    eta_state.ready=0;
    eta_fan_normal_reset(&eta_state.ramp);
}
static void eta_invalidate_sample_locked(void)
{
    unsigned int i;
    eta_state.temp_mask=0;eta_state.power_sample_valid=0;
    eta_state.sample_at_ms=eta_state.sample_completed_ms=0;
    eta_state.power=ETA_POWER_UNKNOWN;
    eta_state.source_present_mask=eta_state.source_online_mask=eta_state.source_failed_mask=0;
    eta_state.battery_status=eta_state.battery_present=-1;
    eta_state.telemetry_fusion=(struct eta_fan_fusion){0};
    for(i=0;i<ETA_FAN_CHANNELS;++i) eta_state.temperature_mc[i]=-1;
}
static void eta_refresh_readback_locked(void)
{
    eta_state.readback_mask=0;
    eta_state.physical_request=eta_state.physical_state=eta_state.last_effective=-1;
    if(!eta_state.led) return;
    if(!eta_read_integer(eta_original_level_show,eta_state.level,&eta_state.physical_request) &&
       eta_state.physical_request>=0 && eta_state.physical_request<=20) eta_state.readback_mask|=1;
    else eta_state.physical_request=-1;
    if(!eta_read_integer(eta_original_state_show,eta_state.state,&eta_state.physical_state) &&
       eta_state.physical_state>=0 && eta_state.physical_state<=1) eta_state.readback_mask|=2;
    else eta_state.physical_state=-1;
    /* Effective level is diagnostic only, never feeds target or compensation. */
    if(!eta_read_integer(eta_original_level_eff_show,eta_state.effective,&eta_state.last_effective) &&
       eta_state.last_effective>=0 && eta_state.last_effective<=20) eta_state.readback_mask|=4;
    else eta_state.last_effective=-1;
}
static enum eta_fan_power eta_power_read(void)
{
    static const char *const sources[3]={"usb","ac","wireless"};
    struct power_supply *battery=power_supply_get_by_name("battery");
    union power_supply_propval value={0};
    unsigned int i;int ok=1;
    enum eta_fan_power p=ETA_POWER_UNKNOWN;
    eta_state.source_present_mask=eta_state.source_online_mask=eta_state.source_failed_mask=0;
    eta_state.battery_status=eta_state.battery_present=-1;
    if(eta_pointer_error(battery)) ok=0;
    else {
        if(power_supply_get_property(battery,POWER_SUPPLY_PROP_STATUS,&value)) ok=0;
        else eta_state.battery_status=value.intval;
        if(power_supply_get_property(battery,POWER_SUPPLY_PROP_PRESENT,&value)) ok=0;
        else eta_state.battery_present=value.intval;
        power_supply_put(battery);
    }
    for(i=0;i<3;++i) {
        struct power_supply *source=power_supply_get_by_name(sources[i]);
        if(!source) { if(i==0) ok=0;continue; } /* usb required; optional NULL != failure */
        if(eta_pointer_error(source)) { eta_state.source_failed_mask|=1U<<i;ok=0;continue; }
        eta_state.source_present_mask|=1U<<i;
        if(power_supply_get_property(source,POWER_SUPPLY_PROP_ONLINE,&value) ||
           (value.intval!=0 && value.intval!=1)) {
            eta_state.source_failed_mask|=1U<<i;ok=0;
        } else if(value.intval) eta_state.source_online_mask|=1U<<i;
        power_supply_put(source);
    }
    if(ok && eta_state.battery_present==1) {
        int status=eta_state.battery_status;
        if(status==2 && !eta_state.source_online_mask) p=ETA_POWER_BATTERY;
        else if(status==1 && eta_state.source_online_mask) p=ETA_POWER_CHARGING;
        else if((status==3 || status==4) && eta_state.source_online_mask) p=ETA_POWER_EXTERNAL_IDLE;
    }
    return p;
}
static void eta_sample_locked(struct eta_fan_policy_input *input,eta_u64 now)
{
    unsigned int i,prior_present=eta_state.source_present_mask,prior_online=eta_state.source_online_mask;
    unsigned int prior_failed=eta_state.source_failed_mask;
    int prior_status=eta_state.battery_status,prior_battery_present=eta_state.battery_present;
    enum eta_fan_power power;
    const struct eta_fan_settings *settings=NULL;
    input->main_enabled=(enum eta_fan_flag)eta_state.config.main_enabled;
    input->mode=(enum eta_fan_mode)eta_state.config.mode;
    input->auto_enabled=(enum eta_fan_flag)eta_state.config.auto_enabled;
#define ETA_INPUT_GATE(F,B) input->F=(eta_state.config.gate_mask&(1U<<(B)))?ETA_FAN_YES:ETA_FAN_UNKNOWN
    ETA_INPUT_GATE(ui_clear,0);ETA_INPUT_GATE(protection_clear,1);ETA_INPUT_GATE(abort_clear,2);
    ETA_INPUT_GATE(noise_clear,3);ETA_INPUT_GATE(handle_clear,4);ETA_INPUT_GATE(hardware_healthy,5);ETA_INPUT_GATE(common_scene,6);
#undef ETA_INPUT_GATE
    /* Observe known hardware denial before potentially slow power/thermal reads. */
    if(!eta_hardware_healthy()) { input->hardware_healthy=ETA_FAN_NO;eta_release_locked(); }
    input->thermal_status_valid=eta_state.config.thermal_status_valid?ETA_FAN_YES:ETA_FAN_UNKNOWN;
    input->thermal_status=eta_state.config.thermal_status;
    power=eta_power_read();
    if(!eta_state.power_sample_valid || power!=eta_state.power || prior_present!=eta_state.source_present_mask ||
       prior_online!=eta_state.source_online_mask || prior_failed!=eta_state.source_failed_mask ||
       prior_status!=eta_state.battery_status || prior_battery_present!=eta_state.battery_present) ++eta_state.power_epoch;
    eta_state.power=power;eta_state.power_sample_valid=1;eta_state.temp_mask=0;eta_state.sample_at_ms=now;
    input->now_ms=now;input->power=power;input->power_epoch=eta_state.power_epoch;
    input->channel_generation=eta_state.channel_generation;input->sample.at_ms=now;
    input->sample.power=power;input->sample.power_epoch=eta_state.power_epoch;
    input->sample.channel_generation=eta_state.channel_generation;
    for(i=0;i<4;++i) {
        struct thermal_zone_device *tz=thermal_zone_get_zone_by_name(eta_state.config.zone[i]);int mc;
        eta_state.temperature_mc[i]=-1;
        if(!eta_pointer_error(tz) && !thermal_zone_get_temp(tz,&mc) && mc>=0 && mc<=65000) {
            input->sample.valid_mask|=1U<<i;input->sample.temperature_mc[i]=mc;
            eta_state.temp_mask|=1U<<i;eta_state.temperature_mc[i]=mc;
        }
    }
    /* Keep the original post-sampling hardware guard as well: a slow thermal
     * callback must not hide a protection edge observed before actuation. */
    if(!eta_hardware_healthy()) input->hardware_healthy=ETA_FAN_NO;
    eta_state.telemetry_fusion=(struct eta_fan_fusion){0};
    if(power==ETA_POWER_BATTERY) settings=&eta_state.config.policy.daily;
    else if(power==ETA_POWER_CHARGING) settings=&eta_state.config.policy.charge;
    if(settings) (void)eta_fan_fuse(settings->weight,&input->sample,&eta_state.telemetry_fusion);
}
static void eta_policy_work(struct work_struct *work)
{
    struct eta_fan_policy_input in={0};struct eta_fan_ramp_input ri={0};struct eta_fan_ramp_output ro={0};
    eta_u64 now,remaining;int req=0,enabled=0;
    (void)work;mutex_lock(&eta_state.lock);now=eta_now();
    if(!eta_state.live || eta_state.blocked || !eta_state.config.revision || !eta_state.wq) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);goto done;
    }
    /* A complete validated config authorizes reads, not actuation. Revoke any
     * old owner BEFORE sampling even when the publisher/lease has disappeared. */
    if(eta_state.fault || !eta_adapter_permissions(&eta_state.config,now,eta_state.deadline)) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);
    }
    now=eta_now();eta_sample_locked(&in,now);now=eta_now();in.now_ms=now;eta_state.sample_completed_ms=now;
    if(eta_state.fault || !eta_adapter_permissions(&eta_state.config,now,eta_state.deadline)) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);goto done;
    }
    eta_fan_policy_step(&eta_state.policy,&eta_state.config.policy,&in,&eta_state.output);
    if(eta_state.output.hard_exit || !eta_state.output.has_target) { eta_release_locked();goto requeue; }
    eta_refresh_readback_locked();req=eta_state.physical_request;enabled=eta_state.physical_state;
    if(eta_state.readback_mask!=7) { eta_state.fault=-5;eta_release_locked();goto done; }
    now=eta_now();
    if(!eta_adapter_permissions(&eta_state.config,now,eta_state.deadline)) { eta_release_locked();goto done; }
    /* Compare only the upstream request, never effective/derated output. */
    if(eta_state.owned && ((unsigned int)req!=eta_state.last_request || (unsigned int)enabled!=eta_state.last_enabled)) {
        eta_state.fault=-16;eta_release_locked();goto done;
    }
    eta_state.ready=1;
    if(!eta_state.owned) {
        /* v1/additive alone retains the previous OEM-demand floor. Full-owner
         * owns a valid zero target too; OEM temperature demand is not an input. */
        if(eta_state.config.strategy==ETA_STRATEGY_ADDITIVE &&
           (!eta_state.output.target || (enabled && (unsigned int)req>=eta_state.output.target))) goto requeue;
        eta_state.saved_oem_request=(unsigned int)req;eta_state.saved_oem_enabled=(unsigned int)enabled;
        eta_state.last_request=(unsigned int)req;eta_state.last_enabled=(unsigned int)enabled;
        /* A physical continuity seed, not a minimum/final policy target. */
        eta_fan_normal_seed(&eta_state.ramp,enabled?req:0,now);eta_owned_locked(1);
    }
    ri.now_ms=now;ri.target=(eta_s32)eta_state.output.target;
    if(eta_state.config.strategy==ETA_STRATEGY_ADDITIVE && eta_state.saved_oem_enabled &&
       (unsigned int)ri.target<eta_state.saved_oem_request) ri.target=(eta_s32)eta_state.saved_oem_request;
    eta_fan_normal_step(&eta_state.ramp,&ri,&ro);
    if(ro.release) { eta_release_locked();goto requeue; }
    if(!ro.requested_level) {
        if(eta_state.config.strategy!=ETA_STRATEGY_FULL_OWNER) { eta_release_locked();goto requeue; }
        if(eta_state.last_enabled) {
            if(eta_original_enable(0)) { eta_state.fault=-5;eta_release_locked();goto done; }
            eta_state.last_enabled=0;
        }
        /* Keep full ownership while stopped. The OEM level request may retain
         * its last nonzero value; state=0/output=0 is the real stopped state. */
        goto readback;
    }
    if(ro.requested_level!=eta_state.last_request) {
        if(eta_original_request(ro.requested_level)) { eta_state.fault=-5;eta_release_locked();goto done; }
        eta_state.last_request=ro.requested_level;
    }
    if((ro.requested_level!=0)!=eta_state.last_enabled) {
        if(eta_original_enable(ro.requested_level!=0)) { eta_state.fault=-5;eta_release_locked();goto done; }
        eta_state.last_enabled=ro.requested_level!=0;
    }
    if(eta_state.config.strategy==ETA_STRATEGY_ADDITIVE && eta_state.saved_oem_enabled &&
       eta_state.output.target<=eta_state.saved_oem_request && eta_state.last_request==eta_state.saved_oem_request &&
       eta_state.last_enabled) {
        eta_owned_locked(0);eta_fan_normal_reset(&eta_state.ramp);
    }
readback:
    eta_refresh_readback_locked();
    if(eta_state.readback_mask!=7 || (eta_state.owned &&
       ((unsigned int)eta_state.physical_request!=eta_state.last_request ||
        (unsigned int)eta_state.physical_state!=eta_state.last_enabled))) {
        eta_state.fault=-5;eta_release_locked();goto done;
    }
requeue:
done:
    now=eta_now();
    if(eta_state.fault || !eta_adapter_permissions(&eta_state.config,now,eta_state.deadline)) eta_release_locked();
    if(eta_state.live && !eta_state.blocked && eta_state.config.revision && eta_state.wq) {
        remaining=ETA_SAMPLE_PERIOD_MS;
        /* Wake early only for a still-authorized lease. Expired/disabled
         * control must not create a zero-delay telemetry busy loop. */
        if(!eta_state.fault && eta_adapter_permissions(&eta_state.config,now,eta_state.deadline) &&
           eta_state.deadline-now<remaining) remaining=eta_state.deadline-now;
        queue_delayed_work_on(ETA_WORK_CPU_UNBOUND,eta_state.wq,&eta_state.work,__msecs_to_jiffies((unsigned int)remaining));
    } else eta_release_locked();
    mutex_unlock(&eta_state.lock);
}

ssize_t eta_adapter_level_store(struct device *d,struct device_attribute *a,const char *b,size_t n)
{
    ssize_t result;
    mutex_lock(&eta_state.lock);
    if(d==eta_state.led && eta_state.owned && !eta_adapter_permissions(&eta_state.config,eta_now(),eta_state.deadline)) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);
    }
    result=d==eta_state.led && eta_state.owned ? -16 : eta_original_level_store(d,a,b,n);
    mutex_unlock(&eta_state.lock);return result;
}
ssize_t eta_adapter_state_store(struct device *d,struct device_attribute *a,const char *b,size_t n)
{
    ssize_t result;
    mutex_lock(&eta_state.lock);
    if(d==eta_state.led && eta_state.owned && !eta_adapter_permissions(&eta_state.config,eta_now(),eta_state.deadline)) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);
    }
    result=d==eta_state.led && eta_state.owned ? -16 : eta_original_state_store(d,a,b,n);
    mutex_unlock(&eta_state.lock);return result;
}
static ssize_t eta_config_store(struct device *d,struct device_attribute *a,const char *b,size_t n)
{
    struct eta_adapter_config config;eta_u64 now;int result;
    (void)d;(void)a;if(n>1024) return -22;
    result=eta_adapter_parse_config(b,(eta_u32)n,&config);if(result) return result;
    mutex_lock(&eta_state.lock);now=eta_now();
    if(!eta_state.live || eta_state.blocked || !eta_state.wq) { result=-19;goto done; }
    if(config.revision<eta_state.config.revision ||
       (config.revision==eta_state.config.revision && !eta_equal_bytes(&config,&eta_state.config,sizeof(config)))) { result=-116;goto done; }
    /* A late renewal cannot retroactively bridge an already-expired lease,
     * even when a delayed worker has not yet observed the expiration. */
    if(eta_state.deadline && now>=eta_state.deadline) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);eta_invalidate_sample_locked();
    }
    if(config.revision!=eta_state.config.revision) {
        eta_release_locked();eta_fan_policy_reset(&eta_state.policy);eta_state.fault=0;eta_invalidate_sample_locked();
        if(!eta_equal_bytes(config.zone,eta_state.config.zone,sizeof(config.zone))) ++eta_state.channel_generation;
    }
    eta_state.config=config;eta_state.deadline=now+config.lease_ms;
    if(!eta_adapter_permissions(&config,now,eta_state.deadline)) eta_release_locked();
    mod_delayed_work_on(ETA_WORK_CPU_UNBOUND,eta_state.wq,&eta_state.work,0);result=(int)n;
done:
    mutex_unlock(&eta_state.lock);return result;
}
static ssize_t eta_status_show(struct device *d,struct device_attribute *a,char *b)
{
    int result,ready,target,output=-1,fusion_valid,fusion_mc,demand,policy_kind,cooling,telemetry_valid;
    eta_u64 now;long long sample_age=-1,sample_duration=-1;
    (void)d;(void)a;mutex_lock(&eta_state.lock);now=eta_now();
    if(eta_state.live && !eta_state.blocked) {
        eta_refresh_readback_locked();
        if(eta_state.owned && eta_state.readback_mask!=7) {
            eta_state.fault=-5;eta_release_locked();
            /* Best effort readback after one fail-safe stop; no retry writes. */
            eta_refresh_readback_locked();
        }
    } else { eta_state.readback_mask=0;eta_state.physical_request=eta_state.physical_state=eta_state.last_effective=-1; }
    now=eta_now();
    if(eta_state.power_sample_valid && now>=eta_state.sample_at_ms) sample_age=(long long)(now-eta_state.sample_at_ms);
    if(eta_state.power_sample_valid && eta_state.sample_completed_ms>=eta_state.sample_at_ms)
        sample_duration=(long long)(eta_state.sample_completed_ms-eta_state.sample_at_ms);
    ready=eta_state.ready && eta_state.live && !eta_state.blocked && !eta_state.fault &&
        eta_adapter_permissions(&eta_state.config,now,eta_state.deadline) &&
        eta_state.temp_mask==15 && now>=eta_state.sample_at_ms && now-eta_state.sample_at_ms<ETA_FAN_FRESH_MS &&
        eta_state.output.has_target && !eta_state.output.hard_exit && eta_state.readback_mask==7 &&
        (!eta_state.owned || ((unsigned int)eta_state.physical_request==eta_state.last_request &&
                              (unsigned int)eta_state.physical_state==eta_state.last_enabled));
    target=ready?(int)eta_state.output.target:-1;
    fusion_valid=ready && eta_state.output.fusion.valid;
    fusion_mc=fusion_valid?eta_state.output.fusion.temperature_mc:-1;
    telemetry_valid=eta_state.live && !eta_state.blocked && eta_state.power_sample_valid &&
        eta_state.sample_at_ms>0 && now>=eta_state.sample_completed_ms &&
        eta_state.sample_completed_ms>=eta_state.sample_at_ms && now-eta_state.sample_at_ms<ETA_FAN_FRESH_MS &&
        eta_state.telemetry_fusion.valid;
    demand=target;policy_kind=ready?(int)eta_state.output.policy:0;cooling=ready?(int)eta_state.output.cooling:0;
    if((eta_state.readback_mask&3)==3) output=eta_state.physical_state?eta_state.physical_request:0;
    result=scnprintf(b,4096,"NONRELEASE protocol=2 configProtocol=%u live=%u blocked=%u configured=%u owned=%u fault=%d leaseUntil=%llu configRevision=%llu ownerEpoch=%llu strategy=%u ready=%d target=%d output=%d requestedLevel=%d physicalState=%d effective=%d readbackMask=%u tempValidMask=%u temp0=%d temp1=%d temp2=%d temp3=%d sampleAtMs=%llu nowMs=%llu power=%u powerEpoch=%llu sourcePresentMask=%u sourceOnlineMask=%u sourceFailedMask=%u batteryStatus=%d batteryPresent=%d reason=%u fusionValid=%d fusionMc=%d demand=%d policyKind=%d cooling=%d samplePeriodMs=%u sampleAgeMs=%lld sampleCompletedAtMs=%llu sampleDurationMs=%lld sampleFreshMs=%u telemetryFusionValid=%d telemetryFusionMc=%d telemetryContributorMask=%u candidateLabel=" ETA_FAN_CANDIDATE_LABEL "\n",
        eta_state.config.protocol_version,eta_state.live,eta_state.blocked,eta_state.config.enabled,eta_state.owned,eta_state.fault,
        (unsigned long long)eta_state.deadline,(unsigned long long)eta_state.config.revision,(unsigned long long)eta_owner_epoch,
        eta_state.config.strategy,ready,target,output,eta_state.physical_request,eta_state.physical_state,eta_state.last_effective,
        eta_state.readback_mask,eta_state.temp_mask,eta_state.temperature_mc[0],eta_state.temperature_mc[1],eta_state.temperature_mc[2],eta_state.temperature_mc[3],
        (unsigned long long)eta_state.sample_at_ms,(unsigned long long)now,(unsigned int)eta_state.power,(unsigned long long)eta_state.power_epoch,
        eta_state.source_present_mask,eta_state.source_online_mask,eta_state.source_failed_mask,eta_state.battery_status,eta_state.battery_present,(unsigned int)eta_state.output.reason,
        fusion_valid,fusion_mc,demand,policy_kind,cooling,ETA_SAMPLE_PERIOD_MS,sample_age,
        (unsigned long long)eta_state.sample_completed_ms,sample_duration,ETA_FAN_FRESH_MS,telemetry_valid,
        telemetry_valid?eta_state.telemetry_fusion.temperature_mc:-1,
        telemetry_valid?eta_state.telemetry_fusion.contributor_mask:0);
    mutex_unlock(&eta_state.lock);return result;
}
static ssize_t eta_config_snapshot_show(struct device *d,struct device_attribute *a,char *b)
{
    const struct eta_adapter_config *c=&eta_state.config;const struct eta_fan_settings *daily=&c->policy.daily,*charge=&c->policy.charge;
    int n;(void)d;(void)a;mutex_lock(&eta_state.lock);
    if(!c->revision) n=scnprintf(b,4096,"UNCONFIGURED protocol=2 strategy=0 enabled=0\n");
    else {
        n=scnprintf(b,4096,"%u %llu %u %u %u %u %u %u %u %d %u %d %u %u %u %u %u %u %d %u %u %u %u %u %u %u",
            c->protocol_version,(unsigned long long)c->revision,c->enabled,c->lease_ms,c->main_enabled,c->mode,c->auto_enabled,c->gate_mask,c->thermal_status_valid,c->thermal_status,
            (unsigned int)daily->enabled,daily->start_mc,daily->floor,daily->weight[0],daily->weight[1],daily->weight[2],daily->weight[3],
            (unsigned int)charge->enabled,charge->start_mc,charge->floor,charge->gain_milli,charge->ceiling,charge->weight[0],charge->weight[1],charge->weight[2],charge->weight[3]);
        if(c->protocol_version==2) n+=scnprintf(b+n,4096-(size_t)n," %u",c->strategy);
        n+=scnprintf(b+n,4096-(size_t)n," %s %s %s %s\n",c->zone[0],c->zone[1],c->zone[2],c->zone[3]);
    }
    mutex_unlock(&eta_state.lock);return n;
}
static struct device_attribute eta_config_attr={ .attr={ .name="config",.mode=0200 },.store=eta_config_store };
static struct device_attribute eta_status_attr={ .attr={ .name="status",.mode=0400 },.show=eta_status_show };
static struct device_attribute eta_config_snapshot_attr={ .attr={ .name="config_snapshot",.mode=0400 },.show=eta_config_snapshot_show };
static struct attribute *eta_attrs[]={ &eta_config_attr.attr,&eta_status_attr.attr,&eta_config_snapshot_attr.attr,NULL };
static const struct attribute_group eta_policy_group={ .name="eta_policy",.attrs=eta_attrs };

int eta_adapter_capture_group(struct kobject *k,const struct attribute_group *g)
{
    int result=sysfs_create_group(k,g);
    if(!result && g==&eta_original_oplus_fan_attrs_group) {
        mutex_lock(&eta_state.lock);
        if(!eta_state.led) {
            eta_state.led=(struct device *)k; /* BTF device.kobj offset 0. */
            eta_state.level=eta_find_attribute(g,"level");eta_state.state=eta_find_attribute(g,"state");
            eta_state.effective=eta_find_attribute(g,"level_eff");eta_state.status=eta_find_attribute(g,"status");eta_state.present=eta_find_attribute(g,"present");
        }
        mutex_unlock(&eta_state.lock);
    }
    return result;
}
static void eta_quiesce(void)
{
    mutex_lock(&eta_state.lock);eta_state.blocked=1;eta_state.deadline=0;eta_release_locked();
    eta_fan_policy_reset(&eta_state.policy);eta_invalidate_sample_locked();mutex_unlock(&eta_state.lock);
    if(eta_state.wq) cancel_delayed_work_sync(&eta_state.work);
}
static void eta_adapter_cleanup(void *opaque)
{
    struct workqueue_struct *wq;struct device *led;unsigned int remove;
    (void)opaque;eta_quiesce();mutex_lock(&eta_state.lock);
    eta_state.live=0;eta_state.probing=1;led=eta_state.led;remove=eta_state.group_added;eta_state.group_added=0;
    wq=eta_state.wq;eta_state.wq=NULL;mutex_unlock(&eta_state.lock);
    /* sysfs removal can wait for active callbacks: never hold our lock here. */
    if(remove && led) sysfs_remove_group(&led->kobj,&eta_policy_group);
    if(wq) destroy_workqueue(wq);
    mutex_lock(&eta_state.lock);eta_state.probing=0;mutex_unlock(&eta_state.lock);
    /* Keep the initialized mutex for the entire module lifetime. Driver-core
     * rebind follows completion of all old devres/LED sysfs teardown. */
}
int eta_adapter_probe(struct platform_device *pdev)
{
    int result;unsigned int expected=0,i;
    if(__atomic_compare_exchange_n(&eta_instance,&expected,1,0,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE)) {
        __mutex_init(&eta_state.lock,"eta_fan_policy",NULL);
        __atomic_store_n(&eta_instance,2,__ATOMIC_RELEASE);
    } else if(__atomic_load_n(&eta_instance,__ATOMIC_ACQUIRE)!=2) return -16;
    mutex_lock(&eta_state.lock);
    if(eta_state.live || eta_state.probing || eta_state.wq) { mutex_unlock(&eta_state.lock);return -16; }
    /* Never reinitialize/copy an active mutex. Only its following state is
     * cleared, after old devres completed and its dedicated queue was drained. */
    __builtin_memset((char *)&eta_state+offsetof(struct eta_adapter_state,pdev),0,
        sizeof(eta_state)-offsetof(struct eta_adapter_state,pdev));
    eta_state.pdev=pdev;eta_state.probing=1;
    eta_state.physical_request=eta_state.physical_state=eta_state.last_effective=-1;
    eta_state.battery_status=eta_state.battery_present=-1;
    for(i=0;i<4;++i) eta_state.temperature_mc[i]=-1;
    eta_state.work.work.data.counter=(long)ETA_WORK_NO_POOL;
    eta_state.work.work.entry.next=&eta_state.work.work.entry;eta_state.work.work.entry.prev=&eta_state.work.work.entry;
    eta_state.work.work.func=eta_policy_work;
    init_timer_key(&eta_state.work.timer,delayed_work_timer_fn,ETA_TIMER_IRQSAFE,NULL,NULL);
    mutex_unlock(&eta_state.lock);
    result=eta_original_oplus_fan_probe(pdev);
    if(result) {
        mutex_lock(&eta_state.lock);eta_state.blocked=1;eta_state.probing=0;mutex_unlock(&eta_state.lock);
        return result;
    }
    eta_state.wq=alloc_workqueue("eta_fan_policy",ETA_WQ_UNBOUND|ETA_WQ_FREEZABLE,1);
    if(!eta_state.wq || !eta_state.led || !eta_state.level || !eta_state.state || !eta_state.effective || !eta_state.status || !eta_state.present) {
        eta_state.fault=-19;eta_adapter_cleanup(NULL);return 0; /* OEM remains usable. */
    }
    /* A later devm action runs before OEM's earlier cleanup/LED resources. */
    result=__devm_add_action(&pdev->dev,eta_adapter_cleanup,NULL,"eta_fan_policy_cleanup");
    if(result) { eta_state.fault=result;eta_adapter_cleanup(NULL);return 0; }
    result=sysfs_create_group(&eta_state.led->kobj,&eta_policy_group);
    if(result) { eta_state.fault=result;eta_adapter_cleanup(NULL);return 0; }
    mutex_lock(&eta_state.lock);eta_state.group_added=1;eta_state.live=1;eta_state.probing=0;mutex_unlock(&eta_state.lock);
    queue_delayed_work_on(ETA_WORK_CPU_UNBOUND,eta_state.wq,&eta_state.work,0);
    return 0;
}
void eta_adapter_remove(struct platform_device *pdev) { eta_quiesce();eta_original_oplus_fan_remove(pdev); }
void eta_adapter_shutdown(struct platform_device *pdev) { eta_quiesce();eta_original_oplus_fan_shutdown(pdev); }
int eta_adapter_suspend(struct device *dev) { eta_quiesce();return eta_original_oplus_fan_suspend(dev); }
int eta_adapter_resume(struct device *dev)
{
    int result=eta_original_oplus_fan_resume(dev);
    mutex_lock(&eta_state.lock);
    if(!result && eta_state.live) {
        eta_state.blocked=0;
        if(eta_state.config.revision && eta_state.wq)
            mod_delayed_work_on(ETA_WORK_CPU_UNBOUND,eta_state.wq,&eta_state.work,0);
    }
    mutex_unlock(&eta_state.lock);
    /* Resume restarts reads only. A fresh authorized lease is mandatory for control. */
    return result;
}
__attribute__((used,section(".eta_fan_nonrelease")))
static const char eta_nonrelease_identity[]=ETA_FAN_CANDIDATE_LABEL ";CANDIDATE;BTF=" ETA_TARGET_BTF_SHA256 ";OEM=" ETA_ORIGINAL_KO_SHA256 ";default_disabled=1;runtime_verified=0";
