/* Included only by test_adapter_host.c; executes real adapter with fakes. */
static int line_v2(char *b,unsigned int revision,unsigned int enable,unsigned int mask,unsigned int mode,unsigned int lease,unsigned int strategy)
{
 return snprintf(b,1024,"2 %u %u %u 2 %u 2 %u 1 0 2 30000 12 600000 0 400000 0 2 30000 6 1000 20 600000 0 400000 0 %u battery board_temp fan_temp svooc_mos_btb_usr\n",revision,enable,lease,mode,mask,strategy);
}
static void configure_v2(unsigned int revision,unsigned int enable,unsigned int mask,unsigned int mode,unsigned int lease,unsigned int strategy)
{
 char b[1024];int n=line_v2(b,revision,enable,mask,mode,lease,strategy);
 CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==n);
}
static void full_acquire(void)
{
 start();configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned && eta_state.ready);CHECK(req==6 && enabled==1);CHECK(eta_owner_epoch==1);
}
static void test_v2_parser_and_snapshots(void)
{
 char b[4096],c[4096];int n;unsigned int i;struct eta_adapter_config parsed;
 start();CHECK(eta_state.config.strategy==0);CHECK(eta_status_attr.attr.mode==0400);CHECK(eta_config_snapshot_attr.attr.mode==0400);CHECK(eta_config_attr.attr.mode==0200);
 CHECK(eta_config_snapshot_show(&fake_led,&eta_config_snapshot_attr,b)>0);CHECK(!strcmp(b,"UNCONFIGURED protocol=2 strategy=0 enabled=0\n"));
 n=line(b,1,1,127,1,5000);CHECK(!eta_adapter_parse_config(b,(eta_u32)n,&parsed));CHECK(parsed.protocol_version==1 && parsed.strategy==1);
 for(i=0;i<3;++i) {
  n=line_v2(b,1,1,127,1,5000,i);CHECK(!eta_adapter_parse_config(b,(eta_u32)n,&parsed));CHECK(parsed.strategy==i && parsed.protocol_version==2);
  CHECK(eta_adapter_permissions(&parsed,1,2)==(i!=0));CHECK(!strcmp(parsed.zone[0],"battery"));CHECK(!strcmp(parsed.zone[3],"svooc_mos_btb_usr"));
 }
 n=line_v2(b,1,1,127,1,5000,3);CHECK(eta_adapter_parse_config(b,(eta_u32)n,&parsed)==-22);
 n=line_v2(b,1,1,127,1,5000,2);strcat(b," 1");CHECK(eta_adapter_parse_config(b,(eta_u32)strlen(b),&parsed)==-22);
 n=line_v2(b,1,1,127,1,5000,2);b[0]='1';CHECK(eta_adapter_parse_config(b,(eta_u32)n,&parsed)==-22);
 configure_v2(1,1,127,1,5000,2);n=(int)eta_config_snapshot_show(&fake_led,&eta_config_snapshot_attr,b);CHECK(n==(int)strlen(b));
 CHECK(!eta_adapter_parse_config(b,(eta_u32)n,&parsed));CHECK(!memcmp(&parsed,&eta_state.config,sizeof(parsed)));
 CHECK(line_v2(c,1,1,127,1,5000,2)==n);CHECK(!strcmp(b,c));
 configure(2,1,127,1,5000);n=(int)eta_config_snapshot_show(&fake_led,&eta_config_snapshot_attr,b);
 CHECK(!eta_adapter_parse_config(b,(eta_u32)n,&parsed));CHECK(parsed.strategy==1 && parsed.protocol_version==1);
 CHECK(!memcmp(&parsed,&eta_state.config,sizeof(parsed)));
}
static void test_full_owner_zero_and_independent_target(void)
{
 unsigned int i;int lw,sw;eta_u64 epoch;
 start();temperature=29000;req=6;enabled=1;configure_v2(1,1,127,1,5000,2);work();
 CHECK(eta_state.output.target==0 && eta_state.owned && eta_state.ready);CHECK(!enabled && state_writes==1 && !level_writes);epoch=eta_owner_epoch;
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"20\n",3)==-16);CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==-16);
 for(i=2;i<=9;++i) { now_ms=i*1000ULL;configure_v2(1,1,127,1,5000,2);work(); }
 CHECK(eta_state.owned && !enabled && !level_writes && state_writes==1);CHECK(eta_owner_epoch==epoch);CHECK(!eta_state.output.hard_exit);
 start();temperature=30000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned && eta_state.output.target==0 && !enabled); /* strict daily > */
 temperature=31000;now_ms=2000;work();CHECK(eta_state.owned && req==6 && enabled==1); /* core normal starts 6 */
 lw=level_writes;sw=state_writes;effective=1;now_ms=3000;configure_v2(1,1,127,1,5000,2);work();CHECK(level_writes==lw && state_writes==sw);
 /* Full ownership does not max a previous OEM target of 14 into core target12. */
 start();req=14;enabled=1;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned && eta_state.output.target==12);
 for(i=2;i<=24;++i) { now_ms=i*1000ULL;if(i%3==0)configure_v2(1,1,127,1,5000,2);work(); }
 CHECK(eta_state.owned && req==12 && enabled);CHECK(eta_state.ramp.requested_level==12);CHECK(state_writes==0);
 lw=level_writes;sw=state_writes;effective=2;
 for(i=25;i<=29;++i) { now_ms=i*1000ULL;if(i%3==0)configure_v2(1,1,127,1,5000,2);work(); }
 CHECK(level_writes==lw && state_writes==sw); /* effective derate never creates writes */
 temperature=28000;
 for(i=30;i<=130;++i) { now_ms=i*1000ULL;if(i%3==0)configure_v2(1,1,127,1,5000,2);work(); }
 CHECK(eta_state.owned && eta_state.ready && eta_state.output.target==0 && !enabled);CHECK(eta_state.ramp.requested_level==0);
 CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==-16);CHECK(!enabled);
 /* v2 explicit pass-through does not accidentally activate even with gates. */
 start();req=17;enabled=1;configure_v2(1,1,127,1,5000,0);work();CHECK(!eta_state.owned && req==17 && enabled && !level_writes && !state_writes);
 /* v1 additive remains tested, not implicitly removed. */
 start();req=17;enabled=1;configure(1,1,127,1,5000);work();CHECK(!eta_state.owned && req==17 && enabled);
}
static void test_full_owner_release_and_manual_edges(void)
{
 unsigned int mask;int sw;eta_u64 epoch;
 full_acquire();epoch=eta_owner_epoch;now_ms=2000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_owner_epoch==epoch && !eta_state.output.hard_exit);
 configure_v2(2,1,127,2,5000,2);CHECK(!eta_state.owned && !enabled && eta_owner_epoch==epoch+1);sw=state_writes;
 CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"18\n",3)==3);CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==2);
 work();CHECK(enabled && req==18 && state_writes==sw+1);CHECK(eta_owner_epoch==epoch+1); /* later manual never stopped */
 full_acquire();now_ms=eta_state.deadline;CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"16\n",3)==3);
 CHECK(!eta_state.owned && !enabled);CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==2);sw=state_writes;work();CHECK(enabled && req==16 && state_writes==sw);
 for(mask=0;mask<7;++mask) {
  full_acquire();configure_v2(2,1,127U&~(1U<<mask),1,5000,2);CHECK(!eta_state.owned && !enabled);work();CHECK(!eta_state.owned);
 }
 full_acquire();configure_v2(2,0,127,1,0,2);CHECK(!eta_state.owned && !enabled);sw=state_writes;work();CHECK(state_writes==sw);
 full_acquire();epoch=eta_owner_epoch;now_ms=eta_state.deadline;configure_v2(1,1,127,1,5000,2);
 CHECK(!eta_state.owned && !enabled && eta_owner_epoch==epoch+1 && !eta_state.policy.context_valid && !eta_state.temp_mask);
 work();CHECK(eta_state.owned && enabled && eta_owner_epoch==epoch+2); /* late same-rev renewal needs fresh sampling */
 full_acquire();CHECK(!eta_adapter_suspend(&fake_pdev.dev));CHECK(!eta_state.owned && !eta_state.policy.context_valid && !eta_state.temp_mask);
 CHECK(!eta_adapter_resume(&fake_pdev.dev));CHECK(eta_state.deadline==0);work();CHECK(!eta_state.owned);
 temperature=29000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned && eta_state.output.target==0 && !enabled);
 epoch=eta_owner_epoch;fake_devm_action(fake_devm_data);CHECK(eta_owner_epoch==epoch+1);CHECK(!eta_adapter_probe(&fake_pdev));CHECK(eta_state.config.strategy==0 && !eta_state.config.enabled);
 configure_v2(1,1,127,1,5000,2);work();CHECK(eta_owner_epoch==epoch+2 && eta_state.owned);CHECK(!enabled);
}
static void test_all_power_sources_and_failures(void)
{
 unsigned int i;enum eta_fan_power power;
 start();power=eta_power_read();CHECK(power==ETA_POWER_BATTERY);CHECK(eta_state.source_present_mask==1 && !eta_state.source_failed_mask);CHECK(battery_puts==1 && source_puts[0]==1 && !source_puts[1] && !source_puts[2]);
 ac_missing=wireless_missing=0;CHECK(eta_power_read()==ETA_POWER_BATTERY);CHECK(eta_state.source_present_mask==7);
 wireless_online=1;power_status=1;CHECK(eta_power_read()==ETA_POWER_CHARGING);CHECK(eta_state.source_online_mask==4);
 wireless_online=0;ac_online=1;CHECK(eta_power_read()==ETA_POWER_CHARGING);CHECK(eta_state.source_online_mask==2);
 power_status=4;CHECK(eta_power_read()==ETA_POWER_EXTERNAL_IDLE);power_status=3;CHECK(eta_power_read()==ETA_POWER_EXTERNAL_IDLE);
 power_status=2;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);power_status=1;ac_online=0;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);
 for(i=0;i<3;++i) {
  start();ac_missing=wireless_missing=0;source_read_fail_mask=1U<<i;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.source_present_mask==7 && eta_state.source_failed_mask==(1U<<i));
  CHECK(battery_puts==1 && source_puts[0]==1 && source_puts[1]==1 && source_puts[2]==1);
  start();ac_missing=wireless_missing=0;source_error_mask=1U<<i;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.source_failed_mask==(1U<<i));CHECK(!(eta_state.source_present_mask&(1U<<i)));CHECK(source_puts[i]==0);
  start();ac_missing=wireless_missing=0;if(i==0)usb_online=2;else if(i==1)ac_online=-1;else wireless_online=2;
  CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.source_failed_mask==(1U<<i));
  /* Any failed existing provider prevents ownership even if another says online. */
  full_acquire();ac_missing=wireless_missing=0;power_status=1;usb_online=wireless_online=1;source_read_fail_mask=1U<<i;now_ms=2000;work();CHECK(!eta_state.owned && !enabled && eta_state.power==ETA_POWER_UNKNOWN);
 }
 start();battery_missing=1;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(!battery_puts);
 start();battery_error=1;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(!battery_puts);
 start();battery_status_fail=1;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.battery_status==-1 && battery_puts==1);
 start();battery_present_fail=1;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.battery_present==-1 && battery_puts==1);
 start();power_present=0;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);power_present=2;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);
 start();usb_missing=1;wireless_missing=0;wireless_online=1;power_status=1;CHECK(eta_power_read()==ETA_POWER_UNKNOWN);CHECK(eta_state.source_present_mask==4);
}
static void test_power_epoch_and_full_owner_sampling(void)
{
 eta_u64 epoch,owner;unsigned int i;char snapshot[4096];
 full_acquire();epoch=eta_state.power_epoch;owner=eta_owner_epoch;now_ms=2000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.power_epoch==epoch && eta_owner_epoch==owner);
 wireless_missing=0;wireless_online=1;power_status=1;now_ms=3000;work();CHECK(eta_state.power_epoch==epoch+1 && eta_state.output.hard_exit && !eta_state.owned && !enabled);
 now_ms=4000;work();CHECK(eta_state.owned && enabled && eta_state.power==ETA_POWER_CHARGING);
 epoch=eta_state.power_epoch;usb_online=1;wireless_online=0;now_ms=5000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.power_epoch==epoch+1 && !eta_state.owned); /* same CHARGING, source edge */
 now_ms=6000;work();CHECK(eta_state.owned);
 epoch=eta_state.power_epoch;usb_online=0;power_status=2;now_ms=7000;work();CHECK(eta_state.power_epoch==epoch+1 && !eta_state.owned && eta_state.power==ETA_POWER_BATTERY);
 now_ms=8000;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned);
 for(i=0;i<4;++i) {
  full_acquire();thermal_read_fail_mask=1U<<i;now_ms=2000;work();CHECK(!eta_state.owned && !enabled && eta_state.temp_mask==(15U&~(1U<<i)));CHECK(eta_state.temperature_mc[i]==-1);
  full_acquire();thermal_missing_mask=1U<<i;now_ms=2000;work();CHECK(!eta_state.owned && !enabled && eta_state.temp_mask==(15U&~(1U<<i)));
 }
 full_acquire();temperature_offset[1]=100;temperature_offset[2]=200;temperature_offset[3]=300;now_ms=2000;work();
 CHECK(eta_status_show(&fake_led,&eta_status_attr,snapshot)>0);
 CHECK(strstr(snapshot,"leaseUntil=") && strstr(snapshot,"configRevision=1") && strstr(snapshot,"ownerEpoch=1") && strstr(snapshot,"strategy=2 ready=1 target=12 output=6"));
 CHECK(strstr(snapshot,"temp0=31000 temp1=31100 temp2=31200 temp3=31300"));CHECK(strstr(snapshot,"physicalState=1 effective=2 readbackMask=7"));
 /* Read-only status reports actual physical state, not last submitted value. */
 req=9;effective=1;CHECK(eta_status_show(&fake_led,&eta_status_attr,snapshot)>0);CHECK(strstr(snapshot,"ready=0 target=-1 output=9"));CHECK(strstr(snapshot,"requestedLevel=9 physicalState=1 effective=1"));
 full_acquire();now_ms=eta_state.deadline;CHECK(eta_status_show(&fake_led,&eta_status_attr,snapshot)>0);CHECK(strstr(snapshot,"ready=0 target=-1"));
}
static void print_stage3_relay_fixtures(void)
{
 char b[4096];start();configure_v2(1,0,0,1,0,2);
 puts("fixture=HOST_MOCK_ONLY_NOT_DEVICE");
 eta_config_snapshot_show(&fake_led,&eta_config_snapshot_attr,b);printf("disabled.config_snapshot=%s",b);
 eta_status_show(&fake_led,&eta_status_attr,b);printf("disabled.status=%s",b);
 configure_v2(2,1,127,1,5000,2);work();
 eta_config_snapshot_show(&fake_led,&eta_config_snapshot_attr,b);printf("full_owner.config_snapshot=%s",b);
 eta_status_show(&fake_led,&eta_status_attr,b);printf("full_owner.status=%s",b);
}
static void test_stage3_independent(void)
{
 test_v2_parser_and_snapshots();test_full_owner_zero_and_independent_target();test_full_owner_release_and_manual_edges();
 test_all_power_sources_and_failures();test_power_epoch_and_full_owner_sampling();
}
