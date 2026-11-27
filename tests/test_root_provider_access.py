#!/usr/bin/env python3
"""Narrow executable tests of the real reflection bridge against explicit Binder-shaped fakes.
Not an Android permission/provider emulator; device probes are authoritative for compatibility.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Binder.java': 'package android.os; public class Binder implements IBinder {}',
    'android/os/Process.java': 'package android.os; public class Process { public static int uid=0; public static int myUid(){return uid;} }',
    'android/os/DeadObjectException.java': 'package android.os; public class DeadObjectException extends Exception {}',
    'android/os/Bundle.java': '''package android.os;
public class Bundle extends java.util.HashMap<String,Object> {
 public boolean broken; public void putInt(String key,int value){put(key,value);}
 @Override public int size(){if(broken)throw new IllegalStateException("unmarshal");return super.size();}
}''',
    'android/content/AttributionSource.java': '''package android.content;
public class AttributionSource {
 public int uid; public String name;
 public static class Builder {
  private final AttributionSource value=new AttributionSource();
  public Builder(int uid){value.uid=uid;}
  public Builder setPackageName(String name){value.name=name;return this;}
  public AttributionSource build(){return value;}
 }
}''',
    'android/content/IContentProvider.java': '''package android.content;
public interface IContentProvider {
 android.os.Bundle call(AttributionSource source,String authority,String method,String argument,android.os.Bundle extras) throws Exception;
}''',
    'android/app/ContentProviderHolder.java': '''package android.app;
public class ContentProviderHolder {public android.content.IContentProvider provider;}''',
    'android/app/IActivityManager.java': '''package android.app;
public interface IActivityManager {
 ContentProviderHolder getContentProviderExternal(String authority,int user,android.os.IBinder token,String tag) throws Exception;
 void removeContentProviderExternalAsUser(String authority,android.os.IBinder token,int user) throws Exception;
}''',
    'android/app/ActivityManager.java': '''package android.app;
public class ActivityManager {
 public static IActivityManager service;
 public static IActivityManager getService(){return service;}
}''',
    'dev/eta/acefan/DailyFanContract.java': '''package dev.eta.acefan;
public class DailyFanContract {public static final String READ="dailyConfig";}''',
    'dev/eta/acefan/RootProviderAccessTest.java': '''package dev.eta.acefan;
import android.app.*; import android.content.*; import android.os.*;
public class RootProviderAccessTest {
 static int assertions;
 static void yes(boolean value){assertions++; if(!value)throw new AssertionError("assertion "+assertions);}
 interface Action {void run() throws Exception;}
 static void fails(Class<?> type,Action action)throws Exception{
  try{action.run();}catch(Throwable failure){yes(type.isInstance(failure));return;}
  throw new AssertionError("expected "+type);
 }
 static final class Manager implements IActivityManager {
  int gets,removes,calls; boolean missing,nullProvider,acquireError,callError,unmarshalError,releaseError,dieOnce,dieAlways;
  String value="1",authority,method,argument; android.os.IBinder held;
  public ContentProviderHolder getContentProviderExternal(String name,int user,android.os.IBinder token,String tag)throws Exception{
   gets++; yes(user==0); yes(held==null); yes("*acefan-root*".equals(tag));
   if(acquireError)throw new IllegalStateException("acquire"); if(missing)return null;
   authority=name; held=token; ContentProviderHolder holder=new ContentProviderHolder();
   if(!nullProvider) holder.provider=(source,a,m,arg,extra)->{
    calls++; yes(source.uid==0); yes("root".equals(source.name)); yes(a.equals(authority));
    yes(held!=null); method=m; argument=arg;
    if(dieAlways || (dieOnce && calls==1))throw new DeadObjectException();
    if(callError)throw new SecurityException("denied");
    Bundle result=new Bundle(); result.broken=unmarshalError;
    if("settings".equals(a)){yes(extra.get("_user").equals(0));result.put("value",value);}
    else result.put("ok",true);
    return result;
   };
   return holder;
  }
  public void removeContentProviderExternalAsUser(String name,android.os.IBinder token,int user){
   removes++; yes(name.equals(authority)); yes(user==0); yes(token==held); held=null;
   if(releaseError)throw new IllegalStateException("release");
  }
 }
 static Manager manager(){Manager m=new Manager();ActivityManager.service=m;return m;}
 static RootProviderAccess bridge()throws Exception{return new RootProviderAccess();}
 public static void main(String[] args)throws Exception{
  Manager m=manager(); RootProviderAccess b=bridge();
  b.call("dev.eta.acefan.data","config",null,null); yes(m.gets==1&&m.removes==1);
  b.call("dev.eta.acefan.data","dailyConfig",null,null);
  b.call("dev.eta.acefan.data","pushRoot",null,new Bundle());yes(m.gets==3&&m.removes==3);
  yes(b.setting("system","key").equals("1"));yes("GET_system".equals(m.method));yes("key".equals(m.argument));
  m.value=null;yes(b.setting("global","missing")==null);yes("GET_global".equals(m.method));
  fails(SecurityException.class,()->b.call("settings","PUT_system","key",null));
  fails(SecurityException.class,()->b.call("other","config",null,null));
  fails(IllegalArgumentException.class,()->b.setting("secure","key"));yes(m.gets==5);
  yes(b.diagnostics().get("acquired").equals(5L));yes(b.diagnostics().get("released").equals(5L));
  m=manager();m.missing=true;RootProviderAccess absent=bridge();
  fails(IllegalStateException.class,()->absent.call("dev.eta.acefan.data","config",null,null));yes(m.removes==0);
  m=manager();m.acquireError=true;RootProviderAccess denied=bridge();
  fails(IllegalStateException.class,()->denied.call("dev.eta.acefan.data","config",null,null));yes(m.removes==0);
  m=manager();m.nullProvider=true;RootProviderAccess empty=bridge();
  fails(IllegalStateException.class,()->empty.call("dev.eta.acefan.data","config",null,null));yes(m.removes==1);
  m=manager();m.callError=true;RootProviderAccess security=bridge();
  fails(SecurityException.class,()->security.call("dev.eta.acefan.data","config",null,null));yes(m.gets==1&&m.removes==1);
  m=manager();m.unmarshalError=true;RootProviderAccess broken=bridge();
  fails(IllegalStateException.class,()->broken.call("dev.eta.acefan.data","config",null,null));yes(m.removes==1);
  m=manager();m.releaseError=true;RootProviderAccess release=bridge();
  fails(IllegalStateException.class,()->release.call("dev.eta.acefan.data","config",null,null));
  yes(release.diagnostics().get("releaseFailures").equals(1L));
  m=manager();m.dieOnce=true;RootProviderAccess reconnect=bridge();
  reconnect.call("dev.eta.acefan.data","config",null,null);yes(m.gets==2&&m.removes==2);
  m=manager();m.dieAlways=true;RootProviderAccess exhausted=bridge();
  fails(IllegalStateException.class,()->exhausted.call("dev.eta.acefan.data","config",null,null));yes(m.gets==2&&m.removes==2);
  m=manager();m.dieAlways=true;RootProviderAccess uncertain=bridge();
  fails(IllegalStateException.class,()->uncertain.call("dev.eta.acefan.data","pushRoot",null,new Bundle()));yes(m.gets==1&&m.removes==1);
  m=manager(); RootProviderAccess handoff=bridge();
  handoff.call("dev.eta.acefan.data","rootHandoff",null,new Bundle());yes(m.calls==1&&m.removes==1);
  handoff.call("dev.eta.acefan.data","kernelHandoff",null,null);yes(m.calls==2&&m.removes==2);
  m=manager();m.dieAlways=true;RootProviderAccess uncertainHandoff=bridge();
  fails(IllegalStateException.class,()->uncertainHandoff.call("dev.eta.acefan.data","rootHandoff",null,new Bundle()));
  yes(m.gets==1&&m.removes==1);
  android.os.Process.uid=12345;fails(SecurityException.class,()->bridge());
  System.out.println("PASS RootProviderAccess: "+assertions+" assertions; root attribution, GET envelope, allowlist, finally release, read death retry, no uncertain push replay");
 }
}'''
}

with tempfile.TemporaryDirectory(prefix='acefan-root-provider-') as temp:
    temp = Path(temp)
    for name, text in SOURCES.items():
        target = temp / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    shutil.copy(ROOT / 'src/dev/eta/acefan/RootProviderAccess.java', temp / 'dev/eta/acefan/RootProviderAccess.java')
    classes = temp / 'classes'
    subprocess.run(['javac', '--release', '11', '-d', str(classes), *map(str, temp.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', str(classes), 'dev.eta.acefan.RootProviderAccessTest'], check=True)

relay = (ROOT / 'src/dev/eta/acefan/KernelSettingsRelay.java').read_text()
probe = (ROOT / 'src/dev/eta/acefan/RootListenerProbe.java').read_text()
assert 'resolver.call(' not in relay
assert 'getStringForUser' not in relay and 'Settings.Global.getString' not in relay
assert 'providers.call(API.getAuthority(), "pushRoot", null, b)' in relay
assert 'manager.getDefaultSensor(POCKET_SENSOR, true)' in relay
assert 'private volatile int pocket = -1' in relay
assert 'if (pocket == 1' in relay
assert 'POCKET_SENSOR = 0x1fa2683' in relay
for forbidden in ('PolicyFiles', 'RevisionStore', 'Os.write', 'pushRoot', 'Settings.System.put', 'Settings.Global.put'):
    assert forbidden not in probe, forbidden
assert 'finally {' in probe and 'unregisterContentObserver' in probe and 'unregisterListener' in probe
assert 'pocketEvent.await(1500, TimeUnit.MILLISECONDS)' in probe
print('PASS relay/listener structural safety boundaries; no ordinary provider acquisition or Settings API reads')
