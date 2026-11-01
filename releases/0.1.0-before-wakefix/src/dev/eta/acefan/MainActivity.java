package dev.eta.acefan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Build;
import android.content.pm.PackageManager;

/** First-party, standard Android UI. All provider and document I/O runs off the UI thread. */
public class MainActivity extends Activity {
    static final String AUTHORITY = "dev.eta.acefan.data";
    static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    static final int CREATE_CSV = 71;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler handler = new Handler(Looper.getMainLooper());
    final ArrayList<ChartView.Sample> samples = new ArrayList<>();
    TextView inject, firmware, status, fault, freshness;
    Switch configSwitch;
    Spinner range;
    ChartView chart;
    boolean alive, configReady;
    final AtomicBoolean loading = new AtomicBoolean(false);
    long lastLocalPush;
    long configVersion;
    int dark, fg, muted, surface;
    final Runnable poll = new Runnable() { @Override public void run() { if (!alive) return; load(false); handler.postDelayed(this, 2000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean isDark=(getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        dark=isDark?1:0; fg=isDark?Color.WHITE:Color.rgb(30,35,42); muted=isDark?0xffb0b7c3:0xff657080; surface=isDark?0xff20242a:Color.WHITE;
        buildUi();
    }
    int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(fg);if(bold)t.setTypeface(null,1);return t;}
    LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    void buildUi(){
        LinearLayout root=vertical(); root.setBackgroundColor(dark==1?0xff121519:0xfff3f5f8);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        TextView title=text("ACE7pro 风扇助手",24,true); title.setPadding(dp(20),dp(22),dp(16),dp(8)); root.addView(title);
        TextView intro=text("通过温度与控制状态遥测辅助调节风扇。仅当前 PMS110 固件支持；条件不满足或数据不可用时回退原厂控制，不绕过保护。",14,false); intro.setTextColor(muted); intro.setPadding(dp(20),0,dp(20),dp(14));root.addView(intro);
        inject=text("注入：读取中…",15,false); firmware=text("固件：--",15,false); status=text("状态：--",15,false); fault=text("故障原因：--",15,false); freshness=text("遥测：--",13,false);
        LinearLayout info=card(root); for(TextView t:new TextView[]{inject,firmware,status,fault,freshness}){t.setPadding(0,dp(4),0,dp(4));info.addView(t);}
        LinearLayout cfg=card(root); configSwitch=new Switch(this);configSwitch.setText("息屏充电增强");configSwitch.setTextColor(fg);configSwitch.setTextSize(16);cfg.addView(configSwitch);
        TextView warning=text("仅息屏有线充电且自动/原厂总开允许时生效，不跳过保护。",13,false);warning.setTextColor(muted);cfg.addView(warning);
        configSwitch.setOnCheckedChangeListener((b,on)->{if(!configReady)return; b.setEnabled(false);io.execute(()->{Bundle x=new Bundle();x.putBoolean("enabled",on);Bundle result=null;String err=null;try{result=getContentResolver().call(BASE,"setConfig",null,x);}catch(Exception e){err=e.toString();} final Bundle r=result; final String e=err;handler.post(()->{b.setEnabled(true);if(e!=null||r==null){Toast.makeText(this,"设置失败："+(e==null?"Provider 无响应":e),Toast.LENGTH_LONG).show();load(false);}else{configVersion=r.getLong("version",configVersion);b.setChecked(r.getBoolean("enabled",on));}});});});
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);actions.setPadding(dp(12),dp(6),dp(12),dp(6));root.addView(actions);
        addButton(actions,"刷新",v->load(true));addButton(actions,"导出CSV",v->exportCsv());addButton(actions,"清空历史",v->confirmClear());addButton(actions,"打开LSPosed",v->openLsposed());
        LinearLayout rangeRow=new LinearLayout(this);rangeRow.setGravity(Gravity.CENTER_VERTICAL);rangeRow.setPadding(dp(20),dp(4),dp(20),0);root.addView(rangeRow);rangeRow.addView(text("时间范围",14,true));range=new Spinner(this);String[] options={"最近30分钟","本次数据可用"};range.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.leftMargin=dp(14);rangeRow.addView(range,rp);range.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?>p){}public void onItemSelected(android.widget.AdapterView<?>p,View v,int pos,long id){if(chart!=null)chart.setRange(pos==0?30:0);}});
        chart=new ChartView(this);chart.setRange(30);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(620));cp.setMargins(dp(12),dp(8),dp(12),dp(20));root.addView(chart,cp);
    }
    LinearLayout card(LinearLayout parent){LinearLayout l=vertical();l.setBackgroundColor(surface);l.setPadding(dp(16),dp(12),dp(16),dp(12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(12),dp(6),dp(12),dp(6));parent.addView(l,p);return l;}
    void addButton(LinearLayout row,String label,View.OnClickListener listener){Button b=new Button(this);b.setText(label);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(listener);}
    @Override protected void onResume(){super.onResume();alive=true;handler.removeCallbacks(poll);load(false);handler.postDelayed(poll,2000);}
    @Override protected void onPause(){alive=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(poll);io.shutdown();super.onDestroy();}

    void load(boolean announce){if(!alive || !loading.compareAndSet(false,true))return;io.execute(()->{Bundle cfg=null;ArrayList<ChartView.Sample> got=new ArrayList<>();String error=null;try{cfg=getContentResolver().call(BASE,"config",null,null);Bundle latest=getContentResolver().call(BASE,"latest",null,null); JSONObject lj=null;
            if(latest!=null){String raw=latest.getString("json");if(raw!=null)try{lj=new JSONObject(raw);}catch(Exception ignored){}}
            long now=System.currentTimeMillis(); boolean hooked=lj!=null && ("已注入".equals(lj.optString("hook")) || "Root只读预览".equals(lj.optString("hook"))) && now-lj.optLong("wall",0)>=0 && now-lj.optLong("wall",0)<20000;
            if(!hooked && alive && now-lastLocalPush>=2000){pushLocalSample(now);}
            Uri u=BASE.buildUpon().appendPath("samples").build();try(Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null){int j=c.getColumnIndex("json");while(c.moveToNext()&&got.size()<4000){String s=c.getString(j);if(s!=null)try{got.add(ChartView.Sample.parse(new JSONObject(s)));}catch(Exception ignored){}}}}}catch(Exception e){error=e.toString();}final Bundle c=cfg;final ArrayList<ChartView.Sample> list=got;final String err=error;handler.post(()->{loading.set(false);if(isFinishing()||isDestroyed())return;if(c!=null){configVersion=c.getLong("version",0);configReady=true;configSwitch.setOnCheckedChangeListener(null);configSwitch.setChecked(c.getBoolean("enabled",false));configSwitch.setOnCheckedChangeListener((b,on)->setConfig(on));}samples.clear();samples.addAll(list);chart.setSamples(samples);updateCards(c,list,err);if(announce&&err==null)Toast.makeText(this,"已刷新",Toast.LENGTH_SHORT).show();});});}
    void pushLocalSample(long now){
        try{
            Class<?> tc=Class.forName("dev.eta.acefan.Telemetry");
            Object value=tc.getMethod("sample",android.content.Context.class).invoke(null,this);
            JSONObject j=value instanceof JSONObject?(JSONObject)value:new JSONObject(String.valueOf(value));
            j.put("hook","未注入·App观测");j.put("reason","仅展示，尚未接管；在LSPosed启用并勾选目标包后需正常重启");j.put("build",Build.DISPLAY);j.put("configVersion",configVersion);
            if(!j.has("wall"))j.put("wall",now);if(!j.has("elapsed"))j.put("elapsed",android.os.SystemClock.elapsedRealtime());
            if(!j.has("boot"))j.put("boot", "app-observation");
            for(String k:new String[]{"board","fan","mos","rpm","driver","effective","state","fanStatus","rawSpeed","candidate","request","oem"})if(!j.has(k))j.put(k,JSONObject.NULL);
            Bundle b=new Bundle();b.putString("json",j.toString());getContentResolver().call(BASE,"push",null,b);lastLocalPush=now;
        }catch(Exception ignored){lastLocalPush=now;}
    }
    void openLsposed(){Intent i=getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");if(i==null){Toast.makeText(this,"未找到 LSPosed Manager",Toast.LENGTH_LONG).show();return;}Toast.makeText(this,"仅勾选 com.oplus.exsystemservice 并正常重启后生效；不会自动重启",Toast.LENGTH_LONG).show();startActivity(i);}
    void setConfig(boolean on){configSwitch.setEnabled(false);io.execute(()->{Bundle x=new Bundle();x.putBoolean("enabled",on);Bundle r=null;String e=null;try{r=getContentResolver().call(BASE,"setConfig",null,x);}catch(Exception ex){e=ex.toString();}final Bundle rr=r;final String ee=e;handler.post(()->{configSwitch.setEnabled(true);if(rr==null){Toast.makeText(this,"设置失败："+(ee==null?"无响应":ee),Toast.LENGTH_LONG).show();load(false);}else{configVersion=rr.getLong("version",configVersion);configSwitch.setChecked(rr.getBoolean("enabled",on));}});});}
    String val(JSONObject j,String key,String unit){Object v=j.opt(key);if(v==null||v==JSONObject.NULL)return "--";return String.valueOf(v)+(unit==null?"":" "+unit);}
    void updateCards(Bundle cfg,List<ChartView.Sample> list,String err){if(err!=null){inject.setText("注入：Provider 不可用");status.setText("状态：读取失败");fault.setText("故障原因："+err);return;}inject.setText("注入："+(list.isEmpty()?"等待遥测":("已注入".equals(list.get(list.size()-1).raw.optString("hook"))?"已注入（Hook 遥测）":list.get(list.size()-1).raw.optString("hook","未注入·App观测")+"（仅观测，未接管）")));if(list.isEmpty()){firmware.setText("固件：--");status.setText("状态：暂无遥测");fault.setText("故障原因：暂无数据");freshness.setText("遥测：等待首条样本");return;}ChartView.Sample s=list.get(list.size()-1);JSONObject j=s.raw;firmware.setText("固件："+j.optString("build","--")+" · Hook "+j.optString("hook","--"));status.setText("状态：active "+(j.has("active")&&!j.isNull("active")?String.valueOf(j.optBoolean("active")):"--")+" · fanStatus "+val(j,"fanStatus",null)+" · 候选 "+val(j,"candidate",null)+" · 原厂请求 "+val(j,"request",null)+" · driverLevel "+val(j,"driver",null)+" · levelEff "+val(j,"effective",null));fault.setText("故障原因："+j.optString("reason","--"));long age=Math.max(0,System.currentTimeMillis()-s.wall);freshness.setText("当前温度：电池 "+val(j,"battery","°C")+" · Board "+val(j,"board","°C")+" · Fan "+val(j,"fan","°C")+" · MOS "+val(j,"mos","°C")+"\n遥测："+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(s.wall))+" · "+(age<20000?"新鲜":age<30000?"延迟（20–30秒）":"已过期")+"（"+(age/1000)+"秒前；elapsed "+s.elapsed+"ms） · RPM "+(j.has("rpm")&&!j.isNull("rpm")?(j.optInt("rpm")==0?"0（零读数）":j.optInt("rpm")+" RPM"):"--"));}
    void confirmClear(){new AlertDialog.Builder(this).setTitle("清空历史？").setMessage("将删除 Provider 中的历史遥测，无法撤销。当前开关设置不受影响。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->io.execute(()->{String e=null;try{getContentResolver().call(BASE,"clear",null,null);}catch(Exception ex){e=ex.toString();}final String er=e;handler.post(()->{Toast.makeText(this,er==null?"历史已清空":"清空失败："+er,Toast.LENGTH_LONG).show();load(false);});})).show();}
    void exportCsv(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"ace7pro-fan-telemetry.csv");startActivityForResult(i,CREATE_CSV);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=CREATE_CSV||result!=RESULT_OK||data==null)return;Uri uri=data.getData();ArrayList<ChartView.Sample> copy=new ArrayList<>(samples);io.execute(()->{String error=null;try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new Exception("无法打开目标文件");out.write(csv(copy).getBytes(StandardCharsets.UTF_8));}catch(Exception e){error=e.toString();}final String er=error;handler.post(()->Toast.makeText(this,er==null?"CSV 已导出":"导出失败："+er,Toast.LENGTH_LONG).show());});}
    static String csv(List<ChartView.Sample> data){StringBuilder b=new StringBuilder("wall_iso,wall_ms,elapsed_ms,boot,battery_C,board_C,fan_C,mos_C,rpm,driverLevel,levelEff,state,oem,candidate,request,active,reason,interactive,plugged,charging,quality,configVersion,hook,build\n");for(ChartView.Sample s:data){JSONObject j=s.raw;String[] keys={"boot","battery","board","fan","mos","rpm","driver","effective","state","oem","candidate","request","active","reason","interactive","plugged","charging","quality","configVersion","hook","build"};b.append('"').append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date(s.wall))).append("\",").append(s.wall).append(',').append(s.elapsed);for(String k:keys){Object o=j.opt(k);b.append(',').append(o==null||o==JSONObject.NULL?"":quote(String.valueOf(o)));}b.append('\n');}return b.toString();}
    static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
}
