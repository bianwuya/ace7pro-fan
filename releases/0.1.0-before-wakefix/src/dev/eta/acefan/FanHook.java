package dev.eta.acefan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
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
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Device-specific candidate-policy hook. Never writes sysfs, thermal data or global controls. */
public final class FanHook implements IXposedHookLoadPackage {
    private static final String TARGET="com.oplus.exsystemservice";
    private static final String APK_HASH="3eeee6cd69d22fad8ddf6b341c54128ca9c0fb505c6e24ce85ffdd2ef780544c";
    private static final Uri API=Uri.parse("content://dev.eta.acefan.data");
    private static final AtomicBoolean started=new AtomicBoolean(), queued=new AtomicBoolean();
    private static volatile Context context;
    private static volatile Handler originalHandler, worker;
    private static volatile Object aeolus;
    private static volatile ClassLoader loader;
    private static volatile JSONObject snapshot;
    private static volatile long sampleAt, configVersion, lastConfigAt;
    private static volatile boolean enabled, compatible, fused;
    private static volatile String baseReason="等待初始化", decisionReason="等待首次决策";
    private static volatile boolean active;
    private static volatile int lastOem=-1,lastCandidate=-1,lastRequest=-1;
    private static final ChargeCurve chargeCurve=new ChargeCurve();
    private static volatile long batteryEventAt;
    private static String previousTrigger="";
    private static int errors;
    private static final double[] UP={33,35,37,39,41};
    private static final int[] LEVEL={6,10,14,18,20};

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if(!TARGET.equals(p.packageName)||!TARGET.equals(p.processName)) return;
        if(!"PMS110".equals(Build.MODEL)||!"OP64F1L1".equals(Build.DEVICE)||Build.VERSION.SDK_INT!=37) {
            XposedBridge.log("ACEFan: unsupported model or SDK; no hooks installed");return;
        }
        loader=p.classLoader;
        try {
            XposedHelpers.findAndHookMethod("i9.h",loader,"q",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if(param.hasThrowable()||!(param.getResult() instanceof Integer))return;
                    try { int old=(Integer)param.getResult();lastOem=old;
                        String why=eligibility();
                        if(why!=null){active=false;lastCandidate=old;decisionReason=why;return;}
                        Object choice=XposedHelpers.getObjectField(param.thisObject,"d");
                        Object strategy=XposedHelpers.callMethod(choice,"g");
                        String name=String.valueOf(XposedHelpers.callMethod(strategy,"b"));
                        // Only these charging/screen-off policy paths may override a protective zero.
                        if(!("KeyguardLocked".equals(name)||"ChargeWhileKeyguardLocked".equals(name)||"Common".equals(name)||"SuperCharging".equals(name))) {
                            active=false;lastCandidate=old;decisionReason="保留原厂场景："+name;return;
                        }
                        Object controller=single("com.oplus.exsystemservice.fan.controller.OplusFanController");
                        if(!Boolean.TRUE.equals(XposedHelpers.callMethod(controller,"s"))){active=false;lastCandidate=old;decisionReason="原厂风扇异常，停止增强";return;}
                        JSONObject s=snapshot;
                        String hardware=s==null?"":s.optString("fanStatus","");
                        if(!"NORMAL".equals(hardware)) {
                            Object ev=XposedHelpers.getObjectField(controller,"mLastUEvent");
                            if(!Integer.valueOf(2).equals(ev)){active=false;lastCandidate=old;decisionReason="风扇状态未知或异常，保留原厂";return;}
                        }
                        int maximum=((Number)XposedHelpers.callMethod(single("com.oplus.exsystemservice.fan.core.ConfigParser"),"d")).intValue();
                        if(maximum<6||maximum>20) {active=false;lastCandidate=old;decisionReason="设备档位能力不匹配";return;}
                        int desired=currentCurveLevel();
                        int chosen=Math.min(maximum,Math.max(old,desired));
                        param.setResult(chosen);lastCandidate=chosen;active=true;
                        decisionReason="息屏有线充电增强：电池曲线"+desired+"档；原厂候选"+old+"档；后续保护保留";
                    }catch(Throwable t){fault(t);}
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.exsystemservice.fan.controller.OplusFanController",loader,"i",int.class,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){lastRequest=(Integer)p.args[0];}
            });
            XposedHelpers.findAndHookMethod("com.oplus.exsystemservice.fan.core.Aeolus",loader,"f",Context.class,Handler.class,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(p.hasThrowable()||p.args[0]==null||p.args[1]==null)return;
                    try {start((Context)p.args[0],(Handler)p.args[1],p.thisObject);}catch(Throwable t){fault(t);}
                }
            });
            XposedBridge.log("ACEFan: policy hooks registered, waiting for fan initialization");
        }catch(Throwable t){fused=true;XposedBridge.log("ACEFan: install failed; original policy retained "+t);}
    }
    private static Object single(String name){return XposedHelpers.findClass(name,loader).getEnumConstants()[0];}
    private static void start(Context c,Handler h,Object a) throws Throwable {
        if(!started.compareAndSet(false,true))return;
        context=c.getApplicationContext()==null?c:c.getApplicationContext(); originalHandler=h;aeolus=a;
        HandlerThread th=new HandlerThread("ACEFan-Telemetry");th.start();worker=new Handler(th.getLooper());
        IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_BATTERY_CHANGED);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_POWER_CONNECTED);f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(i!=null&&Intent.ACTION_BATTERY_CHANGED.equals(i.getAction()))batteryEventAt=SystemClock.elapsedRealtime();
            if(i!=null&&(Intent.ACTION_SCREEN_ON.equals(i.getAction())||Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction()))){active=false;snapshot=null;requestRefresh();}
            if(worker!=null){worker.removeCallbacks(tick);worker.post(tick);}
        }},f,null,h,Context.RECEIVER_NOT_EXPORTED);
        worker.post(new Runnable(){@Override public void run(){
            compatible=verifyApk();baseReason=compatible?"等待采样":"固件APK不匹配，仅遥测";worker.post(tick);
        }});
        XposedBridge.log("ACEFan: initialized; no direct fan/thermal writes");
    }
    private static boolean verifyApk(){
        try {MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];
            try(FileInputStream in=new FileInputStream(context.getPackageCodePath())){int n;while((n=in.read(b))>0)md.update(b,0,n);}
            StringBuilder x=new StringBuilder();for(byte b1:md.digest())x.append(String.format(java.util.Locale.US,"%02x",b1&255));
            return APK_HASH.equals(x.toString());
        }catch(Throwable t){XposedBridge.log("ACEFan: APK verification failed "+t);return false;}
    }
    private static final Runnable tick=new Runnable(){@Override public void run(){
        try {
            JSONObject s=Telemetry.sample(context);
            Bundle cfg=api("config",null);
            if(cfg!=null){enabled=cfg.getBoolean("enabled",false);configVersion=cfg.getLong("version");lastConfigAt=SystemClock.elapsedRealtime();}
            else if(SystemClock.elapsedRealtime()-lastConfigAt>15000)enabled=false;
            sampleAt=SystemClock.elapsedRealtime();snapshot=s;
            baseReason=workerEligibility(s);
            updateCurve(s);
            String trigger=baseReason+"|"+configVersion+"|"+currentCurveLevel();
            if(!trigger.equals(previousTrigger)) {previousTrigger=trigger;requestRefresh();}
            if(!"允许".equals(baseReason)){active=false;decisionReason=baseReason;}
            s.put("hook","已注入");s.put("build",Build.DISPLAY);s.put("compatible",compatible);s.put("enabled",enabled);s.put("configVersion",configVersion);
            s.put("active",active);s.put("reason",decisionReason);s.put("oem",lastOem<0?JSONObject.NULL:lastOem);s.put("candidate",lastCandidate<0?JSONObject.NULL:lastCandidate);s.put("request",lastRequest<0?JSONObject.NULL:lastRequest);s.put("curve",currentCurveLevel());
            Bundle b=new Bundle();b.putString("json",s.toString());api("push",b);
        }catch(Throwable t){fault(t);active=false;enabled=false;requestRefresh();}
        finally{if(worker!=null){worker.removeCallbacks(this);worker.postDelayed(this,5000);}}
    }};
    private static String workerEligibility(JSONObject s){
        try{
            if(fused)return "模块已熔断，保留原厂";
            if(!compatible)return "固件不匹配，保留原厂";
            if(!enabled)return "功能关闭，保留原厂";
            if(s.isNull("interactive"))return "屏幕状态未知";
            if(s.optBoolean("interactive",true))return "亮屏中，保留原厂";
            if(!s.optBoolean("wired",false)||!s.optBoolean("charging",false))return "非有线充电中，保留原厂";
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
    private static String eligibility(){
        if(fused)return "模块已熔断";
        if(!compatible||!enabled)return baseReason;
        if(snapshot==null||SystemClock.elapsedRealtime()-sampleAt>15000)return "遥测过期，保留原厂";
        if(SystemClock.elapsedRealtime()-lastConfigAt>15000)return "配置过期，保留原厂";
        if(!"允许".equals(baseReason))return baseReason;
        try{PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm==null||pm.isInteractive())return "亮屏中，保留原厂";}catch(Throwable t){return "屏幕状态未知";}
        return null;
    }
    private static void updateCurve(JSONObject s){chargeCurve.update(s.optDouble("battery",Double.NaN),SystemClock.elapsedRealtime(),"允许".equals(baseReason));}
    private static int currentCurveLevel(){return chargeCurve.level();}
    private static void requestRefresh(){
        final Handler h=originalHandler;if(h==null||aeolus==null||!queued.compareAndSet(false,true))return;
        h.post(new Runnable(){@Override public void run(){queued.set(false);try{XposedHelpers.callMethod(aeolus,"g","ACE7pro Charging Policy");}catch(Throwable t){fault(t);}}});
    }
    private static Bundle api(String method,Bundle b){long token=Binder.clearCallingIdentity();try{return context.getContentResolver().call(API,method,null,b);}catch(Throwable t){return null;}finally{Binder.restoreCallingIdentity(token);}}
    private static void fault(Throwable t){active=false;decisionReason="模块异常，保留原厂："+t.getClass().getSimpleName();if(++errors<=5)XposedBridge.log("ACEFan: "+t);if(errors>=5){fused=true;enabled=false;}}
}
