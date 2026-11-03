package dev.eta.acefan;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Device-specific policy hook: candidate-only override, no fan/thermal/sysfs writes. */
public final class FanHook implements IXposedHookLoadPackage {
    private static final String VERSION=BuildInfo.HOOK_VERSION;
    private static final String TARGET="com.oplus.exsystemservice";
    private static volatile FanServiceCompat bindings;
    private static volatile FanServiceCompat.Bootstrap bootstrap;
    private static final AtomicBoolean resolving=new AtomicBoolean();
    private static volatile String compatibilityStatus="pending",compatibilityDetail="等待原厂风扇初始化",targetVersion="未知";
    private static volatile String compatibilityStage="",targetSha256="不参与检测（未计算）",targetPath="暂无",hardwareGate="未知";
    private static volatile long lastFailurePush;
    private static volatile String lastFailureLog="";
    private static final RuntimeEvidence evidence=new RuntimeEvidence();
    private static volatile Method selectedCandidate;
    private static final AtomicLong decisionIds=new AtomicLong();
    private static final java.util.List<XC_MethodHook.Unhook> installed=new java.util.ArrayList<XC_MethodHook.Unhook>();
    private static final Uri API=Uri.parse("content://dev.eta.acefan.data");
    private static final AtomicBoolean started=new AtomicBoolean(), queued=new AtomicBoolean();
    private static final ChargeCurve chargeCurve=new ChargeCurve();
    private static final WakeHandoff handoff=new WakeHandoff();
    private static volatile Context context;
    private static volatile Handler originalHandler,worker;
    private static volatile Object aeolus;
    private static volatile ClassLoader loader;
    private static volatile JSONObject snapshot;
    private static volatile long sampleAt,configVersion,lastConfigAt,batteryEventAt;
    private static volatile long sampleIntervalMs=Sampling.ACTIVE_MS,lastPushAt;
    private static volatile boolean pushedOnce;
    private static volatile String lastPushKey="";
    private static volatile double[] lastPushTemps={Double.NaN,Double.NaN,Double.NaN,Double.NaN};
    private static volatile boolean enabled,compatible,active,powerDisconnected,eventChargeBlocked;
    private static volatile String hardReason="等待采样",decisionReason="等待首次决策",phase="oem";
    private static volatile int lastOem=-1,lastCandidate=-1,lastRequest=-1;
    private static String previousTrigger="",previousDecision="";
    private static final ControlCycle<AppliedDecision> controlCycle=new ControlCycle<AppliedDecision>();
    private static final ThreadLocal<CandidateFrame> candidateFrame=new ThreadLocal<CandidateFrame>();
    private static final class CandidateFrame {
        final CandidateFrame parent;boolean strategyRead;
        CandidateFrame(CandidateFrame p){parent=p;}
    }
    private static final class AppliedDecision {
        final long id,generation,at;final int mode;
        AppliedDecision(int mode,long generation,long at){this.id=decisionIds.incrementAndGet();this.mode=mode;this.generation=generation;this.at=at;}
    }

