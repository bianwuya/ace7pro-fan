/* OFFLINE NATIVE HOST HARNESS: all kernel/OEM functions below are fakes.
 * Includes the real adapter implementation; never links/runs an OEM KO. */
#define ETA_FAN_TARGET_ABI_VERIFIED 1
#include "driver_adapter.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

static unsigned checks;
#define CHECK(X) do { ++checks; if(!(X)) { fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#X);abort(); } } while(0)
static struct platform_device fake_pdev;
static struct device fake_led;
static int req,enabled,effective,present,status_normal;
static int level_writes,state_writes,queue_calls,cancel_calls,remove_calls,destroy_calls;
static int probe_result,wq_fail,action_fail,policy_group_fail,lock_depth;
static int original_remove_calls,original_shutdown_calls,original_suspend_calls,original_resume_calls;
static int battery_missing,usb_missing,power_status,power_present,usb_online,thermal_missing,temperature,thermal_delay;
static int ac_missing,wireless_missing,ac_online,wireless_online,battery_error,battery_status_fail,battery_present_fail;
static unsigned int source_error_mask,source_read_fail_mask,thermal_read_fail_mask,thermal_missing_mask,readback_fail_mask;
static int temperature_offset[4];
static unsigned int source_puts[3],battery_puts;
static eta_u64 now_ms;
static void (*fake_devm_action)(void *);
static void *fake_devm_data;
static char fake_wq,fake_battery,fake_sources[3],fake_zones[4];
static struct device_attribute original_attrs[5]={
 {.attr={.name="level"}}, {.attr={.name="state"}}, {.attr={.name="level_eff"}},
 {.attr={.name="status"}}, {.attr={.name="present"}}
};
static struct attribute *original_pointers[16]={&original_attrs[0].attr,&original_attrs[1].attr,&original_attrs[2].attr,&original_attrs[3].attr,&original_attrs[4].attr,NULL};
struct attribute_group eta_original_oplus_fan_attrs_group={.attrs=original_pointers};

