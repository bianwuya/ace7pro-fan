#!/usr/bin/env python3
"""Execute the real OEM handoff bridge with minimal, temporary Android/Xposed fakes.

The fake OEM has a successful same-level cache but physical state=0. Its real
fixture refresh must run on its original Handler, invalidate that cache via the
production adapter, and actually issue a start. This is a host regression, not
proof of Android Binder permissions, Xposed instrumentation or device behavior.
Run directly with python3 tests/test_kernel_handoff.py; no installed dependencies.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/dev/eta/acefan"
REAL_CLASSES = (
    "KernelOemHandoff", "KernelHandoff", "KernelReport", "KernelProtocol",
    "FanServiceCompat", "RootProviderAccess",
)

SOURCES = {
    "android/net/Uri.java": r'''
package android.net;
public final class Uri {
 private final String text;
 private Uri(String text) { this.text = text; }
 public static Uri parse(String text) { return new Uri(text); }
 public static Uri withAppendedPath(Uri base, String path) { return parse(base.text + "/" + path); }
 public String getAuthority() { return text.substring(text.indexOf("://") + 3).split("/", 2)[0]; }
 @Override public boolean equals(Object other) { return other instanceof Uri && text.equals(((Uri)other).text); }
 @Override public int hashCode() { return text.hashCode(); }
 @Override public String toString() { return text; }
}''',
    "android/os/Bundle.java": r'''
package android.os;
public class Bundle extends java.util.HashMap<String,Object> {
 public Bundle() { }
 public Bundle(Bundle source) { super(source); }
 public void putInt(String key, int value) { put(key, value); }
 public void putLong(String key, long value) { put(key, value); }
 public void putBoolean(String key, boolean value) { put(key, value); }
 public void putString(String key, String value) { put(key, value); }
 public long getLong(String key, long fallback) { Object v=get(key); return v instanceof Long ? (Long)v : fallback; }
 public boolean getBoolean(String key, boolean fallback) { Object v=get(key); return v instanceof Boolean ? (Boolean)v : fallback; }
 public String getString(String key) { Object v=get(key); return v instanceof String ? (String)v : null; }
}''',
    "android/os/SystemClock.java": r'''
package android.os;
public final class SystemClock {
 public static volatile long now = 10000;
 public static long elapsedRealtime() { return now; }
}''',
    "android/os/Handler.java": r'''
package android.os;
import java.util.concurrent.*;
public final class Handler implements AutoCloseable {
 public volatile Thread thread;
 private volatile Throwable failure;
 private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
  Thread result = new Thread(task, "OEM-original-handler"); thread = result; return result;
 });
 public boolean post(Runnable task) {
  executor.execute(() -> { try { task.run(); } catch (Throwable t) { failure=t; } });
  return true;
 }
 public void drain() throws Exception {
  executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
  if (failure != null) throw new AssertionError("OEM handler task failed", failure);
 }
 public void close() throws Exception {
  executor.shutdownNow();
  if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("handler leaked");
 }
}''',
    "android/database/ContentObserver.java": r'''
package android.database;
import android.net.Uri;
import android.os.Handler;
public abstract class ContentObserver {
 private final Handler handler;
 public ContentObserver(Handler handler) { this.handler=handler; }
 public void onChange(boolean selfChange, Uri uri) { }
 public void dispatchChange(boolean selfChange, Uri uri) { handler.post(() -> onChange(selfChange, uri)); }
}''',
    "android/content/ContentResolver.java": r'''
package android.content;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
public abstract class ContentResolver {
 private ContentObserver observer;
 private Uri observed;
 public abstract Bundle call(Uri uri, String method, String argument, Bundle extras);
 public abstract String setting(String key);
 public void registerContentObserver(Uri uri, boolean descendants, ContentObserver value) {
  if (observer != null || descendants) throw new AssertionError("unexpected registration");
  observed=uri; observer=value;
 }
 public void unregisterContentObserver(ContentObserver value) { if (observer == value) observer=null; }
 public void notifyChange(Uri uri) {
  if (observer == null || !uri.equals(observed)) throw new AssertionError("wrong/missing observer");
  observer.dispatchChange(false, uri);
 }
 public boolean registered() { return observer != null; }
}''',
    "android/content/Context.java": r'''
package android.content;
public final class Context {
 private final ContentResolver resolver;
 public Context(ContentResolver resolver) { this.resolver=resolver; }
 public ContentResolver getContentResolver() { return resolver; }
}''',
    "android/provider/Settings.java": r'''
package android.provider;
public final class Settings {
 public static final class System {
  public static String getString(android.content.ContentResolver resolver, String key) { return resolver.setting(key); }
 }
}''',
    "de/robv/android/xposed/XC_MethodHook.java": r'''
package de.robv.android.xposed;
public abstract class XC_MethodHook {
 public static final class MethodHookParam {
  public Object thisObject;
  public Object[] args;
  public boolean hasThrowable() { return false; }
 }
 protected void afterHookedMethod(MethodHookParam parameter) throws Throwable { }
}''',
    "de/robv/android/xposed/XposedBridge.java": r'''
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
 public static final java.util.Map<Member,XC_MethodHook> hooks = new java.util.HashMap<>();
 public static final java.util.List<String> logs = new java.util.ArrayList<>();
 public static void hookMethod(Member method, XC_MethodHook hook) { hooks.put(method, hook); }
 public static void log(String text) { logs.add(text); }
 public static void after(Member method, Object target, Object... args) throws Throwable {
  XC_MethodHook hook=hooks.get(method);
  if (hook == null) throw new AssertionError("production install did not hook initialization");
  XC_MethodHook.MethodHookParam parameter=new XC_MethodHook.MethodHookParam();
  parameter.thisObject=target; parameter.args=args; hook.afterHookedMethod(parameter);
 }
}''',
    "com/oplus/exsystemservice/fan/controller/OplusFanController.java": r'''
package com.oplus.exsystemservice.fan.controller;
public final class OplusFanController {
 private static final OplusFanController INSTANCE=new OplusFanController();
 public boolean mCurrentSpeedLevelUpdated;
 public int mCurrentSpeedLevel, physicalState, requests, starts, earlyReturns;
 public int mLastUEvent, scene, mode, selfCheck;
 public boolean protection;
 public Thread startThread;
 public static OplusFanController instance() { return INSTANCE; }
 public void reset() {
  mCurrentSpeedLevelUpdated=true; mCurrentSpeedLevel=20; physicalState=0;
  requests=starts=earlyReturns=0; mLastUEvent=7; scene=3; mode=2; selfCheck=4;
  protection=true; startThread=null;
 }
 public void requestLevel(int target) {
  requests++;
  if (mCurrentSpeedLevelUpdated && target == mCurrentSpeedLevel) { earlyReturns++; return; }
  // A real effect, not an empty refresh: the original OEM path performs the start.
  physicalState=1; starts++; startThread=Thread.currentThread();
  mCurrentSpeedLevel=target; mCurrentSpeedLevelUpdated=true;
 }
}''',
    "com/oplus/exsystemservice/fan/core/Aeolus.java": r'''
package com.oplus.exsystemservice.fan.core;
import android.content.Context;
import android.os.Handler;
import com.oplus.exsystemservice.fan.controller.OplusFanController;
public final class Aeolus {
 public Handler originalHandler;
 public int refreshes, target=20;
 public boolean failNext, cacheAtEntry;
 public String lastReason;
 public Thread refreshThread;
 public void initialize(Context context, Handler handler) { originalHandler=handler; }
 public void refresh(String reason) {
  if (Thread.currentThread() != originalHandler.thread) throw new AssertionError("refresh left original OEM handler");
  refreshes++; refreshThread=Thread.currentThread(); lastReason=reason;
  OplusFanController controller=OplusFanController.instance();
  cacheAtEntry=controller.mCurrentSpeedLevelUpdated;
  if (failNext) { failNext=false; throw new IllegalStateException("synthetic original refresh failure"); }
  controller.requestLevel(target);
 }
}''',
    "android/os/IBinder.java": "package android.os; public interface IBinder {}",
    "android/os/Binder.java": "package android.os; public class Binder implements IBinder {}",
    "android/os/Process.java": r'''
package android.os;
public final class Process { public static int uid=0; public static int myUid() { return uid; } }
''',
    "android/os/DeadObjectException.java": "package android.os; public class DeadObjectException extends Exception {}",
    "android/content/AttributionSource.java": r'''
package android.content;
public final class AttributionSource {
 public int uid; public String name;
 public static final class Builder {
  private final AttributionSource source=new AttributionSource();
  public Builder(int uid) { source.uid=uid; }
  public Builder setPackageName(String name) { source.name=name; return this; }
  public AttributionSource build() { return source; }
 }
}''',
    "android/content/IContentProvider.java": r'''
package android.content;
public interface IContentProvider {
 android.os.Bundle call(AttributionSource source, String authority, String method,
     String argument, android.os.Bundle extras) throws Exception;
}''',
    "android/app/ContentProviderHolder.java": r'''
package android.app;
public final class ContentProviderHolder { public android.content.IContentProvider provider; }
''',
    "android/app/IActivityManager.java": r'''
package android.app;
public interface IActivityManager {
 ContentProviderHolder getContentProviderExternal(String authority, int user, android.os.IBinder token, String tag) throws Exception;
 void removeContentProviderExternalAsUser(String authority, android.os.IBinder token, int user) throws Exception;
}''',
    "android/app/ActivityManager.java": r'''
package android.app;
public final class ActivityManager {
 public static IActivityManager service;
 public static IActivityManager getService() { return service; }
}''',
    "dev/eta/acefan/DailyFanContract.java": r'''
package dev.eta.acefan;
public final class DailyFanContract { public static final String READ="dailyConfig"; }
''',
    "dev/eta/acefan/KernelHandoffExecutableTest.java": r'''
package dev.eta.acefan;
import java.lang.reflect.Field;
import android.content.*;
import android.net.Uri;
import android.os.*;
import de.robv.android.xposed.XposedBridge;
import com.oplus.exsystemservice.fan.core.Aeolus;
import com.oplus.exsystemservice.fan.controller.OplusFanController;
public final class KernelHandoffExecutableTest {
 static final Uri API=Uri.parse("content://dev.eta.acefan.data");
 static final Uri ACK=Uri.withAppendedPath(API,"kernelHandoff");
 static int checks, cases;
 static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
 static void passed(String label) { cases++; System.out.println("PASS " + label); }
 static Field bridgeField(String name) throws Exception {
  Field field=KernelOemHandoff.class.getDeclaredField(name); field.setAccessible(true); return field;
 }
 static long consumed() throws Exception { return bridgeField("consumedAt").getLong(null); }
 static String status() {
  return "NONRELEASE protocol=2 configProtocol=2 configRevision=74 strategy=2 configured=0 live=1 blocked=0 fault=0 "
   + "owned=0 ready=0 leaseUntil=0 nowMs=10000 ownerEpoch=36 target=-1 output=0 requestedLevel=20 "
   + "physicalState=0 effective=0 readbackMask=7 tempValidMask=0 temp0=36700 temp1=40395 temp2=43828 "
   + "temp3=44528 sampleAtMs=9900 power=1 powerEpoch=1 sourcePresentMask=1 sourceOnlineMask=0 "
   + "sourceFailedMask=0 batteryStatus=2 batteryPresent=1 reason=0";
 }
 static Bundle token(long at) {
  Bundle token=new Bundle(); token.putBoolean("released",true); token.putBoolean("consumed",false);
  token.putLong("at",at); token.putLong("revision",74); token.putString("status",status());
  token.putString("mode","2"); token.putString("level","100"); return token;
 }
 static final class Resolver extends ContentResolver {
  Bundle proof;
  String mode="2", level="100";
  int reads, acknowledgements;
  boolean failAckOnce;
  Thread receiptThread;
  public String setting(String key) {
   if ("cooling_fan_mode_v3".equals(key)) return mode;
   if ("cooling_fan_customize_level".equals(key)) return level;
   throw new AssertionError("unexpected setting " + key);
  }
  public Bundle call(Uri uri, String method, String argument, Bundle extras) {
   check(API.equals(uri),"expected handoff provider URI");
   if ("kernelHandoff".equals(method)) { reads++; return proof == null ? new Bundle() : new Bundle(proof); }
   if ("consumeHandoff".equals(method)) {
    acknowledgements++; receiptThread=Thread.currentThread();
    check(proof != null && extras.getLong("at",-1) == proof.getLong("at",0),"ack exact token identity");
    if (failAckOnce) { failAckOnce=false; throw new IllegalStateException("synthetic ack IPC failure"); }
    proof=new Bundle(proof); proof.putBoolean("consumed",true);
    Bundle result=new Bundle(); result.putBoolean("ok",true); return result;
   }
   throw new AssertionError("unexpected provider operation " + method);
  }
 }
 static final class Fixture implements AutoCloseable {
  final Resolver resolver=new Resolver();
  final Context context=new Context(resolver);
  final Handler handler=new Handler();
  final Aeolus engine=new Aeolus();
  final OplusFanController controller=OplusFanController.instance();
  Fixture() throws Throwable {
   SystemClock.now=10000; controller.reset(); XposedBridge.hooks.clear(); XposedBridge.logs.clear();
   bridgeField("installed").setBoolean(null,false); bridgeField("attachedEngine").set(null,null);
   bridgeField("observer").set(null,null); bridgeField("consumedAt").setLong(null,0);
   KernelOemHandoff.install(getClass().getClassLoader());
   check(XposedBridge.hooks.size()==1,"real install hooks one OEM initialize");
   engine.initialize(context,handler);
   // The fake Xposed dispatcher only delivers the registered production after-hook.
   XposedBridge.after(Aeolus.class.getMethod("initialize",Context.class,Handler.class),engine,context,handler);
   handler.drain();
   check(resolver.registered() && resolver.reads==1,"real attach registers observer and checks preexisting token");
   handler.post(() -> engine.refresh("original OEM intelligent-to-extreme request")); handler.drain();
   check(engine.refreshes==1 && controller.requests==1 && controller.earlyReturns==1,"same-level success cache reproduces initial early return");
   check(controller.physicalState==0 && controller.starts==0,"initial mode request really leaves fan stopped");
   check(controller.mCurrentSpeedLevelUpdated,"successful OEM cache remains stale before root proof");
  }
  void notifyToken(Bundle proof) throws Exception {
   resolver.proof=proof; resolver.notifyChange(ACK); handler.drain();
  }
  void protections() {
   check(controller.mLastUEvent==7 && controller.scene==3 && controller.mode==2
    && controller.selfCheck==4 && controller.protection,"health/scene/mode/selfcheck/protection untouched");
   check(controller.mCurrentSpeedLevel==20 && engine.target==20,"bridge does not rewrite target or saved OEM level");
  }
  void rejected(String label) throws Exception {
   check(engine.refreshes==1 && controller.requests==1,"rejected token must not run OEM refresh: " + label);
   check(controller.physicalState==0 && controller.starts==0 && controller.mCurrentSpeedLevelUpdated,
    "rejected token must not invalidate cache or start: " + label);
   check(consumed()==0 && resolver.acknowledgements==0 && !resolver.proof.getBoolean("consumed",false),
    "rejected token must not consume or acknowledge: " + label);
   protections(); passed(label);
  }
  void started(int refreshes, long at) throws Exception {
   check(engine.refreshes==refreshes && controller.starts==1 && controller.physicalState==1,"original OEM refresh actually starts fan once");
   check(!engine.cacheAtEntry && controller.mCurrentSpeedLevelUpdated,"production Handoff clears only confirmation before original refresh");
   check(engine.refreshThread==handler.thread && controller.startThread==handler.thread,"refresh and hardware start on original OEM thread");
   check("ACEFan kernel manual handoff".equals(engine.lastReason),"real bridge invokes original refresh with handoff reason");
   check(consumed()==at,"only successful original refresh consumes token"); protections();
  }
  public void close() throws Exception { handler.close(); }
 }
 interface Mutate { void apply(Bundle proof, Fixture fixture); }
 static void rejection(String label, Mutate mutate) throws Throwable {
  try (Fixture fixture=new Fixture()) {
   Bundle proof=token(10000); mutate.apply(proof,fixture); fixture.notifyToken(proof); fixture.rejected(label);
  }
 }
 static void bridgeCases() throws Throwable {
  // No Strategy, ConfigParser or FanMode is supplied: narrow Handoff must stand alone.
  try { Class.forName("com.oplus.exsystemservice.fan.strategies.Strategy"); throw new AssertionError("Strategy unexpectedly present"); }
  catch (ClassNotFoundException expected) { }
  try (Fixture f=new Fixture()) {
   check(FanServiceCompat.handoff(f.getClass().getClassLoader())!=null,"real narrow adapter resolves without optional Strategy");
   f.notifyToken(token(10000)); f.started(2,10000);
   check(f.resolver.acknowledgements==1 && f.resolver.proof.getBoolean("consumed",false),"success acknowledges consumption");
   check(f.resolver.receiptThread==f.handler.thread,"consumption ack stays on original handler");
   f.resolver.notifyChange(ACK); f.resolver.notifyChange(ACK); f.handler.drain();
   check(f.engine.refreshes==2 && f.controller.starts==1,"duplicate success notifications never rerun refresh");
   check(f.resolver.acknowledgements==3 && f.resolver.proof.getBoolean("consumed",false),"duplicates safely repeat consumption ack");
   f.notifyToken(token(9999));
   check(f.engine.refreshes==2 && f.resolver.acknowledgements==3 && consumed()==10000,"older-than-consumed token is ignored without ack");
   passed("intelligent-to-extreme same-level stopped-cache recovery; handler affinity; duplicate/older ack; Strategy absent");
  }
  rejection("expired proof at exact 5000ms boundary",(p,f)->p.putLong("at",5000));
  rejection("older expired proof",(p,f)->p.putLong("at",1));
  rejection("future proof",(p,f)->p.putLong("at",10001));
  // DataProvider rejects at=0 before a released token can be stored; the real
  // bridge's initial consumedAt=0 sentinel is not a publishable root proof.
  rejection("mode changed to intelligent before notification",(p,f)->f.resolver.mode="0");
  rejection("manual mode changed before notification",(p,f)->f.resolver.mode="1");
  rejection("level changed before notification",(p,f)->f.resolver.level="50");
  rejection("unknown current level",(p,f)->f.resolver.level=null);
  rejection("owned is still one",(p,f)->p.putString("status",status().replace("owned=0","owned=1")));
  rejection("configured is still one",(p,f)->p.putString("status",status().replace("configured=0","configured=1")));
  rejection("proof revision mismatches token",(p,f)->p.putLong("revision",75));
  rejection("unconfirmed config protocol",(p,f)->p.putString("status",status().replace("configProtocol=2","configProtocol=1")));
  rejection("missing release flag",(p,f)->p.putBoolean("released",false));
  rejection("malformed status does not touch OEM",(p,f)->p.putString("status","NONRELEASE protocol=2"));
  try (Fixture f=new Fixture()) {
   f.engine.failNext=true; f.notifyToken(token(10000));
   check(f.engine.refreshes==2 && f.controller.physicalState==0 && f.controller.starts==0,"throwing original refresh cannot claim start");
   check(consumed()==0 && f.resolver.acknowledgements==0 && !f.resolver.proof.getBoolean("consumed",false),"refresh exception leaves proof unconsumed/unacked");
   check(!f.controller.mCurrentSpeedLevelUpdated,"exception does not restore a stale success cache");
   check(XposedBridge.logs.stream().anyMatch(s->s.contains("refresh failed")),"refresh exception logged");
   // A later independent root proof must invalidate any newly cached success again.
   f.controller.mCurrentSpeedLevelUpdated=true; SystemClock.now=10001;
   f.notifyToken(token(10001)); f.started(3,10001);
   check(f.resolver.acknowledgements==1 && f.resolver.proof.getBoolean("consumed",false),"new proof retries after refresh exception and acknowledges");
   passed("throwing OEM refresh is not consumed; later fresh proof retries real original refresh");
  }
  try (Fixture f=new Fixture()) {
   f.resolver.failAckOnce=true; f.notifyToken(token(10000)); f.started(2,10000);
   check(!f.resolver.proof.getBoolean("consumed",false),"failed ack did not pretend provider consumed it");
   f.resolver.notifyChange(ACK); f.handler.drain();
   check(f.engine.refreshes==2 && f.controller.starts==1,"ack failure must not replay a successful original refresh");
   check(f.resolver.acknowledgements==2 && f.resolver.proof.getBoolean("consumed",false),"duplicate retries failed consumption ack only");
   passed("failed ack retries receipt only, never successful OEM action");
  }
 }
 static final class Manager implements android.app.IActivityManager {
  int gets, releases, calls, mutations, deaths;
  android.os.IBinder held;
  public android.app.ContentProviderHolder getContentProviderExternal(String authority,int user,android.os.IBinder token,String tag) {
   gets++; check(user==0 && held==null && "*acefan-root*".equals(tag),"external handle acquired as user0"); held=token;
   android.app.ContentProviderHolder holder=new android.app.ContentProviderHolder();
   holder.provider=(source,a,method,arg,extras)-> {
    calls++; check(source.uid==0 && "root".equals(source.name),"real RootProviderAccess uses UID0 attribution");
    check(authority.equals(a) && held!=null,"provider call stays inside acquired handle");
    if ("rootHandoff".equals(method)) mutations++;
    if (deaths>0) { deaths--; throw new DeadObjectException(); }
    Bundle result=new Bundle(); result.putBoolean("ok",true); return result;
   }; return holder;
  }
  public void removeContentProviderExternalAsUser(String authority,android.os.IBinder token,int user) {
   releases++; check(held==token && user==0,"external handle finally released"); held=null;
  }
 }
 static void providerCases() throws Exception {
  android.os.Process.uid=0;
  Manager manager=new Manager(); manager.deaths=1; android.app.ActivityManager.service=manager;
  RootProviderAccess access=new RootProviderAccess(); boolean failed=false;
  try { access.call(API.getAuthority(),"rootHandoff",null,token(10000)); }
  catch (IllegalStateException expected) { failed=true; check(expected.getCause() instanceof DeadObjectException,"death cause preserved"); }
  check(failed && manager.calls==1 && manager.gets==1 && manager.releases==1 && manager.mutations==1,
   "uncertain rootHandoff mutation must NEVER replay after DeadObject");
  access.call(API.getAuthority(),"rootHandoff",null,token(10001));
  check(manager.calls==2 && manager.mutations==2 && manager.releases==2,"only explicit next fresh proof reacquires and publishes");
  manager=new Manager(); manager.deaths=1; android.app.ActivityManager.service=manager;
  new RootProviderAccess().call(API.getAuthority(),"kernelHandoff",null,null);
  check(manager.calls==2 && manager.gets==2 && manager.releases==2,"side-effect-free handoff read still retries one death");
  android.os.Process.uid=1000; failed=false;
  try { new RootProviderAccess(); } catch (SecurityException expected) { failed=true; }
  finally { android.os.Process.uid=0; }
  check(failed,"system UID cannot construct root write bridge");
  passed("real RootProviderAccess uncertain rootHandoff no-replay; fresh request reacquire; readonly death retry; UID0");
 }
 public static void main(String[] args) throws Throwable {
  bridgeCases(); providerCases();
  System.out.println("PASS executable handoff: " + cases + " cases, " + checks + " assertions (host fakes, not device proof)");
 }
}''',
}


def java_without_comments(text):
    """Retain literals so URL strings survive comment stripping."""
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                  lambda match: "" if match.group().startswith(("//", "/*")) else match.group(),
                  text, flags=re.S)


def block_after(text, pattern):
    """Return a balanced Java block, ignoring braces inside quoted literals."""
    match = re.search(pattern, text)
    if match is None:
        raise AssertionError("missing Java block: " + pattern)
    start = text.index("{", match.start())
    depth = 0
    quote = None
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    raise AssertionError("unclosed Java block: " + pattern)


def structural_checks():
    relay = java_without_comments((JAVA / "KernelSettingsRelay.java").read_text())
    provider = java_without_comments((JAVA / "DataProvider.java").read_text())
    protocol = java_without_comments((JAVA / "KernelProtocol.java").read_text())
    root_access = java_without_comments((JAVA / "RootProviderAccess.java").read_text())
    expected = [
        "cooling_fan_main_switch_modify", "cooling_fan_main_switch", "cooling_fan_mode_v3",
        "cooling_fan_mode_auto_control_switch", "cooling_fan_charge_switch",
        "regular_charge_protection_status", "cooling_fan_mic_mutual", "rider_mode_main_switch",
        "super_powersave_mode_state", "sys_oplus_high_performance_spec",
    ]
    system_keys = block_after(relay, r"String\[\]\s+SYSTEM_KEYS\s*=")
    keys = re.findall(r'"([^"\\]*)"', system_keys)
    assert keys[:10] == expected, "SYSTEM_KEYS existing first ten positions changed"
    assert keys[10:] == ["cooling_fan_customize_level"], "manual level must be appended, not shift existing gates"
    safety = block_after(relay, r"private\s+KernelProtocol\.Config\s+safety\s*\(")
    mapping = re.findall(r"(\w+)\s*=\s*system\(SYSTEM_KEYS\[(\d+)\]\)", safety)
    assert mapping == list(zip(
        ("main", "ui", "mode", "auto", "charge", "protection", "micSwitch", "rider", "saving", "performance"),
        map(str, range(10)))), "existing safety variable/index mapping changed"
    print("PASS static SYSTEM_KEYS first ten keys and safety variable/index mappings unchanged", flush=True)

    root_handoff = block_after(provider, r'if\s*\(\s*"rootHandoff"\.equals\(method\)\s*\)')
    guard = re.search(r'if\s*\(\s*!KernelProtocol\.rootPublisher\(caller\(\)\)\s*\)\s*throw\s+new\s+SecurityException', root_handoff)
    assert guard is not None, "rootHandoff must require UID0, not owner/system write permission"
    assert guard.start() < root_handoff.index("KernelReport.parse") < root_handoff.index("kernelHandoff = token"), "root write credential check must precede evidence/state mutation"
    publisher = block_after(protocol, r"boolean\s+rootPublisher\s*\(")
    assert re.fullmatch(r"\s*return\s+uid\s*==\s*0\s*;\s*", publisher), "rootPublisher widened beyond UID0"
    consume = block_after(provider, r'if\s*\(\s*"consumeHandoff"\.equals\(method\)\s*\)')
    assert re.search(r'if\s*\(caller\(\)\s*!=\s*1000\)\s*throw\s+new\s+SecurityException', consume), "consume ack must remain OEM-only"
    print("PASS static DataProvider release-proof writes require UID0 before mutation; ack requires OEM UID1000", flush=True)

    readonly = re.search(r"boolean\s+readonly\s*=\s*([^;]+);", root_access)
    assert readonly and all('!"' + method + '".equals(method)' in readonly.group(1)
                            for method in ("pushRoot", "rootHandoff")), "rootHandoff is mutating and must not use DeadObject read replay"
    assert re.search(r"readonly\s*&&\s*attempt\s*==\s*0\s*&&\s*e\s+instanceof\s+DeadObjectException", root_access), "DeadObject retry must stay bounded to readonly calls"
    print("PASS static RootProviderAccess rootHandoff excluded from DeadObject replay (also executed below)", flush=True)

    writer_parts = []
    for name in ("writerLoop", "freshAuthorization", "write", "closeWritingFd", "now"):
        writer_parts.append(block_after(relay, r"private\s+(?:static\s+)?[\w.]+\s+" + name + r"\s*\([^)]*\)(?:\s+throws\s+[\w., ]+)?\s*\{"))
    writer_parts.append(block_after(relay, r"private\s+static\s+final\s+class\s+RevisionStore\b"))
    for body in writer_parts:
        assert not re.search(r"\b(?:providers|resolver|context)\s*\.", body), "writer lane/helper gained Provider/framework IPC"
        assert not re.search(r"\b(?:handoffLoop|reportLoop|workerLoop|readProvider|safety)\s*\(", body), "writer entered IPC worker lane"
        assert not re.search(r"\b(?:call|setting|getContentResolver|getContentProviderExternal)\s*\(", body), "writer lane/helper gained provider access"
    writer = writer_parts[0]
    assert "wake(handoffWake)" in writer and "new Sent(" in writer, "writer only signals independent handoff lane after commit"
    handoff = block_after(relay, r"private\s+void\s+handoffLoop\s*\(")
    assert 'providers.call(API.getAuthority(), "rootHandoff"' in handoff, "proof publication must remain outside writer"
    print("PASS static writer/freshness/write/close/clock/revision helpers have no Provider IPC; proof publication isolated", flush=True)


def main():
    structural_checks()
    for executable in ("javac", "java"):
        if shutil.which(executable) is None:
            raise SystemExit("required local tool missing: " + executable + " (no dependencies installed)")
    with tempfile.TemporaryDirectory(prefix="acefan-kernel-handoff-") as directory:
        temp = Path(directory)
        for name, text in SOURCES.items():
            target = temp / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        for name in REAL_CLASSES:
            shutil.copyfile(JAVA / (name + ".java"), temp / "dev/eta/acefan" / (name + ".java"))
        classes = temp / "classes"
        print("Compiling unmodified production classes: " + ", ".join(REAL_CLASSES), flush=True)
        subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sorted(temp.rglob("*.java")))], check=True, timeout=60)
        subprocess.run(["java", "-ea", "-cp", str(classes), "dev.eta.acefan.KernelHandoffExecutableTest"],
                       check=True, timeout=45)
    print("PASS test_kernel_handoff.py; temporary stubs/classes removed; no device access", flush=True)


if __name__ == "__main__":
    main()
