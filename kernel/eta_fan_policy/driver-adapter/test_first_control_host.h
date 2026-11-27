/* Stage4 narrow first-control regressions; all device APIs remain fakes. */
static void test_stage4_status_and_zero(void)
{
 char b[4096];unsigned int i;int sw,lw;eta_u64 epoch;
 full_acquire();CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);
 CHECK(strstr(b,"fusionValid=1 fusionMc=31000 demand=12 policyKind=1 cooling=0"));
 CHECK(strstr(b,"samplePeriodMs=1000 sampleAgeMs=0 sampleCompletedAtMs=1000 sampleDurationMs=0 sampleFreshMs=15000"));
 CHECK(strstr(b,"candidateLabel=" ETA_FAN_CANDIDATE_LABEL));
 now_ms=2000;configure_v2(1,1,127,1,5000,2);CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"sampleAgeMs=1000"));
 /* Sampling slower than 5 s is not a UI-only arbitrary stale threshold.
  * Keep the real 5 s lease fresh by renewing, but don't sample for 6 s here. */
 now_ms=4000;configure_v2(1,1,127,1,5000,2);now_ms=7000;configure_v2(1,1,127,1,5000,2);
 CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"ready=1"));CHECK(strstr(b,"sampleAgeMs=6000"));CHECK(strstr(b,"fusionValid=1 fusionMc=31000 demand=12"));
 now_ms=eta_state.deadline;CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"fusionValid=0 fusionMc=-1 demand=-1 policyKind=0 cooling=0"));
 /* Full-owner charge below start owns zero, never reseeds the retained OEM
  * level, and doesn't interpret a valid target=0 as has_target=false. */
 start();power_status=1;usb_online=1;temperature=29000;req=6;enabled=1;
 configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.output.has_target && eta_state.output.target==0);
 CHECK(eta_state.owned && !enabled && eta_state.ramp.requested_level==0);epoch=eta_owner_epoch;sw=state_writes;lw=level_writes;
 for(i=2;i<=12;++i) { now_ms=i*1000ULL;configure_v2(1,1,127,1,5000,2);work();CHECK(eta_state.owned && eta_state.output.has_target && !eta_state.output.target); }
 CHECK(eta_owner_epoch==epoch && state_writes==sw && level_writes==lw && !enabled);
 CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"target=0 output=0 requestedLevel=6 physicalState=0"));CHECK(strstr(b,"fusionValid=1 fusionMc=29000 demand=0 policyKind=2 cooling=0"));
 /* has_target=false is an invalid/safety exit, not a zero-demand owner. */
 thermal_read_fail_mask=1;now_ms=13000;work();CHECK(!eta_state.output.has_target && !eta_state.owned && eta_state.output.hard_exit);
 sw=state_writes;work();CHECK(state_writes==sw);
 full_acquire();temperature=28000;now_ms=2000;work();CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"demand=12 policyKind=1 cooling=1"));
}
static void test_stage4_readback_fault_release(void)
{
 unsigned int bit;int sw,lw;char b[4096];
 for(bit=1;bit<=4;bit<<=1) {
  full_acquire();sw=state_writes;lw=level_writes;readback_fail_mask=bit;now_ms=2000;work();
  CHECK(eta_state.fault==-5 && !eta_state.owned && !enabled && state_writes==sw+1);CHECK(level_writes==lw);
  work();CHECK(state_writes==sw+1 && level_writes==lw);
  CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(state_writes==sw+1);CHECK(strstr(b,"ready=0"));
  full_acquire();sw=state_writes;lw=level_writes;readback_fail_mask=bit;
  CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(eta_state.fault==-5 && !eta_state.owned && !enabled && state_writes==sw+1);
  CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);work();CHECK(state_writes==sw+1 && level_writes==lw);
 }
 /* An effective derate that reads successfully is never a fault or a reason
  * to retry level_store; only actual read failure is fail-closed. */
 full_acquire();lw=level_writes;sw=state_writes;effective=0;now_ms=2000;work();
 CHECK(eta_state.owned && !eta_state.fault && level_writes==lw && state_writes==sw);
}
static void test_daily_normal_step_integration(void)
{
 char b[4096];unsigned int i;int n;
 const char *config="2 1 1 5000 2 1 2 127 1 0 2 36000 10 1 1 1 1 2 30000 6 1000 20 600000 0 400000 0 2 battery board_temp fan_temp svooc_mos_btb_usr\n";
 const int temperatures[]={41000,43000,42000,41000,40000};
 const unsigned int targets[]={18,20,20,18,16};
 start();temperature=39000;temperature_offset[1]=3000;temperature_offset[2]=2000;temperature_offset[3]=1388;
 n=(int)strlen(config);CHECK(eta_config_store(&fake_led,&eta_config_attr,config,(size_t)n)==n);work();
 CHECK(eta_state.owned && eta_state.output.fusion.temperature_mc==40597 && eta_state.output.target==16);
 CHECK(eta_state.output.fusion.contributor_mask==15 && req==6 && enabled);
 CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"fusionValid=1 fusionMc=40597 demand=16 policyKind=1 cooling=0"));
 for(i=2;i<=60;++i) {
  now_ms=i*1000ULL;memset(temperature_offset,0,sizeof(temperature_offset));
  if(i<=6)temperature=temperatures[i-2];
  else { temperature=39000;temperature_offset[1]=3000;temperature_offset[2]=2000;temperature_offset[3]=1388; }
  CHECK(eta_config_store(&fake_led,&eta_config_attr,config,(size_t)n)==n);work();
  CHECK(eta_state.owned && eta_state.output.target==(i<=6?targets[i-2]:16));
  CHECK(effective==2); /* derated readback must not reseed/up-regulate the ramp */
 }
 CHECK(req==16 && enabled && eta_state.ramp.requested_level==16); /* unchanged .2 level/s ramp */
 CHECK(eta_status_show(&fake_led,&eta_status_attr,b)>0);CHECK(strstr(b,"fusionValid=1 fusionMc=40597 demand=16"));
 temperature=45000;memset(temperature_offset,0,sizeof(temperature_offset));now_ms=61000;work();
 CHECK(!eta_state.owned && !enabled && !eta_state.policy.daily_step); /* same raw battery guard */
}
static void test_stage4_first_control(void)
{
 test_stage4_status_and_zero();test_stage4_readback_fault_release();test_daily_normal_step_integration();
}
