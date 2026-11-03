package dev.eta.acefan;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-stage evidence, independent of Android; errors never disable other capabilities. */
public final class RuntimeEvidence {
    private static final class Entry {
        long calls,failures,at,lastFailureAt;String failureDetail="";String state="未触发",member="",detail="";
    }
    private final Map<String,Entry> stages=new LinkedHashMap<String,Entry>();
    private String lastStage="",lastError="";
    private long revision,attempts,requests,confirmed;
    private String progress="等待接口解析";
    private Entry entry(String stage){Entry e=stages.get(stage);if(e==null){e=new Entry();stages.put(stage,e);}return e;}
    public synchronized void success(String stage,String member,String detail,long at){
        Entry e=entry(stage);e.calls++;e.state="成功";e.member=trim(member,180);e.detail=trim(detail,200);e.at=at;revision++;
    }
    public synchronized void failure(String stage,String member,Throwable error,long at){
        Entry e=entry(stage);e.calls++;e.failures++;e.state="失败";e.member=trim(member,180);e.detail=FanServiceCompat.summary(error);e.at=at;
        lastStage=stage;lastError=e.member+" | "+e.detail;e.lastFailureAt=at;e.failureDetail=lastError;revision++;
    }
    public synchronized void unavailable(String stage,String detail){
        Entry e=entry(stage);e.state="不可用";e.detail=trim(detail,200);revision++;
    }
    public synchronized void ready(){progress="接口已安装，等待真实场景";revision++;}
    public synchronized void attempted(long id,int old,int chosen,long at){
        attempts++;progress="候选已应用";success("候选应用","决策 #"+id,old+" → "+chosen,at);
    }
    public synchronized void requested(long id,int request,boolean accepted,long at){
        requests++;progress=accepted?"原厂状态确认":"控制调用完成";if(accepted)confirmed++;
        success("控制反馈","决策 #"+id,"请求="+request+"；状态确认="+accepted+"；不代表实测转速",at);
    }
    public synchronized String progress(){return progress;}
    public synchronized long revision(){return revision;}
    public synchronized String lastStage(){return lastStage;}
    public synchronized String lastError(){return lastError;}
    public synchronized String counters(){return "候选应用="+attempts+"；同轮控制完成="+requests+"；原厂状态确认="+confirmed;}
    public synchronized String report(){
        StringBuilder b=new StringBuilder(progress).append("；").append(counters()).append('\n');
        for(Map.Entry<String,Entry> p:stages.entrySet()){
            Entry e=p.getValue();b.append(p.getKey()).append("：").append(e.state).append("；调用=").append(e.calls).append("；失败=").append(e.failures).append("；elapsed=").append(e.at).append("ms；").append(e.member).append("；").append(e.detail);if(e.failures>0)b.append("；最近失败elapsed=").append(e.lastFailureAt).append("ms；").append(e.failureDetail);b.append('\n');
        }return b.toString();
    }
    private static String trim(String s,int limit){if(s==null)return "";return s.length()>limit?s.substring(0,limit):s;}
}
