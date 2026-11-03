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

    /** Diagnostic only: which resolution step was reached before a failure. Never gates control. */
    private static final java.util.List<String> trace = new java.util.ArrayList<String>();
    private static volatile String current = "";
    private static void step(String label) {
        synchronized (trace) { if (trace.size() < 40) trace.add(label); }
    }
    private static void attempting(String stage) {
        current = stage;
        step("→ " + stage);
    }
    private static String nz(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v;
    }
    /** Snapshot of the last resolution attempt, for the exportable device report. */
    public static java.util.List<String> lastTrace() {
        synchronized (trace) { return new java.util.ArrayList<String>(trace); }
    }
    public static void resetTrace() {
        synchronized (trace) { trace.clear(); }
    }
    /** Keeps the reflective-failure type contract while recording which step failed. */
    private static final class StageFailure extends ReflectiveOperationException {
        final String stage;
        StageFailure(String stage, Throwable cause) {
            super(cause == null ? stage : String.valueOf(cause.getMessage()), cause);
            this.stage = stage;
        }
    }
    /** Stage name of the last thrown StageFailure, or "" when the failure was not staged. */
    static String stageOf(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause())
            if (cur instanceof StageFailure) return ((StageFailure) cur).stage;
        return "";
    }
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
        resetTrace();
        step("引擎类已定位：" + engine.getName() + (engine.isEnum() ? "（单例枚举）" : "（非枚举）"));
        if (!engine.isEnum()) throw new StageFailure("引擎入口", new NoSuchFieldException("Aeolus is not a singleton enum"));
        Method init=uniqueMethod(engine, void.class, Modifier.PUBLIC, "f", context, handler);
        step("初始化方法匹配：f(" + context.getSimpleName() + "," + handler.getSimpleName() + ")");
        Method refresh=uniqueMethod(engine, void.class, Modifier.PRIVATE | Modifier.SYNCHRONIZED, "g", String.class);
        Field orchestrator=field(engine, "mOrchestrator", null);
        step("刷新方法匹配：g(String)；编排器字段：mOrchestrator:" + orchestrator.getType().getName());
        if (orchestrator.getType().isPrimitive() || Modifier.isFinal(orchestrator.getModifiers()))
            throw new StageFailure("编排器字段", new NoSuchFieldException("invalid orchestrator field"));
        return new Bootstrap(engine,init,refresh,orchestrator);
    }

    public static FanServiceCompat resolve(Bootstrap boot, Object engine, ClassLoader loader,
            Class<?> context, Class<?> handler) throws Exception {
        current = "";
        Class<?> ctrl, config, strategy;
        try {
            ctrl=Class.forName(ROOT+"controller.OplusFanController", false, loader);
            config=Class.forName(ROOT+"core.ConfigParser", false, loader);
            strategy=Class.forName(ROOT+"strategies.Strategy", false, loader);
        } catch (Throwable t) {
            throw new StageFailure("原厂风扇类定位", t);
        }
        step("原厂类定位：" + ctrl.getSimpleName() + " / " + config.getSimpleName() + " / " + strategy.getSimpleName());
        return resolve(boot,engine,ctrl,config,strategy,context,handler);
    }

    static FanServiceCompat resolve(Bootstrap boot, Object engine, Class<?> ctrl, Class<?> config,
            Class<?> strategy, Class<?> context, Class<?> handler) throws Exception {
        try {
            return resolveStaged(boot, engine, ctrl, config, strategy, context, handler);
        } catch (StageFailure f) {
            throw f;
        } catch (Exception e) {
            throw new StageFailure(nz(current, "未知阶段"), e);
        }
    }

    @SuppressWarnings("unused")
    private static FanServiceCompat resolveStaged(Bootstrap boot, Object engine, Class<?> ctrl, Class<?> config,
            Class<?> strategy, Class<?> context, Class<?> handler) throws Exception {
        attempting("读取编排器实例");
        Object orches=boot.orchestrator.get(engine);
        if (orches==null) throw new NoSuchFieldException("原厂风扇未初始化 / mOrchestrator=null");
        Class<?> concrete=orches.getClass(), base=boot.orchestrator.getType();
        step("编排器实例：" + concrete.getName() + "（基类 " + base.getName() + "）");
        if (concrete==base || !base.isAssignableFrom(concrete)) throw new NoSuchMethodException("not a V3 orchestrator");
        concrete.getDeclaredConstructor(context,handler);
        attempting("候选档位方法");
        Method candidate=uniqueMethod(concrete,int.class,Modifier.PRIVATE,"q");
        step("候选方法匹配：" + candidate.getName() + "()I");
        // Do not guess the downstream protection or controller status methods among same-type getters.
        attempting("后续保护与降噪方法");
        Method compute=exact(concrete,"c",void.class,Modifier.PROTECTED);
        Method noise=exact(concrete,"s",int.class,Modifier.PRIVATE,int.class);
        Method recalc=exact(base,"f",void.class,Modifier.PUBLIC,String.class);
        Method apply=exact(base,"b",void.class,Modifier.PUBLIC);
        attempting("控制器与档位上限方法");
        Method set;
        try {
            set=uniqueMethod(ctrl,void.class,Modifier.PUBLIC,"i",int.class);
        } catch (NoSuchMethodException e) {
            step("控制器 set 候选（public void(int)）：" + candidates(ctrl, void.class, int.class));
            throw e;
        }
        Method health=healthMethod(ctrl);
        Method maximum;
        try {
            maximum=uniqueMethod(config,int.class,Modifier.PUBLIC,"d");
        } catch (NoSuchMethodException e) {
            step("档位上限候选（public int()）：" + candidates(config, int.class));
            throw e;
        }
        attempting("控制器状态字段");
        Field updated=field(ctrl,"mCurrentSpeedLevelUpdated",boolean.class);
        Field event=field(ctrl,"mLastUEvent",int.class);
        attempting("策略枚举");
        Method strategyName=uniqueMethod(strategy,String.class,Modifier.PUBLIC,"b");
        if (!strategy.isEnum()) throw new NoSuchFieldException("Strategy is not an enum");
        java.util.HashSet<String> names=new java.util.HashSet<String>();
        for(Object s:strategy.getEnumConstants()) names.add(String.valueOf(strategyName.invoke(s)));
        step("策略枚举：" + names);
        if (!names.containsAll(Arrays.asList("Common","KeyguardLocked","ChargeWhileKeyguardLocked","SuperCharging","ShutDown","PocketMode","TelephonyInHeld")))
            throw new NoSuchFieldException("策略名称/保护场景不匹配");
        attempting("场景选择器");
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
        step("选择器：" + choice.getDeclaringClass().getName() + "." + choice.getName() + " : " + choice.getType().getName());
        Class<?> selector=choice.getType();
        attempting("选择器使能与更新方法");
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
        attempting("关键保护链代码指纹");
        Map<String,String> found=fingerprints(apk);
        java.util.List<String> bad=new java.util.ArrayList<String>();
        for(Map.Entry<String,String> e:ReferenceFanProfile.HASHES.entrySet()) {
            String got=found.get(e.getKey());
            if(!e.getValue().equals(got)) bad.add(e.getKey() + (got==null ? "（缺失）" : "（不一致）"));
        }
        if(found.size()!=ReferenceFanProfile.HASHES.size()) bad.add("档案条目数 " + found.size());
        if(!bad.isEmpty()) {
            step("指纹不通过的成员：" + bad);
            throw new NoSuchMethodException("关键保护链代码变化："+bad.get(0)+"；需要新固件样本适配");
        }
        step("11 处关键方法指纹全部通过");
    }

    Map<String,String> fingerprints(String apk) throws Exception {
        Map<String,String> roleByKey=new HashMap<String,String>();
        for(Map.Entry<String,Method> e:evidence.entrySet())roleByKey.put(methodKey(e.getValue()),e.getKey());
        Map<String,String> raw=DexFingerprint.readApk(apk,roleByKey.keySet(),aliases);
        Map<String,String> out=new LinkedHashMap<String,String>();
        for(Map.Entry<String,String> e:raw.entrySet())out.put(roleByKey.get(e.getKey()),e.getValue());
        return out;
    }

    /** Diagnostic: which public methods of this shape exist, for reports on unknown firmware. */
    static String candidates(Class<?> type, Class<?> returns, Class<?>... args) {
        StringBuilder b = new StringBuilder();
        for (Method m : type.getDeclaredMethods()) {
            int flags = m.getModifiers();
            if (Modifier.isStatic(flags) || m.isSynthetic()) continue;
            if (m.getReturnType() != returns) continue;
            if (!Arrays.equals(m.getParameterTypes(), args)) continue;
            if (b.length() > 0) b.append(", ");
            b.append(m.getName()).append('(').append(')').append(returns == boolean.class ? "Z" : returns.getSimpleName());
            if (b.length() > 240) { b.append(", …"); break; }
        }
        return b.length() == 0 ? "无" : b.toString();
    }

    /**
     * Controller health getter. The reference firmware names it "s", but obfuscation renames
     * between ROM builds: a uniquely-shaped public boolean getter is an acceptable substitute
     * because the 11-method code profile still has to match its body before any hook is installed.
     */
    private static Method healthMethod(Class<?> ctrl) throws Exception {
        try {
            return exact(ctrl, "s", boolean.class, Modifier.PUBLIC);
        } catch (NoSuchMethodException absent) {
            step("控制器无 s()Z；同形候选：" + candidates(ctrl, boolean.class));
            Method found = null;
            for (Method m : ctrl.getDeclaredMethods()) {
                int flags = m.getModifiers();
                if (Modifier.isStatic(flags) || m.isSynthetic()) continue;
                if (m.getReturnType() != boolean.class || m.getParameterTypes().length != 0) continue;
                if ((flags & Modifier.PUBLIC) != Modifier.PUBLIC) continue;
                if (found != null) throw new NoSuchMethodException("ambiguous controller health: "
                        + found.getName() + " / " + m.getName() + "（该固件需人工确认）");
                found = m;
            }
            if (found == null) throw absent;
            step("控制器健康方法改名为：" + found.getName());
            found.setAccessible(true);
            return found;
        }
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
