package dev.eta.acefan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Direct OEM interface adapter. No APK, DEX or reference-firmware comparison. */
public final class FanServiceCompat {
    private static final String ROOT="com.oplus.exsystemservice.fan.";
    public static final String PROFILE="ace7-live-interfaces";
    private static final List<String> trace=new ArrayList<String>();
    private static volatile String current="";
    static void step(String s){synchronized(trace){if(trace.size()<48)trace.add(s);}}
    static void attempting(String s){current=s;step("→ "+s);}
    public static List<String> lastTrace(){synchronized(trace){return new ArrayList<String>(trace);}}
    public static void resetTrace(){synchronized(trace){trace.clear();}current="";}
    private static final class StageFailure extends ReflectiveOperationException {
        final String stage;
        StageFailure(String s,Throwable t){super(s+": "+summary(t),t);stage=s;}
    }
    static String stageOf(Throwable t){for(Throwable c=t;c!=null;c=c.getCause())if(c instanceof StageFailure)return ((StageFailure)c).stage;return "";}
    public static String summary(Throwable t){
        while(t instanceof java.lang.reflect.InvocationTargetException && t.getCause()!=null)t=t.getCause();
        if(t==null)return "未知异常";
        String s=t.getClass().getName()+": "+String.valueOf(t.getMessage());
        StackTraceElement[] stack=t.getStackTrace();
        if(stack.length>0)s+=" @ "+stack[0];
        return s.length()>360?s.substring(0,360):s;
    }
    public static final class Bootstrap {
        public final Class<?> engineClass;
        public final Method initialize,refresh;
        public final Field orchestrator;
        Bootstrap(Class<?> c,Method i,Method r,Field f){engineClass=c;initialize=i;refresh=r;orchestrator=f;}
    }
    public final Object orchestratorObject,controller,parser;
    public final List<Method> candidates,strategyGetters,setters;
    public final Field choice,updated,lastEvent,currentLevel,maximumField;
    public final Method maxLevel;
    private final List<Method> strategyNames;
    private Object mode;
    private Method automatic;

