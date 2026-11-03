package dev.eta.acefan;
public final class ControlCycleTest {
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception{
        final ControlCycle<String> c=new ControlCycle<String>();
        ok(!c.attach("unrelated"));ok(c.take()==null);
        ControlCycle.Token<String> refresh=c.enter();ControlCycle.Token<String> compute=c.enter();
        ok(c.attach("decision-1"));c.exit(compute);
        ok(c.take().equals("decision-1"));ok(c.take()==null); // apply happens after compute returns.
        c.exit(refresh);ok(c.take()==null);
        refresh=c.enter();c.attach("expired");c.exit(refresh);refresh=c.enter();ok(c.take()==null);c.exit(refresh);
        refresh=c.enter();c.attach("main-thread");
        Thread t=new Thread(new Runnable(){public void run(){ok(c.take()==null);ControlCycle.Token<String> token=c.enter();c.attach("other-thread");ok(c.take().equals("other-thread"));c.exit(token);}});
        t.start();t.join();ok(c.take().equals("main-thread"));c.exit(refresh);
        System.out.println("ControlCycle: "+checks+" compute-to-apply / stale discard / thread isolation checks PASS");
    }
}