int scnprintf(char *b,size_t n,const char *f,...)
{
    int result;va_list a;va_start(a,f);result=vsnprintf(b,n,f,a);va_end(a);
    return result<0?0:(result>=(int)n?(int)n-1:result);
}
void __mutex_init(struct mutex *m,const char *n,struct lock_class_key *k) { (void)m;(void)n;(void)k;CHECK(!lock_depth); }
void mutex_lock(struct mutex *m) { (void)m;CHECK(!lock_depth);++lock_depth; }
void mutex_unlock(struct mutex *m) { (void)m;CHECK(lock_depth==1);--lock_depth; }
long long ktime_get(void) { return (long long)(now_ms*1000000ULL); }
void init_timer_key(struct timer_list *t,void (*f)(struct timer_list *),unsigned int flags,const char *n,struct lock_class_key *k)
{ (void)n;(void)k;CHECK(flags==ETA_TIMER_IRQSAFE);t->function=f;t->flags=flags; }
void delayed_work_timer_fn(struct timer_list *t) { (void)t; }
struct workqueue_struct *alloc_workqueue(const char *n,unsigned int flags,int active,...)
{ CHECK(eta_equal(n,"eta_fan_policy"));CHECK(flags==(ETA_WQ_UNBOUND|ETA_WQ_FREEZABLE));CHECK(active==1);return wq_fail?NULL:(struct workqueue_struct *)&fake_wq; }
void destroy_workqueue(struct workqueue_struct *w) { CHECK(w==(struct workqueue_struct *)&fake_wq);CHECK(!lock_depth);++destroy_calls; }
unsigned long __msecs_to_jiffies(const unsigned int ms) { return (ms+3)/4; }
bool queue_delayed_work_on(int cpu,struct workqueue_struct *w,struct delayed_work *d,unsigned long delay)
{ (void)delay;CHECK(cpu==32);CHECK(w==(struct workqueue_struct *)&fake_wq);CHECK(d==&eta_state.work);++queue_calls;return true; }
bool mod_delayed_work_on(int cpu,struct workqueue_struct *w,struct delayed_work *d,unsigned long delay)
{ return queue_delayed_work_on(cpu,w,d,delay); }
bool cancel_delayed_work_sync(struct delayed_work *d) { CHECK(!lock_depth);CHECK(d==&eta_state.work);++cancel_calls;return true; }
int sysfs_create_group(struct kobject *k,const struct attribute_group *g)
{ CHECK(k==&fake_led.kobj);return g==&eta_policy_group && policy_group_fail?-12:0; }
void sysfs_remove_group(struct kobject *k,const struct attribute_group *g)
{ CHECK(!lock_depth);CHECK(k==&fake_led.kobj);CHECK(g==&eta_policy_group);++remove_calls; }
int __devm_add_action(struct device *d,void (*action)(void *),void *data,const char *name)
{ CHECK(d==&fake_pdev.dev);CHECK(eta_equal(name,"eta_fan_policy_cleanup"));if(action_fail)return -12;fake_devm_action=action;fake_devm_data=data;return 0; }
struct thermal_zone_device *thermal_zone_get_zone_by_name(const char *n)
{
 unsigned int i;CHECK(n[0]);for(i=0;i<4;++i) if(eta_equal(n,eta_state.config.zone[i])) break;
 CHECK(i<4);return thermal_missing || (thermal_missing_mask&(1U<<i))?NULL:(struct thermal_zone_device *)&fake_zones[i];
}
int thermal_zone_get_temp(struct thermal_zone_device *d,int *v)
{
 unsigned int i;for(i=0;i<4;++i)if(d==(struct thermal_zone_device *)&fake_zones[i])break;CHECK(i<4);
 now_ms+=(unsigned int)thermal_delay;if(thermal_read_fail_mask&(1U<<i))return -5;*v=temperature+temperature_offset[i];return 0;
}
struct power_supply *power_supply_get_by_name(const char *n)
{
 unsigned int i;int missing;
 if(eta_equal(n,"battery"))return battery_error?(struct power_supply *)-5L:(battery_missing?NULL:(struct power_supply *)&fake_battery);
 for(i=0;i<3;++i)if(eta_equal(n,i==0?"usb":i==1?"ac":"wireless"))break;CHECK(i<3);
 missing=i==0?usb_missing:i==1?ac_missing:wireless_missing;
 if(source_error_mask&(1U<<i))return (struct power_supply *)-5L;
 return missing?NULL:(struct power_supply *)&fake_sources[i];
}
int power_supply_get_property(struct power_supply *p,enum power_supply_property field,union power_supply_propval *v)
{
 unsigned int i;
 if(p==(struct power_supply *)&fake_battery) {
  CHECK(field==POWER_SUPPLY_PROP_STATUS || field==POWER_SUPPLY_PROP_PRESENT);
  if((field==POWER_SUPPLY_PROP_STATUS && battery_status_fail) || (field==POWER_SUPPLY_PROP_PRESENT && battery_present_fail))return -5;
  v->intval=field==POWER_SUPPLY_PROP_STATUS?power_status:power_present;return 0;
 }
 for(i=0;i<3;++i)if(p==(struct power_supply *)&fake_sources[i])break;CHECK(i<3);CHECK(field==POWER_SUPPLY_PROP_ONLINE);
 if(source_read_fail_mask&(1U<<i))return -5;v->intval=i==0?usb_online:i==1?ac_online:wireless_online;return 0;
}
void power_supply_put(struct power_supply *p)
{
 unsigned int i;if(p==(struct power_supply *)&fake_battery){++battery_puts;return;}
 for(i=0;i<3;++i)if(p==(struct power_supply *)&fake_sources[i])break;CHECK(i<3);++source_puts[i];
}
int eta_original_oplus_fan_probe(struct platform_device *d)
{ CHECK(d==&fake_pdev);if(probe_result)return probe_result;return eta_adapter_capture_group(&fake_led.kobj,&eta_original_oplus_fan_attrs_group); }
void eta_original_oplus_fan_remove(struct platform_device *d) { CHECK(d==&fake_pdev);++original_remove_calls; }
void eta_original_oplus_fan_shutdown(struct platform_device *d) { CHECK(d==&fake_pdev);++original_shutdown_calls; }
int eta_original_oplus_fan_suspend(struct device *d) { CHECK(d==&fake_pdev.dev);++original_suspend_calls;return 0; }
int eta_original_oplus_fan_resume(struct device *d) { CHECK(d==&fake_pdev.dev);++original_resume_calls;return 0; }
static int parse_number(const char *b,size_t n,int maximum)
{ size_t i;int v=0;if(!n)return -1;for(i=0;i<n && b[i]>='0' && b[i]<='9';++i)v=v*10+b[i]-'0';if(!i || v>maximum || (i!=n && !(i+1==n && b[i]=='\n')))return -1;return v; }
ssize_t eta_original_level_store(struct device *d,struct device_attribute *a,const char *b,size_t n)
{ int v;CHECK(d==&fake_led);CHECK(a==&original_attrs[0]);v=parse_number(b,n,20);if(v<0)return -22;req=v;++level_writes;return (ssize_t)n; }
ssize_t eta_original_state_store(struct device *d,struct device_attribute *a,const char *b,size_t n)
{ int v;CHECK(d==&fake_led);CHECK(a==&original_attrs[1]);v=parse_number(b,n,1);if(v<0)return -22;enabled=v;++state_writes;return (ssize_t)n; }
ssize_t eta_original_state_show(struct device *d,struct device_attribute *a,char *b) { CHECK(d==&fake_led);CHECK(a==&original_attrs[1]);if(readback_fail_mask&2)return -5;return scnprintf(b,4096,"%d\n",enabled); }
ssize_t eta_original_level_show(struct device *d,struct device_attribute *a,char *b) { CHECK(d==&fake_led);CHECK(a==&original_attrs[0]);if(readback_fail_mask&1)return -5;return scnprintf(b,4096,"%d\n",req); }
ssize_t eta_original_level_eff_show(struct device *d,struct device_attribute *a,char *b) { CHECK(d==&fake_led);CHECK(a==&original_attrs[2]);if(readback_fail_mask&4)return -5;return scnprintf(b,4096,"%d\n",effective); }
ssize_t eta_original_status_show(struct device *d,struct device_attribute *a,char *b) { CHECK(d==&fake_led);CHECK(a==&original_attrs[3]);return scnprintf(b,4096,"%s\n",status_normal?"NORMAL":"OCP_ISSUE"); }
ssize_t eta_original_present_show(struct device *d,struct device_attribute *a,char *b) { CHECK(d==&fake_led);CHECK(a==&original_attrs[4]);return scnprintf(b,4096,"%d\n",present); }