    private FanServiceCompat(Object o,Object c,Object p,List<Method> cs,List<Method> gs,List<Method> ss,
            Field choice,Field updated,Field lastEvent,Field currentLevel,Field maximumField,Method maximum,List<Method> names){
        orchestratorObject=o;controller=c;parser=p;candidates=cs;strategyGetters=gs;setters=ss;
        this.choice=choice;this.updated=updated;this.lastEvent=lastEvent;this.currentLevel=currentLevel;
        this.maximumField=maximumField;maxLevel=maximum;strategyNames=names;
    }
    public static Bootstrap bootstrap(ClassLoader loader,Class<?> context,Class<?> handler)throws Exception{
        resetTrace();attempting("引擎类定位");
        try{return bootstrap(Class.forName(ROOT+"core.Aeolus",false,loader),context,handler);}
        catch(Exception e){throw staged(e);}
    }
    static Bootstrap bootstrap(Class<?> engine,Class<?> context,Class<?> handler)throws Exception{
        try{
            attempting("初始化入口");
            List<Method> init=methods(engine,void.class,context,handler);
            List<Method> publicInit=new ArrayList<Method>();
            for(Method m:init)if(Modifier.isPublic(m.getModifiers()))publicInit.add(m);
            Method initialize=one(publicInit.isEmpty()?init:publicInit,"初始化 void(Context,Handler)");
            attempting("原厂重算入口");
            Method refresh=one(methods(engine,void.class,String.class),"重算 void(String)");
            Field orchestrator=optionalField(engine,"mOrchestrator",null);
            step("初始化："+methodKey(initialize));step("重算："+methodKey(refresh));
            if(orchestrator==null)step("mOrchestrator 未找到，将从实际引擎对象定位编排器");
            return new Bootstrap(engine,initialize,refresh,orchestrator);
        }catch(Exception e){throw staged(e);}
    }
    public static FanServiceCompat resolve(Bootstrap boot,Object engine,ClassLoader loader,Class<?> context,Class<?> handler)throws Exception{
        Class<?> ctrl=optionalClass(loader,ROOT+"controller.OplusFanController");
        Class<?> config=optionalClass(loader,ROOT+"core.ConfigParser");
        Class<?> strategy=optionalClass(loader,ROOT+"strategies.Strategy");
        FanServiceCompat result=resolve(boot,engine,ctrl,config,strategy,context,handler);
        Class<?> modeClass=optionalClass(loader,ROOT+"core.FanMode");
        result.mode=optionalSingleton(modeClass);
        List<Method> choices=modeClass==null?new ArrayList<Method>():publicMethods(modeClass,boolean.class);
        if(choices.size()==1)result.automatic=choices.get(0);
        step("原厂自动模式："+(result.automatic==null?"不可用，沿用明确设置与原厂场景":result.automatic));
        return result;
    }
    static FanServiceCompat resolve(Bootstrap boot,Object engine,Class<?> ctrl,Class<?> config,Class<?> strategy,Class<?> context,Class<?> handler)throws Exception{
        try{
            attempting("读取编排器实例");
            Object orches=boot.orchestrator==null?findOrchestrator(engine):boot.orchestrator.get(engine);
            if(orches==null)throw new NoSuchFieldException("原厂尚未初始化编排器；可在下次初始化继续");
            step("实际编排器："+orches.getClass().getName());
            attempting("候选档位入口");
            List<Method> candidates=candidateMethods(orches.getClass());
            if(candidates.isEmpty())throw new NoSuchMethodException("无候选 int()；实际方法："+inventory(orches.getClass()));
            for(Method m:candidates)step("候选："+methodKey(m));
            if(candidates.size()>1)step("多个候选，先观察原厂调用与策略读取，不凭混淆名挑选");
            attempting("当前场景读取");
            if(strategy==null)throw new ClassNotFoundException("原厂 Strategy 类缺失");
            Field choice=null;List<Method> getters=null;
            for(Field f:fields(orches.getClass())){
                if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive()||f.getType().getName().startsWith("java.")||f.getType()==context||f.getType()==handler)continue;
                List<Method> gs=methods(f.getType(),strategy);
                if(gs.isEmpty())continue;
                f.setAccessible(true);Object actual=f.get(orches);if(actual==null)continue;
                if(choice!=null)throw new NoSuchFieldException("多个实际策略选择器："+choice+" / "+f);
                choice=f;getters=gs;
            }
            if(choice==null)throw new NoSuchFieldException("当前策略选择器缺失；编排器字段："+fieldInventory(orches.getClass()));
            step("场景选择器："+choice+"；读取入口 "+getters);
            List<Method> names=methods(strategy,String.class);
            attempting("独立能力读取");
            Object controller=optionalSingleton(ctrl),parser=optionalSingleton(config);
            List<Method> setters=ctrl==null?new ArrayList<Method>():publicMethods(ctrl,void.class,int.class);
            Field max=optionalField(config,"mSpeedLevelMax",int.class);
            List<Method> maxMethods=config==null?new ArrayList<Method>():publicMethods(config,int.class);
            Method maximum=maxMethods.size()==1?maxMethods.get(0):null;
            Field updated=optionalField(ctrl,"mCurrentSpeedLevelUpdated",boolean.class);
            Field event=optionalField(ctrl,"mLastUEvent",int.class);
            Field level=optionalField(ctrl,"mCurrentSpeedLevel",int.class);
            step("档位上限："+(max!=null?max:maximum!=null?maximum:"读取入口缺失，将报告本环节"));
            step("控制请求观察："+(setters.isEmpty()?"不可用；候选仍可运行，锁屏衔接等待确认":setters.toString()));
            step("健康事件："+(event==null?"未知，保留原厂故障处理":event.toString()));
            step("确认字段："+(updated==null?"不可用；不虚报确认":updated.toString()));
            return new FanServiceCompat(orches,controller,parser,candidates,getters,setters,choice,updated,event,level,max,maximum,names);
        }catch(Exception e){throw staged(e);}
    }
    private static StageFailure staged(Exception e){return e instanceof StageFailure?(StageFailure)e:new StageFailure(current,e);}
    public Boolean readAutomatic()throws Exception{return mode!=null&&automatic!=null?(Boolean)automatic.invoke(mode):null;}
    public int readMaximum()throws Exception{
        if(parser==null)throw new NoSuchFieldException("ConfigParser 实例不可用");
        int n;
        if(maximumField!=null)n=maximumField.getInt(parser);
        else if(maxLevel!=null)n=((Number)maxLevel.invoke(parser)).intValue();
        else throw new NoSuchFieldException("mSpeedLevelMax / 唯一 int() 均不可用");
        if(n<=0)throw new IllegalStateException("原厂上限尚未就绪："+n);
        return n;
    }
    public String readStrategy()throws Exception{
        Object selector=choice.get(orchestratorObject);
        String found=null;
        for(Method m:strategyGetters){
            Object value=m.invoke(selector);if(value==null)continue;
            String name=strategyLabel(value);
            if(found!=null&&!found.equals(name))throw new IllegalStateException("场景读取结果不一致："+found+" / "+name);
            found=name;
        }
        if(found==null)throw new IllegalStateException("当前策略为空");return found;
    }
    private String strategyLabel(Object value)throws Exception{
        for(Method m:strategyNames){try{String s=String.valueOf(m.invoke(value));if(knownScene(s))return s;}catch(ReflectiveOperationException e){step("标签读取失败："+methodKey(m)+" "+summary(e));}}
        return value instanceof Enum?((Enum<?>)value).name():String.valueOf(value);
    }
    private static boolean knownScene(String s){return allowedScene(s)||Arrays.asList("ShutDown","PocketMode","TelephonyInHeld").contains(s);}
    public static boolean allowedScene(String s){return Arrays.asList("Common","KeyguardLocked","ChargeWhileKeyguardLocked","SuperCharging").contains(s);}
    public Integer event()throws Exception{return controller!=null&&lastEvent!=null?Integer.valueOf(lastEvent.getInt(controller)):null;}
    public Boolean confirmed(Object c)throws Exception{return c!=null&&updated!=null?Boolean.valueOf(updated.getBoolean(c)):null;}
    public Integer level(Object c)throws Exception{return c!=null&&currentLevel!=null?Integer.valueOf(currentLevel.getInt(c)):null;}
    private static Object findOrchestrator(Object engine)throws Exception{
        Object found=null;
        for(Field f:fields(engine.getClass())){
            if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive()||f.getType().getName().startsWith("java."))continue;
            if(candidateMethods(f.getType()).isEmpty())continue;
            f.setAccessible(true);Object o=f.get(engine);if(o==null)continue;
            if(found!=null)throw new NoSuchFieldException("多个编排器对象："+fieldInventory(engine.getClass()));found=o;
        }
        return found;
    }
    static List<Method> candidateMethods(Class<?> type){
        List<Method> all=methods(type,int.class),privateOnes=new ArrayList<Method>();
        for(Method m:all)if(Modifier.isPrivate(m.getModifiers()))privateOnes.add(m);
        return privateOnes.isEmpty()?all:privateOnes;
    }
    static List<Method> methods(Class<?> type,Class<?> returns,Class<?>...args){
        List<Method> found=new ArrayList<Method>();
        for(Class<?> c=type;c!=null&&c!=Object.class&&c!=Enum.class;c=c.getSuperclass()){
            for(Method m:c.getDeclaredMethods()){
                int f=m.getModifiers();
                if(Modifier.isStatic(f)||Modifier.isAbstract(f)||m.isSynthetic()||m.getReturnType()!=returns||!Arrays.equals(m.getParameterTypes(),args))continue;
                boolean duplicate=false;for(Method existing:found)if(existing.getName().equals(m.getName())&&Arrays.equals(existing.getParameterTypes(),args)){duplicate=true;break;}
                if(!duplicate){m.setAccessible(true);found.add(m);}
            }
        }return found;
    }
    private static List<Method> publicMethods(Class<?> c,Class<?> returns,Class<?>...args){
        List<Method> out=new ArrayList<Method>();for(Method m:methods(c,returns,args))if(Modifier.isPublic(m.getModifiers()))out.add(m);return out;
    }
    private static Method one(List<Method> list,String role)throws Exception{if(list.size()!=1)throw new NoSuchMethodException(role+" 实际候选数="+list.size()+" "+list);return list.get(0);}
    private static List<Field> fields(Class<?> type){
        List<Field> list=new ArrayList<Field>();for(Class<?> c=type;c!=null&&c!=Object.class&&c!=Enum.class;c=c.getSuperclass())list.addAll(Arrays.asList(c.getDeclaredFields()));return list;
    }
    private static Field optionalField(Class<?> c,String name,Class<?> expected){
        if(c==null)return null;for(Field f:fields(c))if(f.getName().equals(name)&&!Modifier.isStatic(f.getModifiers())&&(expected==null||f.getType()==expected)){try{f.setAccessible(true);return f;}catch(RuntimeException e){step("可选字段不可访问："+f+" "+summary(e));return null;}}return null;
    }
    private static Class<?> optionalClass(ClassLoader l,String n){try{return Class.forName(n,false,l);}catch(ClassNotFoundException e){step("可选类不可用："+n);return null;}}
    private static Object optionalSingleton(Class<?> c){
        if(c==null)return null;try{
            if(c.isEnum()){Object[] all=c.getEnumConstants();if(all.length==1)return all[0];}
            Object found=null;
            for(Field f:fields(c))if(Modifier.isStatic(f.getModifiers())&&c.isAssignableFrom(f.getType())){f.setAccessible(true);Object o=f.get(null);if(o!=null){if(found!=null&&found!=o)return null;found=o;}}
            return found;
        }catch(Exception e){step("实例读取失败："+c.getName()+" "+summary(e));return null;}
    }
    public static String methodKey(Method m){return m.getDeclaringClass().getName()+"."+m.getName()+Arrays.toString(m.getParameterTypes())+"→"+m.getReturnType().getSimpleName();}
    private static String inventory(Class<?> c){String s=Arrays.toString(c.getDeclaredMethods());return s.length()>700?s.substring(0,700):s;}
    private static String fieldInventory(Class<?> c){String s=fields(c).toString();return s.length()>500?s.substring(0,500):s;}
}
