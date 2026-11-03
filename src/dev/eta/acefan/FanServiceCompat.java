package dev.eta.acefan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** OEM V3 adapter. Resolve everything before installing a control hook; ambiguity is not support. */
public final class FanServiceCompat {
    private static final String ROOT = "com.oplus.exsystemservice.fan.";
    public static final String PROFILE = "oplus-fan-v3-code1";
    public static final class Bootstrap {
        public final Class<?> engineClass;
        public final Method initialize, refresh;
        public final Field orchestrator;
        Bootstrap(Class<?> c, Method i, Method r, Field f) { engineClass=c; initialize=i; refresh=r; orchestrator=f; }
    }

    public final Method candidate, setLevel, healthy, maxLevel, getStrategy, strategyName;
    public final Field choice, updated, lastEvent;
    public final Object controller, parser;
    public final Map<String, Method> evidence;
    private final Map<String, String> aliases;

    private FanServiceCompat(Method candidate, Method setLevel, Method healthy, Method maxLevel,
            Method getStrategy, Method strategyName, Field choice, Field updated, Field lastEvent,
            Object controller, Object parser, Map<String, Method> evidence, Map<String, String> aliases) {
        this.candidate=candidate; this.setLevel=setLevel; this.healthy=healthy; this.maxLevel=maxLevel;
        this.getStrategy=getStrategy; this.strategyName=strategyName; this.choice=choice;
        this.updated=updated; this.lastEvent=lastEvent; this.controller=controller; this.parser=parser;
        this.evidence=evidence; this.aliases=aliases;
    }

    public static Bootstrap bootstrap(ClassLoader loader, Class<?> context, Class<?> handler) throws Exception {
        return bootstrap(Class.forName(ROOT+"core.Aeolus", false, loader), context, handler);
    }
    static Bootstrap bootstrap(Class<?> engine, Class<?> context, Class<?> handler) throws Exception {
        if (!engine.isEnum()) throw new NoSuchFieldException("Aeolus is not a singleton enum");
        Method init=uniqueMethod(engine, void.class, Modifier.PUBLIC, "f", context, handler);
        Method refresh=uniqueMethod(engine, void.class, Modifier.PRIVATE | Modifier.SYNCHRONIZED, "g", String.class);
        Field orchestrator=field(engine, "mOrchestrator", null);
        if (orchestrator.getType().isPrimitive() || Modifier.isFinal(orchestrator.getModifiers()))
            throw new NoSuchFieldException("invalid orchestrator field");
        return new Bootstrap(engine,init,refresh,orchestrator);
    }

    public static FanServiceCompat resolve(Bootstrap boot, Object engine, ClassLoader loader,
            Class<?> context, Class<?> handler) throws Exception {
        Class<?> ctrl=Class.forName(ROOT+"controller.OplusFanController", false, loader);
        Class<?> config=Class.forName(ROOT+"core.ConfigParser", false, loader);
        Class<?> strategy=Class.forName(ROOT+"strategies.Strategy", false, loader);
        return resolve(boot,engine,ctrl,config,strategy,context,handler);
    }

