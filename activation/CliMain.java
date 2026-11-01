package org.lsposed.lspd.cli;

import android.system.Os;
import defpackage.c;
import defpackage.f;
import defpackage.g3;
import defpackage.g5;
import defpackage.h0;
import defpackage.j2;
import defpackage.j5;
import defpackage.k2;
import defpackage.l7;
import defpackage.m5;
import defpackage.n2;
import defpackage.n5;
import defpackage.s2;
import defpackage.t2;
import defpackage.u3;
import defpackage.v3;
import defpackage.w2;
import defpackage.wa;
import defpackage.y4;
import defpackage.y5;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
/* JADX INFO: loaded from: classes.dex */
public final class CliMain {
    public static void a(f fVar, boolean z) {
        String str = (String) fVar.c;
        if (z) {
            System.out.println((String) fVar.e);
        } else {
            String str2 = (String) fVar.d;
            if (!str.isEmpty()) {
                System.out.println(str);
            }
            if (!str2.isEmpty()) {
                System.err.println(str2);
            }
        }
        int i = fVar.f51a;
        if (i != 0) {
            System.exit(i);
        }
    }

    public static void main(String[] strArr) throws Throwable {
        boolean zB;
        ArrayList arrayList;
        m5 m5VarS;
        int size;
        int i;
        Integer numValueOf;
        int iIntValue;
        Object objB;
        Object objInvoke;
        List listAsList = Arrays.asList(strArr);
        String[] strArr2 = wa.f219a;
        boolean zContains = listAsList.contains(wa.h(-10033043602437L, strArr2));
        if (Os.getuid() != 0) {
            a(f.c(4, wa.h(-10063108373509L, strArr2), wa.h(-10123237915653L, strArr2)), zContains);
            return;
        }
        try {
            try {
                zB = l7.b();
                while (true) {
                    if (i >= size) {
                        h0.b(4, m5VarS, "Help was not requested. Continuing to process ParseResult...", new Object[0]);
                        numValueOf = null;
                        break;
                    }
                    Object obj = arrayList.get(i);
                    i++;
                    y5 y5Var = (y5) obj;
                    w2 w2Var = y5Var.g;
                    if (y5Var.e == null) {
                        y5Var.o(new PrintWriter((Writer) new BufferedWriter(new OutputStreamWriter(System.out, y5.c(System.getProperty("sun.stdout.encoding")))), true));
                    }
                    PrintWriter printWriter = y5Var.e;
                    j5 j5Var = y5Var.b.f;
                    if (j5Var != null && j5Var.m) {
                        h0.b(4, m5VarS, "Printing usage help for '%s' as requested.", y5Var.f235a.v(" "));
                        StringBuilder sb = new StringBuilder();
                        v3 v3Var = y5Var.f235a;
                        y4 y4Var = v3Var.q;
                        if (y4Var.f234a == null) {
                            y4Var.f234a = new k2();
                        }
                        y5Var.u(sb, new g3(v3Var, w2Var));
                        printWriter.print(sb);
                        printWriter.flush();
                        Integer num = y5Var.f235a.H;
                        numValueOf = Integer.valueOf(num == null ? 0 : num.intValue());
                        break;
                    }
                    if (j5Var != null && j5Var.n) {
                        h0.b(4, m5VarS, "Printing version info for '%s' as requested.", y5Var.f235a.v(" "));
                        t2 t2Var = w2Var.g;
                        Object[] objArr = new Object[0];
                        for (String str : y5Var.f235a.D()) {
                            t2Var.getClass();
                            printWriter.println(new s2(t2Var, y5.g(str, objArr), y5Var.g));
                        }
                        printWriter.flush();
                        Integer num2 = y5Var.f235a.I;
                        numValueOf = Integer.valueOf(num2 == null ? 0 : num2.intValue());
                        break;
                    }
                    Boolean bool = y5Var.f235a.y;
                    if (bool == null) {
                        bool = Boolean.FALSE;
                    }
                    if (bool.booleanValue()) {
                        String strV = y5Var.f235a.v(" ");
                        if (y5Var.f == null) {
                            y5Var.n(new PrintWriter((Writer) new BufferedWriter(new OutputStreamWriter(System.err, y5.c(System.getProperty("sun.stderr.encoding")))), true));
                        }
                        y5Var.f235a.s.a();
                        y5Var.f235a.s.a();
                        h0.b(4, m5VarS, "helpCommand '%s' does not implement IHelpCommandInitializable2 or IHelpCommandInitializable...", strV);
                        h0.b(4, m5VarS, "Executing helpCommand '%s'...", strV);
                        ArrayList arrayList2 = new ArrayList();
                        m5 m5VarS2 = y5.s();
                        Object objA = y5Var.f235a.s.a();
                        if (objA instanceof Callable) {
                            try {
                                h0.b(4, m5VarS2, "Invoking Callable::call on user object %s@%s...", objA.getClass().getName(), Integer.toHexString(objA.hashCode()));
                                Object objCall = ((Callable) objA).call();
                                y5Var.d = objCall;
                                arrayList2.add(objCall);
                            } catch (g5 e) {
                                throw e;
                            } catch (n2 e2) {
                                throw e2;
                            } catch (Exception e3) {
                                c.j("Error while calling command (", objA, e3, y5Var);
                                return;
                            }
                        } else if (objA instanceof Runnable) {
                            try {
                                h0.b(4, m5VarS2, "Invoking Runnable::run on user object %s@%s...", objA.getClass().getName(), Integer.toHexString(objA.hashCode()));
                                ((Runnable) objA).run();
                                arrayList2.add(null);
                            } catch (g5 e4) {
                                throw e4;
                            } catch (n2 e5) {
                                throw e5;
                            } catch (Exception e6) {
                                c.j("Error while running command (", objA, e6, y5Var);
                                return;
                            }
                        } else {
                            if (!(objA instanceof Method)) {
                                if (!new u3(y5Var.f235a.f203a).isEmpty()) {
                                    throw new g5(y5Var, "Missing required subcommand");
                                }
                                throw new n2("Parsed command (" + objA + ") is not a Method, Runnable or Callable");
                            }
                            try {
                                Method method = (Method) objA;
                                Object[] objArrM = y5Var.f235a.m();
                                if (Modifier.isStatic(method.getModifiers())) {
                                    h0.b(4, m5VarS2, "Invoking static method %s with parameters %s", method, Arrays.toString(objArrM));
                                    objInvoke = method.invoke(null, objArrM);
                                } else {
                                    v3 v3Var2 = y5Var.f235a.u;
                                    if (v3Var2 != null) {
                                        objB = v3Var2.s.a();
                                    } else {
                                        j2 j2Var = y5Var.c;
                                        Class<?> declaringClass = method.getDeclaringClass();
                                        j2Var.getClass();
                                        objB = j2.b(declaringClass);
                                    }
                                    h0.b(4, m5VarS2, "Invoking method %s on %s@%s with parameters %s", method, objB.getClass().getName(), Integer.toHexString(objB.hashCode()), Arrays.toString(objArrM));
                                    objInvoke = method.invoke(objB, objArrM);
                                }
                                y5Var.d = objInvoke;
                                arrayList2.add(objInvoke);
                            } catch (InvocationTargetException e7) {
                                Throwable targetException = e7.getTargetException();
                                if (targetException instanceof g5) {
                                    throw ((g5) targetException);
                                }
                                if (targetException instanceof n2) {
                                    throw ((n2) targetException);
                                }
                                c.j("Error while calling command (", objA, targetException, y5Var);
                                return;
                            } catch (Exception e8) {
                                c.j("Unhandled error while calling command (", objA, e8, y5Var);
                                return;
                            }
                        }
                        int size2 = arrayList2.size();
                        int i2 = 0;
                        int i3 = 0;
                        while (i3 < size2) {
                            Object obj2 = arrayList2.get(i3);
                            i3++;
                            if ((obj2 instanceof Integer) && (((iIntValue = ((Integer) obj2).intValue()) > 0 && iIntValue > i2) || (iIntValue < i2 && i2 <= 0))) {
                                i2 = iIntValue;
                            }
                        }
                        numValueOf = Integer.valueOf(i2);
                        break;
                    }
                }
            } catch (Throwable unused) {
                zB = false;
            }
            CliCommand.Parsed parsed = CliCommand.parse(strArr, zB);
            boolean z = parsed.root().json;
            n5 result = parsed.parseResult();
            m5 m5Var = y5.h;
            result.getClass();
            arrayList = new ArrayList();
            result.a(arrayList);
            m5VarS = y5.s();
            size = arrayList.size();
            i = 0;
            if (numValueOf != null) {
                return;
            }
            if (!parsed.isNotAction()) {
                try {
                    a(l7.k(strArr), z);
                    return;
                } catch (Throwable th) {
                    String strH = wa.h(-10290741640197L, strArr2);
                    StringBuilder sb2 = new StringBuilder(wa.h(-10372346018821L, strArr2));
                    String message = th.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = th.getClass().getSimpleName();
                    }
                    sb2.append(message);
                    a(f.c(3, strH, sb2.toString()), z);
                    return;
                }
            }
            f fVarC = f.c(2, wa.h(-10264971836421L, strArr2), parsed.missingSubcommandMessage());
            if (z) {
                a(fVarC, true);
                return;
            }
            System.err.println((String) fVarC.d);
            System.err.println();
            y5 y5VarCommandLine = parsed.commandLine();
            PrintStream printStream = System.err;
            w2 w2Var2 = y5VarCommandLine.g;
            StringBuilder sb3 = new StringBuilder();
            v3 v3Var3 = y5VarCommandLine.f235a;
            y4 y4Var2 = v3Var3.q;
            if (y4Var2.f234a == null) {
                y4Var2.f234a = new k2();
            }
            y5VarCommandLine.u(sb3, new g3(v3Var3, w2Var2));
            printStream.print(sb3);
            printStream.flush();
            System.exit(fVarC.f51a);
        } catch (g5 e9) {
            a(f.c(2, wa.h(-10239202032645L, strArr2), e9.getMessage()), zContains);
        }
    }
}
