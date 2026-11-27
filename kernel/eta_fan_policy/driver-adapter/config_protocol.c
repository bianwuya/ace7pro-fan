#include "config_protocol.h"
static int eta_cfg_space(char c) { return c==' ' || c=='\t' || c=='\n' || c=='\r'; }
static int eta_cfg_equal(const char *a,const char *b)
{
    unsigned int i=0;
    while (a[i] && a[i]==b[i]) ++i;
    return a[i]==b[i];
}
static int eta_cfg_contains(const char *s,const char *word)
{
    unsigned int i,j;
    for(i=0;s[i];++i) {
        for(j=0;word[j] && s[i+j];++j) {
            char c=s[i+j]; if(c>='A' && c<='Z') c=(char)(c+'a'-'A');
            if(c!=word[j]) break;
        }
        if(!word[j]) return 1;
    }
    return 0;
}
static int eta_cfg_settings(const struct eta_fan_settings *s,int charge)
{
    unsigned int i,total=0;
    if(s->enabled!=ETA_FAN_NO && s->enabled!=ETA_FAN_YES) return 0;
    if(s->start_mc<20000 || s->start_mc>50000 || s->floor<1 || s->floor>20) return 0;
    for(i=0;i<4;++i) { if(s->weight[i]>ETA_FAN_WEIGHT_MAX) return 0; if(s->weight[i]) ++total; }
    if(!total) return 0;
    if(charge && (s->gain_milli<1 || s->gain_milli>ETA_FAN_GAIN_MAX || s->ceiling<s->floor || s->ceiling>20)) return 0;
    return 1;
}
int eta_adapter_parse_config(const char *line,eta_u32 count,struct eta_adapter_config *out)
{
    struct eta_adapter_config c={0};
    eta_u32 values[27]={0},i,j,pos=0,fields=26;
    if(!line || !out || count<1 || count>1024) return -22;
    for(i=0;i<fields;++i) {
        eta_u32 value=0,digits=0;
        while(pos<count && eta_cfg_space(line[pos])) ++pos;
        while(pos<count && line[pos]>='0' && line[pos]<='9') {
            eta_u32 digit=(eta_u32)(line[pos]-'0');
            if(value>(0xffffffffU-digit)/10U) return -22;
            value=value*10U+digit; ++pos; ++digits;
        }
        if(!digits || pos>=count || !eta_cfg_space(line[pos])) return -22;
        values[i]=value;
        if(i==0) { if(value==2) fields=27; else if(value!=1) return -22; }
    }
    c.protocol_version=values[0];
    c.strategy=values[0]==1?ETA_STRATEGY_ADDITIVE:values[26];
    if(c.strategy>ETA_STRATEGY_FULL_OWNER) return -22;
    for(i=0;i<4;++i) {
        j=0; while(pos<count && eta_cfg_space(line[pos])) ++pos;
        while(pos<count && !eta_cfg_space(line[pos])) {
            char v=line[pos++];
            if(!((v>='a'&&v<='z')||(v>='A'&&v<='Z')||(v>='0'&&v<='9')||v=='_'||v=='-') || j>=63) return -22;
            c.zone[i][j++]=v;
        }
        if(!j || eta_cfg_contains(c.zone[i],"shell") || eta_cfg_contains(c.zone[i],"skin") ||
           eta_cfg_contains(c.zone[i],"virtual") || eta_cfg_contains(c.zone[i],"fake") ||
           eta_cfg_contains(c.zone[i],"horae")) return -22;
        for(j=0;j<i;++j) if(eta_cfg_equal(c.zone[i],c.zone[j])) return -22;
    }
    while(pos<count && eta_cfg_space(line[pos])) ++pos;
    if(pos!=count || !values[1] || values[2]>1 || values[3]>5000 ||
       (values[2] && !values[3]) || values[4]>2 || values[5]>3 || values[6]>2 ||
       values[7]>127 || values[8]>1 || values[9]>6) return -22;
    c.revision=values[1]; c.enabled=values[2]; c.lease_ms=values[3]; c.main_enabled=values[4];
    c.mode=values[5]; c.auto_enabled=values[6]; c.gate_mask=values[7];
    c.thermal_status_valid=values[8]; c.thermal_status=(eta_s32)values[9];
    c.policy.version=c.revision;
    c.policy.daily.enabled=(enum eta_fan_flag)values[10]; c.policy.daily.start_mc=(eta_s32)values[11];
    c.policy.daily.floor=values[12]; for(i=0;i<4;++i) c.policy.daily.weight[i]=values[13+i];
    c.policy.charge.enabled=(enum eta_fan_flag)values[17]; c.policy.charge.start_mc=(eta_s32)values[18];
    c.policy.charge.floor=values[19]; c.policy.charge.gain_milli=values[20]; c.policy.charge.ceiling=values[21];
    for(i=0;i<4;++i) c.policy.charge.weight[i]=values[22+i];
    if(!eta_cfg_settings(&c.policy.daily,0) || !eta_cfg_settings(&c.policy.charge,1)) return -22;
    *out=c; return 0;
}
int eta_adapter_permissions(const struct eta_adapter_config *c,eta_u64 now,eta_u64 deadline)
{
    return c && c->enabled && (c->strategy==ETA_STRATEGY_ADDITIVE || c->strategy==ETA_STRATEGY_FULL_OWNER) &&
        now<deadline && c->main_enabled==ETA_FAN_YES &&
        c->mode==ETA_MODE_AUTO && c->auto_enabled==ETA_FAN_YES && c->gate_mask==127 &&
        c->thermal_status_valid && c->thermal_status==0;
}