    static FanServiceCompat resolve(Bootstrap boot, Object engine, Class<?> ctrl, Class<?> config,
            Class<?> strategy, Class<?> context, Class<?> handler) throws Exception {
        Object orches=boot.orchestrator.get(engine);
        if (orches==null) throw new NoSuchFieldException("原厂风扇未初始化 / mOrchestrator=null");
        Class<?> concrete=orches.getClass(), base=boot.orchestrator.getType();
        if (concrete==base || !base.isAssignableFrom(concrete)) throw new NoSuchMethodException("not a V3 orchestrator");
        concrete.getDeclaredConstructor(context,handler);
        Method candidate=uniqueMethod(concrete,int.class,Modifier.PRIVATE,"q");
        // Do not guess the downstream protection or controller status methods among same-type getters.
        Method compute=exact(concrete,"c",void.class,Modifier.PROTECTED);
        Method noise=exact(concrete,"s",int.class,Modifier.PRIVATE,int.class);
        Method recalc=exact(base,"f",void.class,Modifier.PUBLIC,String.class);
        Method apply=exact(base,"b",void.class,Modifier.PUBLIC);
        Method set=uniqueMethod(ctrl,void.class,Modifier.PUBLIC,"i",int.class);
        Method health=exact(ctrl,"s",boolean.class,Modifier.PUBLIC);
        Method maximum=uniqueMethod(config,int.class,Modifier.PUBLIC,"d");
        Field updated=field(ctrl,"mCurrentSpeedLevelUpdated",boolean.class);
        Field event=field(ctrl,"mLastUEvent",int.class);
        Method strategyName=uniqueMethod(strategy,String.class,Modifier.PUBLIC,"b");
        if (!strategy.isEnum()) throw new NoSuchFieldException("Strategy is not an enum");
        java.util.HashSet<String> names=new java.util.HashSet<String>();
        for(Object s:strategy.getEnumConstants()) names.add(String.valueOf(strategyName.invoke(s)));
        if (!names.containsAll(Arrays.asList("Common","KeyguardLocked","ChargeWhileKeyguardLocked","SuperCharging","ShutDown","PocketMode","TelephonyInHeld")))
            throw new NoSuchFieldException("策略名称/保护场景不匹配");
        Field choice=null; Method getter=null;
        for(Field f:base.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers()) || f.getType().isPrimitive()
                    || f.getType()==context || f.getType()==handler || f.getType()==String.class)continue;
            Method possible=null;
            for(Method m:f.getType().getDeclaredMethods())
                if(!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic() && m.getReturnType()==strategy && m.getParameterTypes().length==0) {
                    if(possible!=null)throw new NoSuchMethodException("ambiguous strategy getter"); possible=m;
                }
            if(possible==null)continue;
            if(choice!=null)throw new NoSuchFieldException("ambiguous selector field");
            choice=f; getter=possible;
        }
        if(choice==null)throw new NoSuchFieldException("strategy selector missing");
        choice.setAccessible(true); getter.setAccessible(true);
        Class<?> selector=choice.getType();
        Method enabled=exact(selector,"i",boolean.class,0);
        Method select=exact(selector,"j",void.class,0);
        Map<String,Method> proof=new LinkedHashMap<String,Method>();
        proof.put("candidate",candidate); proof.put("downstream",compute); proof.put("noise",noise);
        proof.put("recalculate",recalc); proof.put("apply",apply); proof.put("selectorEnabled",enabled);
        proof.put("selectorUpdate",select); proof.put("controllerSet",set); proof.put("controllerHealth",health);
        proof.put("maximum",maximum); proof.put("refresh",boot.refresh);
        Map<String,String> aliases=new HashMap<String,String>();
        aliases.put(descriptor(concrete),"@orchestrator;"); aliases.put(descriptor(base),"@base;");
        aliases.put(descriptor(selector),"@selector;");
        for(Map.Entry<String,Method> p:proof.entrySet()) aliases.put(methodKey(p.getValue()),"@"+p.getKey());
        aliases.put(methodKey(getter),"@getStrategy"); aliases.put(fieldKey(choice),"@choice");
        return new FanServiceCompat(candidate,set,health,maximum,getter,strategyName,choice,updated,event,
                singleton(ctrl),singleton(config),proof,aliases);
    }

    public void verifyCode(String apk) throws Exception {
        Map<String,String> found=fingerprints(apk);
        for(Map.Entry<String,String> e:ReferenceFanProfile.HASHES.entrySet())
            if(!e.getValue().equals(found.get(e.getKey())))
                throw new NoSuchMethodException("关键保护链代码变化："+e.getKey()+"；需要新固件样本适配");
        if(found.size()!=ReferenceFanProfile.HASHES.size())throw new NoSuchMethodException("code profile size");
    }

    Map<String,String> fingerprints(String apk) throws Exception {
        Map<String,String> roleByKey=new HashMap<String,String>();
        for(Map.Entry<String,Method> e:evidence.entrySet())roleByKey.put(methodKey(e.getValue()),e.getKey());
        Map<String,String> raw=DexFingerprint.readApk(apk,roleByKey.keySet(),aliases);
        Map<String,String> out=new LinkedHashMap<String,String>();
        for(Map.Entry<String,String> e:raw.entrySet())out.put(roleByKey.get(e.getKey()),e.getValue());
        return out;
    }

    /** Signature-only fallback is allowed only where unique; code evidence is still mandatory. */
    static Method uniqueMethod(Class<?> type, Class<?> returns, int required, String preferred, Class<?>... args) throws Exception {
        Method found=null;
        for(Method m:type.getDeclaredMethods()) {
            int flags=m.getModifiers();
            if(Modifier.isStatic(flags)||m.isSynthetic()||(flags&required)!=required||m.getReturnType()!=returns||!Arrays.equals(m.getParameterTypes(),args))continue;
            if(found!=null)throw new NoSuchMethodException("ambiguous "+type.getName()+" "+returns.getName()+Arrays.toString(args));
            found=m;
        }
        if(found==null)throw new NoSuchMethodException("missing "+type.getName()+"."+preferred);
        found.setAccessible(true); return found;
    }
    private static Method exact(Class<?> type,String name,Class<?> returns,int required,Class<?>...args)throws Exception {
        Method m=type.getDeclaredMethod(name,args); int flags=m.getModifiers();
        if(Modifier.isStatic(flags)||m.isSynthetic()||(flags&required)!=required||m.getReturnType()!=returns)
            throw new NoSuchMethodException("changed "+type.getName()+"."+name);
        m.setAccessible(true);return m;
    }
    private static Field field(Class<?> type,String name,Class<?> expected)throws Exception {
        Field f=type.getDeclaredField(name);
        if(Modifier.isStatic(f.getModifiers()) || (expected!=null&&f.getType()!=expected))throw new NoSuchFieldException("changed "+name);
        f.setAccessible(true);return f;
    }
    private static Object singleton(Class<?> type)throws Exception {
        if(!type.isEnum())throw new NoSuchFieldException("not singleton enum: "+type.getName());
        Object[] all=type.getEnumConstants();
        if(all.length!=1)throw new NoSuchFieldException("ambiguous singleton: "+type.getName());
        return all[0];
    }
    static String descriptor(Class<?> c) {
        if(c==void.class)return "V";if(c==int.class)return "I";if(c==boolean.class)return "Z";
        if(c==byte.class)return "B";if(c==short.class)return "S";if(c==char.class)return "C";
        if(c==long.class)return "J";if(c==float.class)return "F";if(c==double.class)return "D";
        return c.isArray()?c.getName().replace('.','/'):"L"+c.getName().replace('.','/')+";";
    }
    static String methodKey(Method m) {
        StringBuilder b=new StringBuilder(descriptor(m.getDeclaringClass())).append("->").append(m.getName()).append('(');
        for(Class<?> c:m.getParameterTypes())b.append(descriptor(c));
        return b.append(')').append(descriptor(m.getReturnType())).toString();
    }
    private static String fieldKey(Field f){return descriptor(f.getDeclaringClass())+"->"+f.getName()+":"+descriptor(f.getType());}
}
