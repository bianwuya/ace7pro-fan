/* Real adapter execution with fake kernel/OEM APIs, NOT device verification. */
static void telemetry_status(char *b)
{
 CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);
 CHECK(strstr(b,"ready=0 target=-1"));
 CHECK(strstr(b,"fusionValid=0 fusionMc=-1 demand=-1"));
}
static void telemetry_round(void)
{
 unsigned int tr=thermal_reads,pr=power_reads;int lw=level_writes,sw=state_writes,q=queue_calls;
 eta_u64 owner=eta_owner_epoch,at=now_ms;char b[4096];
 expect_unowned_before_reads=1;work();
 CHECK(thermal_reads==tr+4 && power_reads==pr+4);
 CHECK(level_writes==lw && state_writes==sw && !eta_state.owned && eta_owner_epoch==owner);
 CHECK(queue_calls==q+1 && last_queue_delay==__msecs_to_jiffies(1000));
 CHECK(eta_state.sample_at_ms==at && eta_state.sample_completed_ms==now_ms && eta_state.temp_mask==15);
 telemetry_status(b);CHECK(strstr(b,"telemetryFusionValid=1"));
}
static void test_telemetry_denied_scenes(void)
{
 unsigned int scene,i;int lw,sw;eta_u64 owner;char b[1024];int n;
 /* Manual modes, disabled, passthrough, every safety/game bit, thermal status,
  * main/auto switch: all still use the actual validated config entry point. */
 for(scene=0;scene<19;++scene) {
  unsigned int mask=127,mode=1,enable=1,strategy=2,main=2,auto_on=2,status=0;
  full_acquire();
  if(scene<2)mode=scene+2;
  else if(scene==2)enable=0;
  else if(scene==3)strategy=0;
  else if(scene<11)mask&=~(1U<<(scene-4));
  else if(scene<17)status=scene-10;
  else if(scene==17)main=1;
  else auto_on=1;
  n=snprintf(b,sizeof(b),"2 2 %u %u %u %u %u %u 1 %u 2 30000 12 600000 0 400000 0 2 30000 6 1000 20 600000 0 400000 0 %u battery board_temp fan_temp svooc_mos_btb_usr\n",
      enable,enable?5000:0,main,mode,auto_on,mask,status,strategy);
  sw=state_writes;CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==n);
  CHECK(!eta_state.owned && !enabled && state_writes==sw+1);
  /* A subsequent real manual/OEM owner is never stopped by telemetry. */
  CHECK(eta_adapter_level_store(&fake_led,&original_attrs[0],"18\n",3)==3);
  CHECK(eta_adapter_state_store(&fake_led,&original_attrs[1],"1\n",2)==2);
  lw=level_writes;sw=state_writes;owner=eta_owner_epoch;
  for(i=0;i<20;++i) { now_ms+=1000;telemetry_round(); }
  CHECK(level_writes==lw && state_writes==sw && req==18 && enabled && eta_owner_epoch==owner);
 }
 /* Missing publisher: lease expiry releases before any slow read and cannot
  * stop the queue or be retroactively renewed by read-only work. */
 full_acquire();now_ms=eta_state.deadline;sw=state_writes;lw=level_writes;
 expect_unowned_before_reads=1;thermal_delay=250;work();
 CHECK(!eta_state.owned && !enabled && state_writes==sw+1 && level_writes==lw);
 CHECK(last_queue_delay==__msecs_to_jiffies(1000));
 for(i=0;i<10;++i) { now_ms+=1000;telemetry_round(); }
 /* A short live lease schedules its expiry, never zero-delay after expiry. */
 start();configure_v2(1,1,127,1,1,2);work();CHECK(last_queue_delay==__msecs_to_jiffies(1));
 now_ms=eta_state.deadline;work();CHECK(last_queue_delay==__msecs_to_jiffies(1000) && !eta_state.owned);
 /* Latched software fault and hardware safety denial release before reads. */
 full_acquire();eta_state.fault=-5;sw=state_writes;expect_unowned_before_reads=1;thermal_delay=250;work();
 CHECK(state_writes==sw+1 && !eta_state.owned && eta_state.fault==-5);
 for(i=0;i<8;++i) { now_ms+=1000;telemetry_round();CHECK(eta_state.fault==-5); }
 for(scene=0;scene<2;++scene) {
  full_acquire();if(scene)present=0;else status_normal=0;
  sw=state_writes;expect_unowned_before_reads=1;thermal_delay=250;work();
  CHECK(state_writes==sw+1 && !eta_state.owned && !enabled);
  for(i=0;i<8;++i) { now_ms+=1000;telemetry_round(); }
 }
 /* A protection edge appearing DURING sampling is still checked before
  * actuation, preserving the original post-sample hardware guard. */
 full_acquire();sw=state_writes;lw=level_writes;hardware_trip_after_thermal=thermal_reads+1;now_ms+=1000;work();
 CHECK(!eta_state.owned && !enabled && eta_state.output.hard_exit && state_writes==sw+1 && level_writes==lw);
 CHECK(eta_state.telemetry_fusion.valid && last_queue_delay==__msecs_to_jiffies(1000));
 /* A real readback fault remains latched even when all thermal reads work. */
 full_acquire();readback_fail_mask=1;now_ms+=1000;work();CHECK(eta_state.fault==-5 && !eta_state.owned);
 for(i=0;i<8;++i) { now_ms+=1000;telemetry_round();CHECK(eta_state.fault==-5); }
}
static void test_telemetry_missing_and_config(void)
{
 char b[4096];int n;eta_u64 generation;unsigned int i;
 start();configure_v2(1,0,127,2,0,2);temperature_offset[2]=10000;telemetry_round();
 CHECK(eta_state.telemetry_fusion.temperature_mc==35000 && eta_state.telemetry_fusion.contributor_mask==5);
 thermal_read_fail_mask=1;now_ms+=1000;work();telemetry_status(b);
 CHECK(eta_state.temp_mask==14 && eta_state.temperature_mc[0]==-1);
 CHECK(strstr(b,"tempValidMask=14 temp0=-1"));
 CHECK(strstr(b,"telemetryFusionValid=1 telemetryFusionMc=41000 telemetryContributorMask=4"));
 thermal_read_fail_mask=4;now_ms+=1000;work();telemetry_status(b);
 CHECK(eta_state.temp_mask==11 && eta_state.temperature_mc[2]==-1);
 CHECK(strstr(b,"telemetryFusionValid=1 telemetryFusionMc=31000 telemetryContributorMask=1"));
 thermal_read_fail_mask=5;now_ms+=1000;work();telemetry_status(b);
 CHECK(eta_state.temp_mask==10 && strstr(b,"telemetryFusionValid=0 telemetryFusionMc=-1 telemetryContributorMask=0"));
 thermal_read_fail_mask=15;now_ms+=1000;work();CHECK(!eta_state.temp_mask);
 for(i=0;i<4;++i)CHECK(eta_state.temperature_mc[i]==-1);
 thermal_read_fail_mask=0;memset(temperature_offset,0,sizeof(temperature_offset));
 temperature=65001;now_ms+=1000;work();CHECK(!eta_state.temp_mask && !eta_state.telemetry_fusion.valid);
 temperature=0;now_ms+=1000;work();CHECK(eta_state.temp_mask==15 && eta_state.telemetry_fusion.valid && eta_state.telemetry_fusion.temperature_mc==0);
 temperature=31000;now_ms+=1000;telemetry_round();generation=eta_state.channel_generation;
 /* Parameter-only revision must also clear old fused/raw/time evidence. */
 n=snprintf(b,sizeof(b),"2 2 0 0 2 2 2 127 1 0 2 30000 12 0 1 0 0 2 30000 6 1000 20 1 0 0 0 2 battery board_temp fan_temp svooc_mos_btb_usr\n");
 CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==n);
 CHECK(eta_state.channel_generation==generation && !eta_state.power_sample_valid && !eta_state.temp_mask && !eta_state.sample_at_ms);
 CHECK(!eta_state.telemetry_fusion.valid);telemetry_status(b);
 CHECK(strstr(b,"tempValidMask=0 temp0=-1 temp1=-1 temp2=-1 temp3=-1 sampleAtMs=0"));
 CHECK(strstr(b,"telemetryFusionValid=0 telemetryFusionMc=-1"));
 temperature_offset[1]=5000;now_ms+=1000;telemetry_round();CHECK(eta_state.telemetry_fusion.temperature_mc==36000);
 n=snprintf(b,sizeof(b),"2 3 0 0 2 2 2 127 1 0 2 30000 12 0 1 0 0 2 30000 6 1000 20 1 0 0 0 2 battery_new board_temp fan_temp svooc_mos_btb_usr\n");
 CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==n);
 CHECK(eta_state.channel_generation==generation+1 && !eta_state.temp_mask && !eta_state.power_sample_valid);
 CHECK(!eta_state.telemetry_fusion.valid && !eta_state.sample_completed_ms);
 now_ms+=1000;telemetry_round();CHECK(eta_state.telemetry_fusion.temperature_mc==36000);
 /* Rejected config cannot destroy a valid sample or change the mapping. */
 generation=eta_state.sample_at_ms;b[0]='9';CHECK(eta_config_store(&fake_led,&eta_config_attr,b,(size_t)n)==-22);
 CHECK(eta_state.sample_at_ms==generation && eta_state.telemetry_fusion.valid);
 /* Read-only status does not perform additional thermal/power sampling. */
 i=thermal_reads;n=(int)power_reads;telemetry_status(b);CHECK(thermal_reads==i && power_reads==(unsigned int)n);
 now_ms=eta_state.sample_at_ms+14999;telemetry_status(b);CHECK(strstr(b,"telemetryFusionValid=1"));
 ++now_ms;telemetry_status(b);CHECK(strstr(b,"telemetryFusionValid=0 telemetryFusionMc=-1"));
}
static void test_telemetry_pm_and_power(void)
{
 unsigned int tr,pr;int q,sw,lw;char b[4096];eta_u64 owner;
 full_acquire();CHECK(!eta_adapter_suspend(&fake_pdev.dev));q=queue_calls;tr=thermal_reads;pr=power_reads;
 CHECK(!eta_state.power_sample_valid && !eta_state.temp_mask && !eta_state.telemetry_fusion.valid && !eta_state.deadline);
 now_ms+=20000;work();CHECK(queue_calls==q && thermal_reads==tr && power_reads==pr);
 CHECK(!eta_adapter_resume(&fake_pdev.dev));CHECK(queue_calls==q+1 && !last_queue_delay);
 owner=eta_owner_epoch;sw=state_writes;lw=level_writes;
 telemetry_round();now_ms+=1000;telemetry_round();
 CHECK(!eta_state.deadline && !eta_state.owned && owner==eta_owner_epoch && sw==state_writes && lw==level_writes);
 configure_v2(1,1,127,1,5000,2);expect_unowned_before_reads=0;work();CHECK(eta_state.owned);
 /* Resume of disabled/manual config still reads; never activates. */
 start();configure_v2(1,0,127,2,0,2);work();CHECK(!eta_adapter_suspend(&fake_pdev.dev));
 CHECK(!eta_adapter_resume(&fake_pdev.dev));now_ms+=1000;telemetry_round();
 /* No configuration means no invented channels or polling loop. */
 start();tr=thermal_reads;pr=power_reads;q=queue_calls;work();CHECK(thermal_reads==tr && power_reads==pr && queue_calls==q);
 CHECK(!eta_adapter_suspend(&fake_pdev.dev));CHECK(!eta_adapter_resume(&fake_pdev.dev));CHECK(queue_calls==q);
 /* Telemetry follows fresh power context, including charge weights, but not
  * unknown or external-idle context. No policy target is calculated. */
 start();configure_v2(1,0,127,2,0,2);temperature_offset[2]=10000;telemetry_round();
 power_status=1;usb_online=1;now_ms+=1000;telemetry_round();telemetry_status(b);
 CHECK(eta_state.power==ETA_POWER_CHARGING && strstr(b,"telemetryFusionMc=35000"));
 power_status=4;now_ms+=1000;work();telemetry_status(b);CHECK(eta_state.power==ETA_POWER_EXTERNAL_IDLE && !eta_state.telemetry_fusion.valid);
 battery_status_fail=1;now_ms+=1000;work();telemetry_status(b);CHECK(eta_state.power==ETA_POWER_UNKNOWN && !eta_state.telemetry_fusion.valid && eta_state.temp_mask==15);
 fake_devm_action(fake_devm_data);q=queue_calls;tr=thermal_reads;pr=power_reads;work();CHECK(queue_calls==q && thermal_reads==tr && power_reads==pr);
}
static void print_telemetry_relay_fixtures(void)
{
 char b[4096];full_acquire();configure_v2(2,0,127,2,0,2);now_ms+=1000;work();
 puts("fixture=HOST_MOCK_ONLY_NOT_DEVICE");eta_status_show(&fake_led,&eta_status_attr,b);printf("manual.status=%s",b);
 thermal_read_fail_mask=1;now_ms+=1000;work();eta_status_show(&fake_led,&eta_status_attr,b);printf("missing.status=%s",b);
 thermal_read_fail_mask=5;now_ms+=1000;work();eta_status_show(&fake_led,&eta_status_attr,b);printf("no_contributors.status=%s",b);
 full_acquire();now_ms=eta_state.deadline;work();eta_status_show(&fake_led,&eta_status_attr,b);printf("expired.status=%s",b);
 full_acquire();eta_state.fault=-5;now_ms+=1000;work();eta_status_show(&fake_led,&eta_status_attr,b);printf("fault.status=%s",b);
 full_acquire();CHECK(!eta_adapter_suspend(&fake_pdev.dev));CHECK(!eta_adapter_resume(&fake_pdev.dev));now_ms+=1000;work();
 eta_status_show(&fake_led,&eta_status_attr,b);printf("resumed.status=%s",b);
}
static void test_read_only_telemetry(void)
{
 test_telemetry_denied_scenes();test_telemetry_missing_and_config();test_telemetry_pm_and_power();
}