static void reset(void)
{
    memset(&eta_state,0,sizeof(eta_state));eta_instance=0;eta_owner_epoch=0;req=enabled=0;effective=2;present=status_normal=1;
    level_writes=state_writes=queue_calls=cancel_calls=remove_calls=destroy_calls=0;
    probe_result=wq_fail=action_fail=policy_group_fail=lock_depth=0;
    original_remove_calls=original_shutdown_calls=original_suspend_calls=original_resume_calls=0;
    battery_missing=usb_missing=thermal_missing=thermal_delay=0;power_status=2;power_present=1;usb_online=0;temperature=31000;
    ac_missing=wireless_missing=1;ac_online=wireless_online=battery_error=battery_status_fail=battery_present_fail=0;
    source_error_mask=source_read_fail_mask=thermal_read_fail_mask=thermal_missing_mask=readback_fail_mask=0;
    memset(temperature_offset,0,sizeof(temperature_offset));memset(source_puts,0,sizeof(source_puts));battery_puts=0;
    now_ms=1000;fake_devm_action=NULL;fake_devm_data=NULL;
}
static void start(void) { reset();CHECK(eta_adapter_probe(&fake_pdev)==0);CHECK(eta_state.live);CHECK(eta_state.config.enabled==0); }
static int line(char *b,unsigned int revision,unsigned int enable,unsigned int mask,unsigned int mode,unsigned int lease)
{
 return snprintf(b,1024,"1 %u %u %u 2 %u 2 %u 1 0 2 30000 12 600000 0 400000 0 2 30000 6 1000 20 600000 0 400000 0 battery_raw board_raw surround_raw mos_raw\n",revision,enable,lease,mode,mask);
}
static ssize_t configure(unsigned int revision,unsigned int enable,unsigned int mask,unsigned int mode,unsigned int lease)
{ char b[1024];int n=line(b,revision,enable,mask,mode,lease);ssize_t result=eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n);CHECK(result==n);return result; }
static void work(void) { eta_policy_work(&eta_state.work.work);CHECK(!lock_depth); }
static void acquire(void) { start();configure(1,1,127,1,5000);work();CHECK(eta_state.owned);CHECK(req==6 && enabled==1); }
static void test_parser(void)
{
 char b[1100];struct eta_adapter_config c,old;int n=line(b,1,1,127,1,5000);CHECK(!eta_adapter_parse_config(b,(eta_u32)n,&c));CHECK(c.policy.daily.floor==12);CHECK(c.policy.charge.gain_milli==1000);CHECK(eta_adapter_permissions(&c,1,2));CHECK(!eta_adapter_permissions(&c,2,2));
 memset(&c,0x5a,sizeof(c));old=c;b[0]='9';CHECK(eta_adapter_parse_config(b,(eta_u32)n,&c)==-22);CHECK(!memcmp(&c,&old,sizeof(c)));
 n=line(b,1,1,127,1,5000);strcat(b,"extra");CHECK(eta_adapter_parse_config(b,(eta_u32)strlen(b),&c)==-22);
 n=line(b,1,1,127,1,5001);CHECK(eta_adapter_parse_config(b,(eta_u32)n,&c)==-22);
 n=line(b,1,1,127,1,0);CHECK(eta_adapter_parse_config(b,(eta_u32)n,&c)==-22);
 n=line(b,1,1,127,1,5000);{ char *p=strstr(b,"battery_raw");memcpy(p,"shell_fake_",11); }CHECK(eta_adapter_parse_config(b,(eta_u32)n,&c)==-22);
 n=line(b,1,1,127,1,5000);CHECK(eta_adapter_parse_config(b,(eta_u32)n-10,&c)==-22);
 n=line(b,1,1,127,1,5000);b[2]='-';CHECK(eta_adapter_parse_config(b,(eta_u32)n,&c)==-22);
 CHECK(eta_adapter_parse_config(b,1025,&c)==-22);
}
static void test_default_and_passthrough(void)
{
 start();work();CHECK(!eta_state.owned && !level_writes && !state_writes);CHECK(queue_calls==1);
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"17\n",3)==3);CHECK(req==17);
 CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==2);CHECK(enabled==1);
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"bogus",5)==-22);
 configure(1,0,127,1,0);work();CHECK(!eta_state.owned && enabled==1);
}
static void test_edges_lease_derate(void)
{
 int lw,sw;char b[1024];int n;
 acquire();lw=level_writes;sw=state_writes;now_ms=2000;effective=1;work();CHECK(level_writes==lw && state_writes==sw);CHECK(eta_state.last_effective==1);
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"20\n",3)==-16);CHECK(req==6);
 CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"0\n",2)==-16);CHECK(enabled==1);
 configure(1,1,127,1,5000);CHECK(eta_state.deadline==7000);work();CHECK(level_writes==lw);
 n=line(b,1,1,127,1,5000);b[0]='2';CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==-22);CHECK(eta_state.config.revision==1);
 now_ms=7000;work();CHECK(!eta_state.owned && enabled==0);CHECK(state_writes==sw+1);
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"18\n",3)==3);CHECK(req==18);
}
static void test_hardguards(void)
{
 acquire();temperature=45000;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);CHECK(eta_state.output.hard_exit);
 acquire();status_normal=0;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();present=0;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();thermal_missing=1;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();battery_missing=1;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();usb_missing=1;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();usb_online=1;power_status=4;now_ms=2000;work();CHECK(!eta_state.owned && !enabled);
 acquire();configure(2,1,0,1,5000);CHECK(!eta_state.owned && !enabled);work();CHECK(!eta_state.owned);
 acquire();configure(2,1,127,2,5000);CHECK(!eta_state.owned && !enabled);CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"15\n",3)==3);
 acquire();req=10;now_ms=2000;work();CHECK(eta_state.fault==-16 && !eta_state.owned && !enabled);
 start();configure(1,1,127,1,5000);thermal_delay=2000;work();CHECK(!level_writes && !state_writes && !eta_state.owned);
}
static void test_baseline_and_normal_ramp(void)
{
 unsigned int i;start();req=14;enabled=1;configure(1,1,127,1,5000);work();CHECK(!eta_state.owned && req==14 && enabled==1);CHECK(!level_writes && !state_writes);
 start();temperature=29000;req=9;enabled=1;configure(1,1,127,1,5000);work();CHECK(!eta_state.owned && req==9 && enabled==1);
 start();req=8;enabled=1;configure(1,1,127,1,5000);work();CHECK(eta_state.owned && req==8);CHECK(!level_writes && !state_writes);
 for(i=2;i<=100;++i) { now_ms=i*1000ULL;if(i%3==0)configure(1,1,127,1,5000);if(i>=8)temperature=28000;work(); }
 CHECK(!eta_state.owned);CHECK(req==8 && enabled==1);CHECK(!state_writes);CHECK(level_writes>0 && level_writes<12);
}
static void test_lifecycle(void)
{
 acquire();CHECK(!eta_adapter_suspend(&fake_pdev.dev));CHECK(original_suspend_calls==1);CHECK(eta_state.blocked && !eta_state.owned && !enabled);CHECK(cancel_calls==1);
 CHECK(!eta_adapter_resume(&fake_pdev.dev));CHECK(original_resume_calls==1);CHECK(!eta_state.blocked && eta_state.deadline==0);work();CHECK(!enabled);
 configure(1,1,127,1,5000);work();CHECK(eta_state.owned);CHECK(fake_devm_action);fake_devm_action(fake_devm_data);CHECK(!eta_state.live && !eta_state.wq);CHECK(remove_calls==1 && destroy_calls==1 && !enabled);
 CHECK(!eta_adapter_probe(&fake_pdev));CHECK(eta_state.live && !eta_state.config.enabled);work();CHECK(!eta_state.owned);CHECK(eta_adapter_probe(&fake_pdev)==-16);
 acquire();eta_adapter_shutdown(&fake_pdev);CHECK(original_shutdown_calls==1 && !enabled);CHECK(eta_state.blocked);
 acquire();eta_adapter_remove(&fake_pdev);CHECK(original_remove_calls==1 && !enabled);CHECK(eta_state.blocked);
 reset();probe_result=-517;CHECK(eta_adapter_probe(&fake_pdev)==-517);CHECK(!queue_calls && eta_instance==2);probe_result=0;CHECK(!eta_adapter_probe(&fake_pdev));CHECK(eta_state.live && !eta_state.blocked);
 reset();wq_fail=1;CHECK(!eta_adapter_probe(&fake_pdev));CHECK(!eta_state.live && !queue_calls);CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"9\n",2)==2);
 reset();action_fail=1;CHECK(!eta_adapter_probe(&fake_pdev));CHECK(!eta_state.live && destroy_calls==1 && !queue_calls);
 reset();policy_group_fail=1;CHECK(!eta_adapter_probe(&fake_pdev));CHECK(!eta_state.live && destroy_calls==1 && !queue_calls);CHECK(fake_devm_action);fake_devm_action(fake_devm_data);CHECK(destroy_calls==1);
}
#include "test_independent_host.h"
#include "test_first_control_host.h"
int main(int argc,char **argv)
{
 test_parser();test_default_and_passthrough();test_edges_lease_derate();test_hardguards();test_baseline_and_normal_ramp();test_lifecycle();
 test_stage3_independent();test_stage4_first_control();
 if(argc==2 && !strcmp(argv[1],"--snapshots")) print_stage3_relay_fixtures();
 printf("PASS %u assertions: real adapter/policy host execution with mocked kernel+OEM APIs; NOT device verification\n",checks);return 0;
}
