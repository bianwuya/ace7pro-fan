package dev.eta.acefan;

public final class RuntimeEvidenceTest {
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        RuntimeEvidence e=new RuntimeEvidence();e.ready();
        ok(e.report().contains("候选应用=0"));ok(e.lastStage().isEmpty());
        e.failure("健康状态读取","field",new NoSuchFieldException("missing"),10);
        ok(e.lastStage().equals("健康状态读取"));ok(e.lastError().contains("NoSuchFieldException"));
        for(int i=0;i<12;i++)e.failure("健康状态读取","field",new NoSuchFieldException("missing"),20+i);
        e.attempted(7,6,14,50);e.requested(7,10,true,60);
        ok(e.progress().equals("原厂状态确认"));ok(e.counters().contains("候选应用=1"));ok(e.counters().contains("原厂状态确认=1"));
        ok(e.lastStage().equals("健康状态读取")); // Other successes cannot erase failure history.
        e.success("健康状态读取","field","恢复",70);
        ok(e.report().contains("健康状态读取：已恢复"));ok(e.report().contains("失败=13"));ok(e.report().contains("elapsed=70ms"));
        e.requested(8,0,false,80);ok(e.counters().contains("原厂状态确认=1"));ok(e.progress().equals("控制调用完成"));
        e.unavailable("辅助遥测","未找到入口");ok(e.report().contains("不可用"));
        RuntimeEvidence boot=new RuntimeEvidence();
        boot.waiting("配置读取","首次解锁前");ok(boot.activeFailures().isEmpty());ok(boot.lastStage().isEmpty());
        boot.failure("配置读取","Provider.config",new IllegalArgumentException("Unknown authority dev.eta.acefan.data"),13751);
        boot.failure("报告回传","Provider.push",new IllegalArgumentException("Unknown authority dev.eta.acefan.data"),20467);
        ok(boot.activeFailures().contains("配置读取"));ok(boot.activeFailures().contains("报告回传"));ok(!boot.lastFailureResolved());
        boot.success("配置读取","Provider.config","配置版本=1；enabled=true",64780);
        ok(!boot.activeFailures().contains("配置读取"));ok(boot.activeFailures().contains("报告回传"));
        boot.success("报告回传","Provider.push","ok=true",64783);
        ok(boot.activeFailures().isEmpty());ok(boot.lastFailureResolved());
        ok(boot.report().contains("报告回传：已恢复"));ok(boot.report().contains("Unknown authority"));
        ok(boot.report().contains("最近失败elapsed=20467ms"));ok(boot.lastError().contains("Unknown authority"));
        boot.failure("报告回传","Provider.push",new IllegalStateException("again"),70000);
        ok(!boot.lastFailureResolved());ok(boot.activeFailures().equals("报告回传"));
        boot.waiting("报告回传","等待");ok(!boot.lastFailureResolved());ok(boot.activeFailures().equals("报告回传"));
        boot.unavailable("报告回传","未知");ok(!boot.lastFailureResolved());ok(boot.activeFailures().equals("报告回传"));
        boot.success("报告回传","Provider.push","ok",71000);ok(boot.lastFailureResolved());ok(boot.activeFailures().isEmpty());
        System.out.println("RuntimeEvidence: "+checks+" recovery / independent errors / historical failure checks PASS");
    }
}
