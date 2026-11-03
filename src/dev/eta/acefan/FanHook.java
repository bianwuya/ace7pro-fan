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
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Device-specific policy hook: candidate-only override, no fan/thermal/sysfs writes. */
public final class FanHook implements IXposedHookLoadPackage {
    private static final String VERSION=BuildInfo.HOOK_VERSION;
    private static final String TARGET="com.oplus.exsystemservice";
    private static final String APK_HASH=CompatibilityCatalog.KNOWN_APK_SHA256;
    private static volatile FanServiceCompat bindings;
    private static volatile FanServiceCompat.Bootstrap bootstrap;
    private static final AtomicBoolean resolving=new AtomicBoolean();
    private static volatile String compatibilityStatus="pending",compatibilityDetail="等待原厂风扇初始化",targetVersion="未知";
    private static volatile String compatibilityStage="",targetSha256="未知",targetPath="暂无",hardwareGate="未知";
    private static volatile boolean knownFirmware,apkChecked;
    private static final java.util.List<XC_MethodHook.Unhook> installed=new java.util.ArrayList<XC_MethodHook.Unhook>();
    private static final Uri API=Uri.parse("content://dev.eta.acefan.data");
    private static final AtomicBoolean started=new AtomicBoolean(), queued=new AtomicBoolean();
    private static final AtomicInteger errors=new AtomicInteger();
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
    private static volatile boolean enabled,compatible,fused,active,powerDisconnected,eventChargeBlocked;
    private static volatile String hardReason="等待采样",decisionReason="等待首次决策",phase="oem";
    private static volatile int lastOem=-1,lastCandidate=-1,lastRequest=-1;
    private static String previousTrigger="",previousDecision="";
    private static final ThreadLocal<AppliedDecision> pendingScreenOff=new ThreadLocal<AppliedDecision>();
    private static final class AppliedDecision {
        final long generation,at;
        AppliedDecision(long g,long time){generation=g;at=time;}
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
        // Publish injection/compatibility evidence even when the OEM service API is unsupported.
        XposedHelpers.findAndHookMethod(android.app.Application.class,"attach",Context.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam event){
                if(event.hasThrowable() || !(event.args[0] instanceof Context))return;
                try{startWorker((Context)event.args[0]);}catch(Throwable t){unsupported(t);}
            }
        });
        hardwareGate=CompatibilityCatalog.hardwareSupported(Build.MODEL,Build.DEVICE)
                ?"通过："+Build.MODEL+" / "+Build.DEVICE
                :"不通过："+Build.MODEL+" / "+Build.DEVICE+"（本模块仅覆盖 ACE7pro PMS110 / OP64F1L1）";
        if(!CompatibilityCatalog.hardwareSupported(Build.MODEL,Build.DEVICE)){
            compatibilityStatus="unsupported";compatibilityStage="硬件门槛";
            compatibilityDetail="非 ACE7pro PMS110 / OP64F1L1 硬件；仅观测";return;
        }
        if(!CompatibilityCatalog.bridgeSupported(XposedBridge.getXposedVersion())){
            compatibilityStatus="unsupported";compatibilityStage="框架门槛";
            compatibilityDetail="需要传统 Xposed API ≥82，当前 API "+XposedBridge.getXposedVersion();return;
        }
        try {
            bootstrap=FanServiceCompat.bootstrap(loader,Context.class,Handler.class);
            XposedBridge.hookMethod(bootstrap.initialize,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam event){
                    if(event.hasThrowable() || event.args[0]==null || event.args[1]==null)return;
                    final Object engine=event.thisObject;
                    final Handler h=(Handler)event.args[1];
                    try {
                        startWorker((Context)event.args[0]);
                        if(!resolving.compareAndSet(false,true))return;
                        worker.post(new Runnable(){@Override public void run(){
                            try {
                                final FanServiceCompat found=FanServiceCompat.resolve(bootstrap,engine,loader,Context.class,Handler.class);
                                found.verifyCode(context.getPackageCodePath());
                                knownFirmware=verifyApk();
                                h.post(new Runnable(){@Override public void run(){
                                    try {
                                        originalHandler=h;aeolus=engine;bindings=found;
                                        installPolicyHooks(found);
                                        compatibilityStatus=knownFirmware?"known-firmware":"interface-matched";
                                        compatibilityDetail=knownFirmware?"参考固件已识别；11 处关键代码指纹通过":"系统版本不限；接口与 11 处关键保护链代码匹配，尚待实机验证";
                                        compatible=true;sampleSoon();requestRefresh();
                                    }catch(Throwable t){unsupported(t);}
                                }});
                            }catch(Throwable t){unsupported(t);}
                        }});
                    }catch(Throwable t){unsupported(t);}
                }
            });
            XposedBridge.log("ACEFan "+VERSION+": adapter bootstrap installed; waiting for OEM V3 initialization");
        }catch(Throwable t){unsupported(t);}
    }

    private static synchronized void installPolicyHooks(final FanServiceCompat found) throws Throwable {
        try {
            installed.add(XposedBridge.hookMethod(found.candidate,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param){
                    pendingScreenOff.remove();
                    if(param.hasThrowable()||!(param.getResult() instanceof Integer))return;
                    try {
                        int old=(Integer)param.getResult();lastOem=old;
                        long decisionGeneration=handoff.generation();
                        Gate gate=evaluateGate();
                        if(!gate.allowed()){observeOriginal(old,gate.reason);return;}
                        Object choice=found.choice.get(param.thisObject);
                        Object strategy=found.getStrategy.invoke(choice);
                        String name=String.valueOf(found.strategyName.invoke(strategy));
                        // Known charging/lockscreen policies only: never elevate an interrupt-scene zero.
                        if(!("KeyguardLocked".equals(name)||"ChargeWhileKeyguardLocked".equals(name)||"Common".equals(name)||"SuperCharging".equals(name))){
                            cancelContinuation();observeOriginal(old,"保留原厂场景："+name);return;
                        }
                        Object controller=found.controller;
                        if(!Boolean.TRUE.equals(found.healthy.invoke(controller))){
                            cancelContinuation();observeOriginal(old,"原厂风扇异常，停止增强");return;
                        }
                        JSONObject s=snapshot;
                        String hardware=s==null?"":s.optString("fanStatus","");
                        if(!"NORMAL".equals(hardware)){
                            Object ev=found.lastEvent.get(controller);
                            if(!Integer.valueOf(2).equals(ev)){
                                cancelContinuation();observeOriginal(old,"风扇状态未知或异常，保留原厂");return;
                            }
                        }
                        int maximum=((Number)found.maxLevel.invoke(found.parser)).intValue();
                        if(maximum<6||maximum>20){cancelContinuation();observeOriginal(old,"设备档位能力不匹配");return;}
                        int desired=chargeCurve.level();
                        int chosen=Math.min(maximum,Math.max(old,desired));
                        param.setResult(chosen);
                        if(gate.mode==WakeHandoff.SCREEN_OFF && chosen>0)pendingScreenOff.set(new AppliedDecision(decisionGeneration,SystemClock.elapsedRealtime()));
                        lastCandidate=chosen;active=true;phase=WakeHandoff.label(gate.mode);
                        String prefix=gate.mode==WakeHandoff.LOCKED?"亮屏锁屏延续":gate.mode==WakeHandoff.UNLOCK_GRACE?"解锁后短暂衔接":"息屏有线充电增强";
                        decisionReason=prefix+"：电池曲线"+desired+"档；原厂候选"+old+"档；后续保护保留";
                        traceDecision(name,old,chosen,gate.locked);
                    }catch(Throwable t){fault(t);}
                }
            }));
            installed.add(XposedBridge.hookMethod(found.setLevel,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    AppliedDecision pending=pendingScreenOff.get();pendingScreenOff.remove();
                    if(pending!=null && SystemClock.elapsedRealtime()-pending.at<2000L)p.setObjectExtra("acefan.screenOffDecision",pending);
                    int request=(Integer)p.args[0];
                    if(lastRequest!=request)XposedBridge.log("ACEFan "+VERSION+": controllerRequest="+request+" phase="+phase+" active="+active+" elapsed="+SystemClock.elapsedRealtime());
                    lastRequest=request;
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    int request=(Integer)p.args[0];
                    if(p.hasThrowable())return;
                    if(active && request<=0){
                        cancelContinuation();active=false;decisionReason="原厂后续保护/降噪要求停转，保留该结果";
                    }else if(request>0 && p.getObjectExtra("acefan.screenOffDecision") instanceof AppliedDecision){
                        try{
                            AppliedDecision applied=(AppliedDecision)p.getObjectExtra("acefan.screenOffDecision");
                            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                            // Tie the completion to its actual candidate, not a concurrent telemetry phase.
                            if(enabled && !fused && !powerDisconnected && !eventChargeBlocked && pm!=null && !pm.isInteractive() && SystemClock.elapsedRealtime()-applied.at<2000L && found.updated.getBoolean(p.thisObject))handoff.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,request,applied.generation);
                        }catch(Throwable ignored){cancelContinuation();}
                    }
                }
            }));
            XposedBridge.log("ACEFan "+VERSION+": policy hooks installed after code-profile verification");
        }catch(Throwable t){
            for(XC_MethodHook.Unhook u:installed)try{u.unhook();}catch(Throwable ignored){}
            installed.clear();throw t;
        }
    }

    private static void unsupported(Throwable t){
        compatible=false;active=false;cancelContinuation();
        compatibilityStatus="unsupported";
        String stage=FanServiceCompat.stageOf(t);
        if(!stage.isEmpty())compatibilityStage=stage;
        compatibilityDetail=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        if(compatibilityDetail.length()>500)compatibilityDetail=compatibilityDetail.substring(0,500);
        decisionReason="系统风扇接口未适配，仅观测："+compatibilityDetail;
        XposedBridge.log("ACEFan "+VERSION+": "+decisionReason);sampleSoon();
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
        worker.post(new Runnable(){@Override public void run(){verifyApk();}});
        XposedBridge.log("ACEFan "+VERSION+": telemetry initialized; enhancement waits for interface/code verification");
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

    /**
     * Reads the target APK path and SHA-256 once, before interface resolution. Unsupported
     * firmware must still report its own APK hash: that hash is the evidence needed to adapt it.
     */
    private static boolean verifyApk(){
        if(apkChecked)return knownFirmware;
        apkChecked=true;
        try{
            targetPath=String.valueOf(context.getPackageCodePath());
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];
            try(FileInputStream in=new FileInputStream(context.getPackageCodePath())){int n;while((n=in.read(buffer))>0)md.update(buffer,0,n);}
            StringBuilder x=new StringBuilder();for(byte b:md.digest())x.append(String.format(java.util.Locale.US,"%02x",b&255));
            targetSha256=x.toString();
            knownFirmware=APK_HASH.equals(targetSha256);
        }catch(Throwable t){targetSha256="读取失败";XposedBridge.log("ACEFan "+VERSION+": APK verification failed "+t);}
        return knownFirmware;
    }

    private static final Runnable tick=new Runnable(){@Override public void run(){
        long next=Sampling.ACTIVE_MS;
        try{
            JSONObject s=Telemetry.sample(context);
            Bundle cfg=api("config",null);
            if(cfg!=null){enabled=cfg.getBoolean("enabled",false);configVersion=cfg.getLong("version");lastConfigAt=SystemClock.elapsedRealtime();}
            else if(SystemClock.elapsedRealtime()-lastConfigAt>Sampling.staleMs(sampleIntervalMs))enabled=false;
            sampleAt=SystemClock.elapsedRealtime();snapshot=s;
            hardReason=workerHardEligibility(s);
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
            s.put("compatibilityStatus",fused?"runtime-error":compatibilityStatus);
            s.put("compatibilityStage",compatibilityStage);
            s.put("compatibilityDetail",compatibilityDetail);s.put("targetVersion",targetVersion);
            s.put("targetPackage",TARGET);s.put("targetSha256",targetSha256);s.put("targetPath",targetPath);
            s.put("knownApk",!apkChecked?"未校验":knownFirmware?"一致":"不一致");
            s.put("hardwareGate",hardwareGate);
            s.put("targetScope","com.oplus.exsystemservice");
            StringBuilder tr=new StringBuilder();
            for(String line:FanServiceCompat.lastTrace()){
                if(tr.length()+line.length()>1200){tr.append("\n…（轨迹已截断）");break;}
                if(tr.length()>0)tr.append("\n");tr.append(line);
            }
            s.put("compatibilityTrace",tr.toString());
            s.put("phase",phase);s.put("handoffRemainingMs",remaining);s.put("active",active);s.put("reason",decisionReason);
            s.put("oem",lastOem<0?JSONObject.NULL:lastOem);s.put("candidate",lastCandidate<0?JSONObject.NULL:lastCandidate);s.put("request",lastRequest<0?JSONObject.NULL:lastRequest);s.put("curve",chargeCurve.level());
            double[] temps={s.optDouble("battery",Double.NaN),s.optDouble("board",Double.NaN),s.optDouble("fan",Double.NaN),s.optDouble("mos",Double.NaN)};
            String key=trigger+"|"+active+"|"+decisionReason+"|"+s.optString("fanStatus","")+"|"+s.optInt("effective",-1)+"|"+s.optInt("driver",-1)+"|"+s.optBoolean("interactive",false);
            long nowElapsed=SystemClock.elapsedRealtime();
            if(Sampling.shouldPush(!pushedOnce,!key.equals(lastPushKey),nowElapsed-lastPushAt,Sampling.maxDelta(temps,lastPushTemps))){
                Bundle b=new Bundle();b.putString("json",s.toString());
                if(api("push",b)!=null){pushedOnce=true;lastPushAt=nowElapsed;lastPushKey=key;lastPushTemps=temps;}
            }
        }catch(Throwable t){fault(t);active=false;enabled=false;cancelContinuation();requestRefresh();next=Sampling.IDLE_MS;sampleIntervalMs=next;}
        finally{if(worker!=null){worker.removeCallbacks(this);worker.postDelayed(this,next);}}
    }};

    private static String workerHardEligibility(JSONObject s){
        try{
            if(fused)return "模块已熔断，保留原厂";
            if(!compatible)return "系统风扇未适配，保留原厂："+compatibilityDetail;
            if(!enabled)return "功能关闭，保留原厂";
            if(powerDisconnected||eventChargeBlocked||!s.optBoolean("wired",false)||!s.optBoolean("charging",false))return "非有线充电中，保留原厂";
            if(!s.optBoolean("batteryValid",false)||s.isNull("battery"))return "电池温度不可用";
            if("battery_sticky".equals(s.optString("batterySource"))&&(batteryEventAt==0||SystemClock.elapsedRealtime()-batteryEventAt>60000))return "仅有过期电池广播，保留原厂";
            double t=s.optDouble("battery",Double.NaN);if(Double.isNaN(t)||t<0||t>65)return "温度超出已验证工作区间，保留原厂";
            int sw=Settings.System.getInt(context.getContentResolver(),"cooling_fan_main_switch_modify",0);
            if(sw!=1&&sw!=3)return "用户风扇总开关未允许";
            if(Settings.System.getInt(context.getContentResolver(),"cooling_fan_charge_switch",0)!=1)return "原厂充电风扇开关关闭";
            if(Settings.System.getInt(context.getContentResolver(),"cooling_fan_mode_v3",0)!=0)return "手动模式，保留用户设定";
            if(Settings.System.getInt(context.getContentResolver(),"cooling_fan_mode_auto_control_switch",1)==0)return "原厂自动调速已关闭";
            if(Settings.System.getInt(context.getContentResolver(),"regular_charge_protection_status",0)==1)return "充电保护生效，保留原厂";
            if("true".equals(Settings.Global.getString(context.getContentResolver(),"cooling_fan_service_abort")))return "原厂服务已停用";
            return "允许";
        }catch(Throwable t){return "状态读取失败，保留原厂";}
    }

    private static Gate evaluateGate(){
        String why=null;
        long now=SystemClock.elapsedRealtime();
        long stale=Sampling.staleMs(sampleIntervalMs);
        if(fused)why="模块已熔断，保留原厂";
        else if(!compatible)why="系统风扇未适配，保留原厂："+compatibilityDetail;
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
        h.post(new Runnable(){@Override public void run(){queued.set(false);try{if(bootstrap!=null)bootstrap.refresh.invoke(aeolus,"ACE7pro Charging Policy");}catch(Throwable t){fault(t);}}});
    }
    private static Bundle api(String method,Bundle b){long token=Binder.clearCallingIdentity();try{return context.getContentResolver().call(API,method,null,b);}catch(Throwable t){return null;}finally{Binder.restoreCallingIdentity(token);}}
    private static void fault(Throwable t){active=false;cancelContinuation();decisionReason="模块异常，保留原厂："+t.getClass().getSimpleName();int count=errors.incrementAndGet();if(count<=5)XposedBridge.log("ACEFan "+VERSION+": "+t);if(count>=5){fused=true;enabled=false;}}
}
