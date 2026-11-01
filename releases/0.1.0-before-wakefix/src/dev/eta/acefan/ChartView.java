package dev.eta.acefan;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Canvas chart: temperature and RPM have separate panels/scales; levels use an integer step panel. */
public class ChartView extends View {
    public static final class Sample {
        public final long wall, elapsed; public final String boot; public final JSONObject raw;
        Sample(long wall,long elapsed,String boot,JSONObject raw){this.wall=wall;this.elapsed=elapsed;this.boot=boot;this.raw=raw;}
        public static Sample parse(JSONObject j){return new Sample(j.optLong("wall",0),j.optLong("elapsed",0),j.optString("boot",""),j);}
    }
    final Paint p=new Paint(3); final ArrayList<Sample> data=new ArrayList<>(); boolean dark; int minutes=30;
    final int[] colors={0xffe4572e,0xff2878b5,0xff7b61a8,0xffd19a00,0xff15966a,0xff00a6a6,0xffbd4e87};
    public ChartView(Context c){super(c);dark=(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    public void setSamples(List<Sample> s){data.clear();data.addAll(s);invalidate();}
    public void setRange(int m){minutes=m;invalidate();}
    float dp(float x){return x*getResources().getDisplayMetrics().density;}
    void paint(Canvas c,int color,float size,Paint.Style style){p.setColor(color);p.setTextSize(dp(size));p.setStrokeWidth(dp(1));p.setStyle(style);p.setStrokeCap(Paint.Cap.ROUND);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);int fg=dark?0xffeeeeee:0xff28313b,grid=dark?0xff414750:0xffdce1e7,bg=dark?0xff20242a:0xffffffff;paint(c,bg,12,Paint.Style.FILL);c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),dp(12),dp(12),p);
        long now=System.currentTimeMillis(),start=minutes==0?(data.isEmpty()?now-1800000:data.get(0).wall):now-minutes*60000L;
        ArrayList<Sample> visible=new ArrayList<>();for(Sample s:data)if(s.wall>=start&&s.wall<=now+60000)visible.add(s);
        float left=dp(56),right=getWidth()-dp(14),width=right-left; if(width<=0)return;
        float top=dp(18),gap=dp(30),h=(getHeight()-top-dp(45)-2*gap)/3f;if(h<dp(70))h=dp(70);
        panel(c,visible,fg,grid,left,right,top,h,start,now,"温度（°C）",new String[]{"battery","board","fan","mos"},new String[]{"电池","Board","Fan","MOS"},false);
        panel(c,visible,fg,grid,left,right,top+h+gap,h,start,now,"风扇转速（RPM）",new String[]{"rpm"},new String[]{"RPM"},false);
        panel(c,visible,fg,grid,left,right,top+2*(h+gap),h,start,now,"控制档位",new String[]{"candidate","request","driver","effective"},new String[]{"模块候选","原厂请求","driverLevel","levelEff"},true);
        paint(c,fg,10,Paint.Style.FILL);SimpleDateFormat f=new SimpleDateFormat("HH:mm",Locale.getDefault());for(int i=0;i<=4;i++){float x=left+width*i/4;c.drawText(f.format(new Date(start+(now-start)*i/4)),x-dp(16),getHeight()-dp(9),p);}
        if(visible.isEmpty()){paint(c,fg,14,Paint.Style.FILL);c.drawText("暂无可用遥测数据",left+dp(12),top+h/2,p);}
    }
    void panel(Canvas c,List<Sample> a,int fg,int grid,float l,float r,float y,float h,long start,long end,String title,String[] keys,String[] names,boolean step){
        float w=r-l;paint(c,fg,12,Paint.Style.FILL);c.drawText(title,dp(12),y+dp(12),p);
        float plotTop=y+dp(22),plotBottom=y+h-dp(12),ph=plotBottom-plotTop;if(ph<=0)return;
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        if(step){min=0;max=20;}else for(Sample sample:a)for(String key:keys){Object v=sample.raw.opt(key);if(v instanceof Number){double n=((Number)v).doubleValue();if(!Double.isNaN(n)&&!Double.isInfinite(n)){min=Math.min(min,n);max=Math.max(max,n);}}}
        if(min==Double.POSITIVE_INFINITY){min=0;max=1;} else if(!step){double pad=Math.max(1.0,(max-min)*.12);min-=pad;max+=pad;if(max-min<1){min-=.5;max+=.5;}}
        paint(c,grid,9,Paint.Style.STROKE);
        int ticks=step?5:(keys.length==1?4:4);
        paint(c,fg,9,Paint.Style.FILL);
        for(int i=0;i<ticks;i++){double fraction=i/(double)(ticks-1);float gy=(float)(plotBottom-ph*fraction);c.drawLine(l,gy,r,gy,p);double value=min+(max-min)*fraction;String label=step?Integer.toString((int)Math.round(value)):String.format(Locale.getDefault(),"%.1f",value);c.drawText(label,dp(3),gy+dp(3),p);}
        for(int series=0;series<keys.length;series++){
            int color=colors[(series+(step?4:0))%colors.length];paint(c,color,2,Paint.Style.STROKE);Path path=new Path();boolean pen=false;Sample prev=null;
            for(Sample sample:a){Object v=sample.raw.opt(keys[series]);boolean valid=v instanceof Number;double value=valid?((Number)v).doubleValue():Double.NaN;valid=valid&&!Double.isNaN(value)&&!Double.isInfinite(value);
                if(!valid){pen=false;prev=null;continue;}
                boolean gap=prev!=null&&(!prev.boot.equals(sample.boot)||sample.wall-prev.wall>30000||sample.wall<prev.wall);
                float x=(float)(l+((sample.wall-start)/(double)Math.max(1,end-start))*w);float yy=(float)(plotBottom-((value-min)/(max-min))*ph);
                if(!pen||gap){path.moveTo(x,yy);pen=true;}else if(step){float oldY=(float)(plotBottom-((((Number)prev.raw.opt(keys[series])).doubleValue()-min)/(max-min))*ph);path.lineTo(x,oldY);path.lineTo(x,yy);}else path.lineTo(x,yy);
                prev=sample;
            }
            c.drawPath(path,p);paint(c,color,9,Paint.Style.FILL);c.drawText(names[series],l+dp(3)+series*dp(73),y+dp(13),p);
        }
    }

}
