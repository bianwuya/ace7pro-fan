package dev.eta.acefan;

/** Associates an OEM compute result with apply() later in the same refresh, per thread. */
public final class ControlCycle<T> {
    public static final class Token<T> {
        private final Token<T> parent;
        private T pending;
        private Token(Token<T> p){parent=p;}
    }
    private final ThreadLocal<Token<T>> active=new ThreadLocal<Token<T>>();
    public Token<T> enter(){Token<T> t=new Token<T>(active.get());active.set(t);return t;}
    public void exit(Token<T> t){
        if(active.get()!=t)return;
        if(t.parent==null)active.remove();
        else{if(t.pending!=null)t.parent.pending=t.pending;active.set(t.parent);}
    }
    public boolean attach(T value){Token<T> t=active.get();if(t==null)return false;t.pending=value;return true;}
    public T take(){Token<T> t=active.get();if(t==null)return null;T value=t.pending;t.pending=null;return value;}
}
