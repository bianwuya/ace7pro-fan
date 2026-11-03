package dev.eta.acefan;
import java.util.*;
/** Local OEM APK is not redistributed. Read-only code evidence and mutation tests. */
public final class CodeProfileTool {
    static Map<String,String> roles() {
        Map<String,String> m=new LinkedHashMap<String,String>();
        m.put("candidate","Li9/h;->q()I");
        m.put("downstream","Li9/h;->c()V");
        m.put("noise","Li9/h;->s(I)I");
        m.put("recalculate","Li9/d;->f(Ljava/lang/String;)V");
        m.put("apply","Li9/d;->b()V");
        m.put("selectorEnabled","Li9/b;->i()Z");
        m.put("selectorUpdate","Li9/b;->j()V");
        m.put("controllerSet","Lcom/oplus/exsystemservice/fan/controller/OplusFanController;->i(I)V");
        m.put("controllerHealth","Lcom/oplus/exsystemservice/fan/controller/OplusFanController;->s()Z");
        m.put("maximum","Lcom/oplus/exsystemservice/fan/core/ConfigParser;->d()I");
        m.put("refresh","Lcom/oplus/exsystemservice/fan/core/Aeolus;->g(Ljava/lang/String;)V");
        return m;
    }
    static Map<String,String> hashes(String path)throws Exception {
        Map<String,String> roles=roles(), aliases=new HashMap<String,String>();
        aliases.put("Li9/h;","@orchestrator;");aliases.put("Li9/d;","@base;");aliases.put("Li9/b;","@selector;");
        for(Map.Entry<String,String> e:roles.entrySet())aliases.put(e.getValue(),"@"+e.getKey());
        aliases.put("Li9/b;->g()Lcom/oplus/exsystemservice/fan/strategies/Strategy;","@getStrategy");
        aliases.put("Li9/d;->d:Li9/b;","@choice");
        Map<String,String> raw=DexFingerprint.readApk(path,new HashSet<String>(roles.values()),aliases);
        Map<String,String> out=new LinkedHashMap<String,String>();
        for(Map.Entry<String,String> e:roles.entrySet())out.put(e.getKey(),raw.get(e.getValue()));
        return out;
    }
    public static void main(String[] args)throws Exception {
        for(Map.Entry<String,String> e:hashes(args[0]).entrySet())System.out.println(e.getKey()+"="+e.getValue());
    }
}
