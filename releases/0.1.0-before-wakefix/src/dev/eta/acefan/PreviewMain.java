package dev.eta.acefan;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;

/** Bounded ROOT-initiated read-only preview. Not a root service and never starts itself. */
public final class PreviewMain {
 public static void main(String[] args) throws Exception {
  if(Process.myUid()!=0)throw new SecurityException("Preview is only for explicit root diagnostics");
  android.os.Looper.prepareMainLooper();
  Class<?> a=Class.forName("android.app.ActivityThread");
  Object thread=a.getMethod("systemMain").invoke(null);
  Context c=(Context)a.getMethod("getSystemContext").invoke(thread);
  Uri uri=Uri.parse("content://dev.eta.acefan.data");
  System.out.println("ACEFan: read-only preview, 10-minute maximum; no fan writes or hook activation");
  for(int n=0;n<120;n++){
   Bundle latest=c.getContentResolver().call(uri,"latest",null,null);
   if(latest!=null){JSONObject last=new JSONObject(latest.getString("json","{}"));if("已注入".equals(last.optString("hook"))){System.out.println("Hook telemetry detected; preview stopped");return;}}
   JSONObject s=Telemetry.sample(c);
   Bundle cfg=c.getContentResolver().call(uri,"config",null,null);
   s.put("hook","Root只读预览");s.put("active",false);s.put("build",android.os.Build.DISPLAY);s.put("reason","真实节点只读预览；LSPosed尚未接管风扇");s.put("configVersion",cfg==null?0:cfg.getLong("version"));
   s.put("oem",JSONObject.NULL);s.put("candidate",JSONObject.NULL);s.put("request",JSONObject.NULL);
   Bundle b=new Bundle();b.putString("json",s.toString());c.getContentResolver().call(uri,"push",null,b);
   if(n==0||n%12==0)System.out.println("preview "+n+" battery="+s.opt("battery")+" RPM="+s.opt("rpm")+" level="+s.opt("driver"));
   SystemClock.sleep(5000);
  }
  System.out.println("Read-only preview finished");
 }
}