    private static final class Gate {
        final int mode;
        final String reason;
        final Boolean locked;
        Gate(int m,String r,Boolean l){mode=m;reason=r;locked=l;}
        boolean allowed(){return mode!=WakeHandoff.OEM && reason==null;}
    }

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if(!TARGET.equals(p.packageName)||!TARGET.equals(p.processName))return;
        loader=p.classLoader;
        XposedHelpers.findAndHookMethod(android.app.Application.class,"attach",Context.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam event){
                if(event.hasThrowable()||!(event.args[0] instanceof Context))return;
                try{startWorker((Context)event.args[0]);}catch(Throwable t){failure("采样初始化","Application.attach",t);}
            }
        });
        String market=marketName();
        boolean family=CompatibilityCatalog.hardwareSupported(Build.MODEL,Build.DEVICE,market);
        hardwareGate=(family?"通过：":"不通过：")+Build.MODEL+" / "+Build.DEVICE+" / "+market+"（ACE 7 系列）";
        if(!family){compatibilityStatus="outside-family";compatibilityStage="设备系列识别";compatibilityDetail=hardwareGate;return;}
        if(!CompatibilityCatalog.bridgeSupported(XposedBridge.getXposedVersion())){
            compatibilityStatus="setup-failed";compatibilityStage="框架接口";
            compatibilityDetail="需要传统 Xposed API ≥82，当前 "+XposedBridge.getXposedVersion();return;
        }
        try{
            bootstrap=FanServiceCompat.bootstrap(loader,Context.class,Handler.class);
            evidence.success("初始化入口",FanServiceCompat.methodKey(bootstrap.initialize),"已定位，等待原厂调用",SystemClock.elapsedRealtime());
            XposedBridge.hookMethod(bootstrap.initialize,new XC_MethodHook(){
                @Override protected void afterHookedMethod(final MethodHookParam event){
                    if(event.hasThrowable()){failure("原厂初始化",FanServiceCompat.methodKey(bootstrap.initialize),event.getThrowable());return;}
                    if(event.args[0]==null||!(event.args[1] instanceof Handler))return;
                    try{
                        startWorker((Context)event.args[0]);
                        if(compatible||!resolving.compareAndSet(false,true))return;
                        final Handler h=(Handler)event.args[1];
                        final Object engine=event.thisObject;
                        // Read and probe live OEM objects on their own handler, after initialization.
                        h.post(new Runnable(){@Override public void run(){
                            try{
                                originalHandler=h;aeolus=engine;
                                FanServiceCompat found=FanServiceCompat.resolve(bootstrap,aeolus,loader,Context.class,Handler.class);
                                bindings=found;installPolicyHooks(found);
                                compatibilityStatus="ready";compatibilityDetail="实际接口已接入，等待允许场景中的原厂调用；无指纹门槛";
                                compatible=true;evidence.ready();compatibilityStage="Hook安装";sampleSoon();requestRefresh();
                            }catch(Throwable t){setupFailure(t);}
                            finally{resolving.set(false);}
                        }});
                    }catch(Throwable t){resolving.set(false);setupFailure(t);}
                }
            });
        }catch(Throwable t){setupFailure(t);}
    }
    private static String marketName(){
        StringBuilder b=new StringBuilder();
        for(String key:new String[]{"ro.vendor.oplus.market.name","ro.oplus.market.name","ro.product.marketname","ro.product.vendor.marketname"}){
            try{String v=(String)XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.SystemProperties",null),"get",key,"");if(v!=null&&!v.isEmpty())b.append(v).append(' ');}catch(Throwable ignored){}
        }return b.toString().trim();
    }
    private static void hookCycle(final Method m){
        try{installed.add(XposedBridge.hookMethod(m,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                p.setObjectExtra("acefan.cycle",controlCycle.enter());
            }
            @Override @SuppressWarnings("unchecked") protected void afterHookedMethod(MethodHookParam p){
                controlCycle.exit((ControlCycle.Token<AppliedDecision>)p.getObjectExtra("acefan.cycle"));
            }
        }));}catch(Throwable t){failure("调用关联安装",FanServiceCompat.methodKey(m),t);}
    }
    private static synchronized void installPolicyHooks(final FanServiceCompat found)throws Throwable{
        // Compute can return before apply; evidence propagates to the enclosing refresh.
        hookCycle(bootstrap.refresh);
        for(Method m:FanServiceCompat.methods(found.orchestratorObject.getClass(),void.class))
            if(Modifier.isProtected(m.getModifiers()))hookCycle(m);
        if(found.candidates.size()>1){
            for(final Method getter:found.strategyGetters){
                try{installed.add(XposedBridge.hookMethod(getter,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        CandidateFrame f=candidateFrame.get();if(f!=null&&!p.hasThrowable())f.strategyRead=true;
                    }
                }));}catch(Throwable t){failure("候选辨认安装",FanServiceCompat.methodKey(getter),t);}
            }
        }
        int candidateHooks=0,controllerHooks=0;
        for(final Method candidate:found.candidates){
            try{installed.add(XposedBridge.hookMethod(candidate,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){candidateFrame.set(new CandidateFrame(candidateFrame.get()));}
                @Override protected void afterHookedMethod(MethodHookParam p){
                    CandidateFrame frame=candidateFrame.get();candidateFrame.set(frame==null?null:frame.parent);
                    if(p.thisObject!=found.orchestratorObject)return;
                    String member=FanServiceCompat.methodKey(candidate);
                    if(p.hasThrowable()){failure("原厂候选调用",member,p.getThrowable());return;}
                    if(!(p.getResult() instanceof Integer))return;
                    if(selectedCandidate==null){
                        if(found.candidates.size()>1&&(frame==null||!frame.strategyRead)){
                            evidence.success("候选观察",member,"返回="+p.getResult()+"；本次未见策略读取，继续观察",SystemClock.elapsedRealtime());return;
                        }
                        selectedCandidate=candidate;evidence.success("候选定位",member,"已观察原厂调用"+(frame!=null&&frame.strategyRead?"及策略读取":"；唯一入口"),SystemClock.elapsedRealtime());
                    }
                    if(!candidate.equals(selectedCandidate))return;
                    String stage="候选读取";
                    final int original=(Integer)p.getResult();
                    try{
                        int old=original;lastOem=old;
                        evidence.success(stage,member,"原厂返回="+old,SystemClock.elapsedRealtime());
                        Gate gate=evaluateGate();if(!gate.allowed()){observeOriginal(old,gate.reason);return;}
                        stage="自动模式实读";Boolean automatic=null;
                        try{automatic=found.readAutomatic();}catch(Throwable t){failure(stage,"FanMode",t);}
                        if(Boolean.FALSE.equals(automatic)){cancelContinuation();observeOriginal(old,"原厂接口确认手动模式，保留用户设定");return;}
                        stage="场景读取";String name=found.readStrategy();
                        evidence.success(stage,String.valueOf(found.choice),name,SystemClock.elapsedRealtime());
                        if(!FanServiceCompat.allowedScene(name)){cancelContinuation();observeOriginal(old,"保留原厂场景："+name);return;}
                        // Missing telemetry is unknown, not a global incompatibility verdict.
                        stage="健康状态读取";
                        String hardware=snapshot==null||snapshot.isNull("fanStatus")?"":snapshot.optString("fanStatus","");
                        Integer ev=null;
                        try{ev=found.event();}catch(Throwable t){failure(stage,String.valueOf(found.lastEvent),t);}
                        evidence.success(stage,"status / mLastUEvent","状态="+(hardware.isEmpty()?"未知":hardware)+"；event="+ev,SystemClock.elapsedRealtime());
                        if((!hardware.isEmpty()&&!"NORMAL".equals(hardware))||(ev!=null&&ev.intValue()>=3&&ev.intValue()<=7)){
                            cancelContinuation();observeOriginal(old,"原厂故障状态："+hardware+" / event="+ev);return;
                        }
                        stage="档位上限读取";int maximum=found.readMaximum();
                        evidence.success(stage,String.valueOf(found.maximumField!=null?found.maximumField:found.maxLevel),"实际上限="+maximum,SystemClock.elapsedRealtime());
                        int desired=chargeCurve.level();int chosen=Math.max(old,Math.min(maximum,desired));
                        if(chosen<=old){observeOriginal(old,"当前无需额外增风：原厂="+old+"，需求="+desired);return;}
                        stage="候选应用";
                        AppliedDecision decision=new AppliedDecision(gate.mode,handoff.generation(),SystemClock.elapsedRealtime());
                        p.setResult(chosen);controlCycle.attach(decision);
                        evidence.attempted(decision.id,old,chosen,decision.at);
                        compatibilityStatus="attempted";active=true;lastCandidate=chosen;phase=WakeHandoff.label(gate.mode);
                        decisionReason="候选已应用："+old+" → "+chosen+"；等待同轮原厂控制反馈";
                        traceDecision(name,old,chosen,gate.locked);sampleSoon();
                    }catch(Throwable t){p.setResult(original);controlCycle.take();failure(stage,member,t);observeOriginal(original,"本次"+stage+"失败，下次原厂调用继续；可导出失败报告");}
                }
            }));candidateHooks++;}catch(Throwable t){failure("候选Hook安装",FanServiceCompat.methodKey(candidate),t);}
        }
        if(candidateHooks==0){
            for(XC_MethodHook.Unhook u:installed)try{u.unhook();}catch(Throwable ignored){}
            installed.clear();throw new IllegalStateException("候选 Hook 均未安装；见逐环节失败记录");
        }
        for(final Method setter:found.setters){
            try{installed.add(XposedBridge.hookMethod(setter,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    AppliedDecision d=controlCycle.take();
                    if(d!=null&&SystemClock.elapsedRealtime()-d.at<2000L)p.setObjectExtra("acefan.decision",d);
                    lastRequest=(Integer)p.args[0];
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    AppliedDecision d=(AppliedDecision)p.getObjectExtra("acefan.decision");
                    if(p.hasThrowable()){failure("原厂控制调用",FanServiceCompat.methodKey(setter),p.getThrowable());return;}
                    if(d==null)return;
                    try{
                        int request=(Integer)p.args[0];Boolean updated=found.confirmed(p.thisObject);Integer level=found.level(p.thisObject);
                        boolean accepted=Boolean.TRUE.equals(updated)&&level!=null&&level.intValue()==request;
                        evidence.requested(d.id,request,accepted,SystemClock.elapsedRealtime());
                        compatibilityStatus=accepted?"confirmed":"request-observed";
                        if(request<=0){cancelContinuation();active=false;decisionReason="原厂后续要求停转，已保留";}
                        else{
                            decisionReason="同轮原厂请求="+request+(accepted?"；原厂状态已确认":"；调用完成，确认字段未知或未确认")+"；不代表实测转速";
                            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                            if(d.mode==WakeHandoff.SCREEN_OFF&&accepted&&enabled&&!powerDisconnected&&!eventChargeBlocked&&pm!=null&&!pm.isInteractive())handoff.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,request,d.generation);
                        }sampleSoon();
                    }catch(Throwable t){failure("控制反馈读取",FanServiceCompat.methodKey(setter),t);}
                }
            }));controllerHooks++;}catch(Throwable t){failure("控制观察安装",FanServiceCompat.methodKey(setter),t);}
        }
        evidence.success("Hook安装","候选="+candidateHooks+"；控制观察="+controllerHooks,"等待真实调用",SystemClock.elapsedRealtime());
        try{evidence.success("档位上限读取","ConfigParser","实际值="+found.readMaximum(),SystemClock.elapsedRealtime());}
        catch(Throwable t){failure("档位上限读取","ConfigParser",t);}
        try{evidence.success("场景接口实读",String.valueOf(found.choice),found.readStrategy(),SystemClock.elapsedRealtime());}
        catch(Throwable t){failure("场景读取",String.valueOf(found.choice),t);}
        try{Boolean auto=found.readAutomatic();if(auto==null)evidence.unavailable("自动模式实读","入口缺失，沿用实际设置与原厂场景");else evidence.success("自动模式实读","FanMode","auto="+auto,SystemClock.elapsedRealtime());}
        catch(Throwable t){failure("自动模式实读","FanMode",t);}
        if(found.updated==null)evidence.unavailable("控制确认","确认字段缺失，候选仍可运行；不启用未经确认的锁屏衔接");
        if(found.setters.isEmpty())evidence.unavailable("控制观察","无控制请求入口，候选仍可运行；后续反馈未知");
    }
    private static void setupFailure(Throwable t){
        compatibilityStatus="setup-failed";compatibilityDetail=FanServiceCompat.summary(t);
        String stage=FanServiceCompat.stageOf(t);if(stage.isEmpty())stage="接口接入";
        failure(stage,"接口解析/安装",t);
    }
    private static void failure(String stage,String member,Throwable t){
        evidence.failure(stage,member,t,SystemClock.elapsedRealtime());
        compatibilityStage=stage;compatibilityDetail=FanServiceCompat.summary(t);
        String key=stage+"|"+member+"|"+compatibilityDetail;
        if(!key.equals(lastFailureLog)){lastFailureLog=key;XposedBridge.log("ACEFan "+VERSION+" ["+stage+"] "+member+": "+compatibilityDetail);}
        long now=SystemClock.elapsedRealtime();
        if(!"报告回传".equals(stage)&&!"遥测采样".equals(stage)&&!"使用条件读取".equals(stage)&&now-lastFailurePush>2000L){lastFailurePush=now;sampleSoon();}
    }

    private static void startWorker(Context c) throws Throwable {
        if(!started.compareAndSet(false,true))return;
        context=c.getApplicationContext()==null?c:c.getApplicationContext();
        final Handler h=new Handler(android.os.Looper.getMainLooper());
        try{targetVersion=context.getPackageManager().getPackageInfo(TARGET,0).versionName;}catch(Throwable ignored){}
        HandlerThread thread=new HandlerThread("ACEFan-Telemetry");thread.start();worker=new Handler(thread.getLooper());
        IntentFilter f=new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);f.addAction(Intent.ACTION_POWER_CONNECTED);f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(i==null)return;
            String action=i.getAction();
            if(Intent.ACTION_BATTERY_CHANGED.equals(action)){
                batteryEventAt=SystemClock.elapsedRealtime();
                int plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0),status=i.getIntExtra(BatteryManager.EXTRA_STATUS,0);
                eventChargeBlocked=(plugged&(BatteryManager.BATTERY_PLUGGED_AC|BatteryManager.BATTERY_PLUGGED_USB))==0 || (plugged&BatteryManager.BATTERY_PLUGGED_WIRELESS)!=0 || status!=BatteryManager.BATTERY_STATUS_CHARGING;
                if(eventChargeBlocked){active=false;cancelContinuation();requestRefresh();}
            }else if(Intent.ACTION_POWER_DISCONNECTED.equals(action)){
                powerDisconnected=true;eventChargeBlocked=true;active=false;snapshot=null;cancelContinuation();requestRefresh();
            }else if(Intent.ACTION_POWER_CONNECTED.equals(action)){
                powerDisconnected=false;
            }
            if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_SCREEN_OFF.equals(action)||Intent.ACTION_USER_PRESENT.equals(action)){
                // SCREEN_ON is not an unlock: retain fresh telemetry and let the live keyguard gate decide.
                XposedBridge.log("ACEFan "+VERSION+": event="+action+" elapsed="+SystemClock.elapsedRealtime());
                requestRefresh();
            }
            sampleSoon();
        }};
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,null,h,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,f,null,h);
        if(Build.VERSION.SDK_INT>=33)try{Api33.listen(h);}catch(Throwable t){
            XposedBridge.log("ACEFan "+VERSION+": keyguard listener unavailable; using live state, user-present events and sampling");
        }
        worker.postDelayed(new Runnable(){@Override public void run(){
            if("pending".equals(compatibilityStatus) && !resolving.get()){
                compatibilityDetail="框架已注入，但原厂风扇初始化尚未出现；请检查原厂服务/开关和版本";sampleSoon();
            }
        }},20000L);
        sampleSoon();
        targetPath=context.getPackageCodePath();targetSha256="不参与检测（未计算）";
        XposedBridge.log("ACEFan "+VERSION+": telemetry initialized; enhancement uses live interfaces and per-stage runtime evidence");
    }

    /** Do not resolve API33 listener types when running Android 9–12. */
    private static final class Api33 {
        static void listen(final Handler h){
            KeyguardManager km=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
            if(km!=null)km.addKeyguardLockedStateListener(new Executor(){@Override public void execute(Runnable r){h.post(r);}},new KeyguardManager.KeyguardLockedStateListener(){
                @Override public void onKeyguardLockedStateChanged(boolean locked){requestRefresh();sampleSoon();}
            });
        }
    }

    private static final Runnable tick=new Runnable(){@Override public void run(){
        long next=Sampling.ACTIVE_MS;
        try{
            JSONObject s=Telemetry.sample(context);
            Bundle cfg=api("config",null);
            if(cfg!=null){enabled=cfg.getBoolean("enabled",false);configVersion=cfg.getLong("version");lastConfigAt=SystemClock.elapsedRealtime();}
            else{evidence.unavailable("配置读取","Provider 未返回配置");if(SystemClock.elapsedRealtime()-lastConfigAt>Sampling.staleMs(sampleIntervalMs))enabled=false;}
            sampleAt=SystemClock.elapsedRealtime();snapshot=s;
            hardReason=workerHardEligibility(s);
            evidence.success("运行条件","供电/温度/开关",hardReason,SystemClock.elapsedRealtime());
            Gate gate=evaluateGate();
            chargeCurve.update(s.optDouble("battery",Double.NaN),SystemClock.elapsedRealtime(),gate.allowed());
            String trigger=hardReason+"|"+gate.mode+"|"+configVersion+"|"+chargeCurve.level()+"|"+compatibilityStatus;
            if(!trigger.equals(previousTrigger)){previousTrigger=trigger;requestRefresh();}
            if(!gate.allowed()){active=false;decisionReason=gate.reason;}
            long tier=Sampling.interval(gate.allowed(),"允许".equals(hardReason));
            sampleIntervalMs=tier;next=tier;
            long remaining=handoff.remaining(SystemClock.elapsedRealtime());
            if(remaining>0)next=Math.min(next,remaining+50L);
            s.put("hook","已注入");s.put("hookVersion",VERSION);s.put("build",Build.DISPLAY);s.put("compatible",compatible);s.put("enabled",enabled);s.put("configVersion",configVersion);
            FrameworkCompat.putRuntime(s);
            s.put("model",Build.MODEL);s.put("device",Build.DEVICE);s.put("sdk",Build.VERSION.SDK_INT);
            s.put("firmwareProfile",compatible?FanServiceCompat.PROFILE:"none");
            s.put("compatibilityStatus",compatibilityStatus);
            s.put("compatibilityStage",compatibilityStage);
            s.put("compatibilityDetail",compatibilityDetail);s.put("targetVersion",targetVersion);
            s.put("targetPackage",TARGET);s.put("targetSha256",targetSha256);s.put("targetPath",targetPath);
            s.put("knownApk","不参与检测");
            s.put("runtimeEvidence",bounded(evidence.report(),6400));
            s.put("runtimeProgress",evidence.progress());
            s.put("runtimeCounters",evidence.counters());
            s.put("lastFailureStage",evidence.lastStage());
            s.put("lastFailureDetail",bounded(evidence.lastError(),400));
            s.put("hardwareGate",hardwareGate);
            s.put("targetScope","com.oplus.exsystemservice");
            StringBuilder tr=new StringBuilder();
            for(String line:FanServiceCompat.lastTrace()){
                if(tr.length()+line.length()>1800){tr.append("\n…（轨迹已截断）");break;}
                if(tr.length()>0)tr.append("\n");tr.append(line);
            }
            s.put("compatibilityTrace",tr.toString());
            s.put("phase",phase);s.put("handoffRemainingMs",remaining);s.put("active",active);s.put("reason",decisionReason);
            s.put("oem",lastOem<0?JSONObject.NULL:lastOem);s.put("candidate",lastCandidate<0?JSONObject.NULL:lastCandidate);s.put("request",lastRequest<0?JSONObject.NULL:lastRequest);s.put("curve",chargeCurve.level());
            double[] temps={s.optDouble("battery",Double.NaN),s.optDouble("board",Double.NaN),s.optDouble("fan",Double.NaN),s.optDouble("mos",Double.NaN)};
            String key=evidence.counters()+"|"+evidence.lastStage()+"|"+evidence.lastError()+"|"+trigger+"|"+active+"|"+decisionReason+"|"+s.optString("fanStatus","")+"|"+s.optInt("effective",-1)+"|"+s.optInt("driver",-1)+"|"+s.optBoolean("interactive",false);
            long nowElapsed=SystemClock.elapsedRealtime();
            if(Sampling.shouldPush(!pushedOnce,!key.equals(lastPushKey),nowElapsed-lastPushAt,Sampling.maxDelta(temps,lastPushTemps))){
                Bundle b=new Bundle();b.putString("json",s.toString());
                if(api("push",b)!=null){pushedOnce=true;lastPushAt=nowElapsed;lastPushKey=key;lastPushTemps=temps;}else failure("报告回传","Provider.push",new IllegalStateException("未取得写入回执"));
            }
        }catch(Throwable t){failure("遥测采样","Telemetry/config/push",t);active=false;cancelContinuation();next=Sampling.IDLE_MS;sampleIntervalMs=next;}
        finally{if(worker!=null){worker.removeCallbacks(this);worker.postDelayed(this,next);}}
    }};

    private static String workerHardEligibility(JSONObject s){
        try{
            if(!compatible)return "接口尚未接入，保留原厂："+compatibilityDetail;
            if(!enabled)return "功能关闭，保留原厂";
            if(powerDisconnected||eventChargeBlocked||!s.optBoolean("wired",false)||!s.optBoolean("charging",false))return "非有线充电中，保留原厂";
            if(!s.optBoolean("batteryValid",false)||s.isNull("battery"))return "电池温度不可用";
            if("battery_sticky".equals(s.optString("batterySource"))&&(batteryEventAt==0||SystemClock.elapsedRealtime()-batteryEventAt>60000))return "仅有过期电池广播，保留原厂";
            double t=s.optDouble("battery",Double.NaN);if(Double.isNaN(t)||t<0||t>65)return "温度超出已验证工作区间，保留原厂";
            Integer sw=readSetting("cooling_fan_main_switch_modify");
            if(sw!=null&&sw!=1&&sw!=3)return "用户风扇总开关未允许";
            Integer charge=readSetting("cooling_fan_charge_switch");
            if(charge!=null&&charge!=1)return "原厂充电风扇开关关闭";
            Integer mode=readSetting("cooling_fan_mode_v3");
            if(mode!=null&&mode!=0)return "手动模式，保留用户设定";
            Integer auto=readSetting("cooling_fan_mode_auto_control_switch");
            if(auto!=null&&auto==0)return "原厂自动调速已关闭";
            if(Settings.System.getInt(context.getContentResolver(),"regular_charge_protection_status",0)==1)return "充电保护生效，保留原厂";
            if("true".equals(Settings.Global.getString(context.getContentResolver(),"cooling_fan_service_abort")))return "原厂服务已停用";
            return "允许";
        }catch(Throwable t){failure("使用条件读取","Settings/供电/温度",t);return "本次使用条件读取失败，等待下次采样";}
    }

    private static Integer readSetting(String key){
        String raw=Settings.System.getString(context.getContentResolver(),key);
        if(raw==null){evidence.unavailable("设置:"+key,"未提供，沿用原厂实际场景及下游使能判断");return null;}
        try{return Integer.valueOf(raw);}catch(NumberFormatException e){failure("设置读取",key,e);return null;}
    }

    private static Gate evaluateGate(){
        String why=null;
        long now=SystemClock.elapsedRealtime();
        long stale=Sampling.staleMs(sampleIntervalMs);
        if(!compatible)why="接口尚未接入，保留原厂："+compatibilityDetail;
        else if(!enabled)why="功能关闭，保留原厂";
        else if(powerDisconnected||eventChargeBlocked)why="非有线充电中，保留原厂";
        else if(snapshot==null||now-sampleAt>stale)why="遥测过期，保留原厂";
        else if(now-lastConfigAt>stale)why="配置过期，保留原厂";
        else if(!"允许".equals(hardReason))why=hardReason;
        if(why!=null){cancelContinuation();phase="oem";return new Gate(WakeHandoff.OEM,why,null);}
        Boolean interactive=null,locked=null;
        try{PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm!=null)interactive=pm.isInteractive();}catch(Throwable ignored){}
        try{KeyguardManager km=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);if(km!=null)locked=km.isKeyguardLocked();}catch(Throwable ignored){}
        int mode=handoff.evaluate(now,interactive,locked,true);phase=WakeHandoff.label(mode);
        scheduleUnlockDeadline(mode,now);
        if(mode==WakeHandoff.OEM){
            why=interactive==null?"屏幕状态未知，保留原厂":locked==null?"锁屏状态未知，保留原厂":"亮屏已解锁或无息屏会话，保留原厂";
        }
        return new Gate(mode,why,locked);
    }

    private static final Runnable unlockDeadline=new Runnable(){@Override public void run(){sampleSoon();requestRefresh();}};
    private static void scheduleUnlockDeadline(int mode,long now){
        Handler h=originalHandler;if(h==null)return;
        h.removeCallbacks(unlockDeadline);
        if(mode==WakeHandoff.UNLOCK_GRACE){long remaining=handoff.remaining(now);h.postDelayed(unlockDeadline,Math.max(50L,remaining+25L));}
    }
    private static void cancelContinuation(){
        phase="oem";
        handoff.reset();
        Handler h=originalHandler;if(h!=null)h.removeCallbacks(unlockDeadline);
    }
    private static void observeOriginal(int old,String reason){active=false;lastCandidate=old;decisionReason=reason==null?"保留原厂":reason;traceDecision("original",old,old,null);}
    private static synchronized void traceDecision(String strategy,int old,int chosen,Boolean locked){
        String key=phase+"|"+active+"|"+strategy+"|"+old+"|"+chosen+"|"+decisionReason;
        if(key.equals(previousDecision))return;previousDecision=key;
        XposedBridge.log("ACEFan "+VERSION+": phase="+phase+" locked="+locked+" strategy="+strategy+" oem="+old+" candidate="+chosen+" active="+active+" elapsed="+SystemClock.elapsedRealtime()+" reason="+decisionReason);
    }
    private static void sampleSoon(){Handler w=worker;if(w!=null){w.removeCallbacks(tick);w.post(tick);}}
    private static void requestRefresh(){
        final Handler h=originalHandler;if(h==null||aeolus==null||!queued.compareAndSet(false,true))return;
        h.post(new Runnable(){@Override public void run(){queued.set(false);try{
            if(bootstrap!=null){bootstrap.refresh.invoke(aeolus,"ACE7 Charging Policy");evidence.success("原厂重算",FanServiceCompat.methodKey(bootstrap.refresh),"调用完成；增强="+active,SystemClock.elapsedRealtime());}
        }catch(Throwable t){failure("原厂重算",bootstrap==null?"未定位":FanServiceCompat.methodKey(bootstrap.refresh),t);}}});
    }
    private static Bundle api(String method,Bundle b){long token=Binder.clearCallingIdentity();try{return context.getContentResolver().call(API,method,null,b);}catch(Throwable t){evidence.failure("Provider读取/回传",method,t,SystemClock.elapsedRealtime());return null;}finally{Binder.restoreCallingIdentity(token);}}
    private static String bounded(String s,int n){return s.length()>n?s.substring(0,n)+"\n…（已截断）":s;}
}
