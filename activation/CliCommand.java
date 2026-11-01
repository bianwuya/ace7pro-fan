package org.lsposed.lspd.cli;

import defpackage.b5;
import defpackage.c2;
import defpackage.f5;
import defpackage.h0;
import defpackage.h5;
import defpackage.i2;
import defpackage.j3;
import defpackage.j5;
import defpackage.m3;
import defpackage.m5;
import defpackage.n5;
import defpackage.o3;
import defpackage.s5;
import defpackage.t2;
import defpackage.u3;
import defpackage.v3;
import defpackage.wa;
import defpackage.y5;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Stack;
import java.util.TreeSet;

/* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
/* JADX INFO: loaded from: classes.dex */
@i2(description = {"Interactively debug LSPosed modules from a computer. Enable developer mode for full function."}, footer = {"", "This CLI is intended for humans and AI agents debugging modules through ADB.", "It is not a stable control protocol. Everything may change without notice.", "Do not build apps or unattended integrations against it."}, mixinStandardHelpOptions = true, name = "lspctl", scope = s5.b, subcommands = {Status.class, Stop.class, Module.class, Scope.class, HookDebug.class}, synopsisSubcommandLabel = "COMMAND", versionProvider = VersionProvider.class)
public final class CliCommand {

    @f5(description = {"Print JSON for the current interactive session."}, names = {"--json"}, scope = s5.b)
    public boolean json;

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public interface Action {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Inspect, configure, or dump hook debugging information."}, name = "hook-debug", subcommands = {HookDebugEnable.class, HookDebugDisable.class, HookDebugDump.class})
    public final class HookDebug {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Disable hook debugging."}, name = "disable")
    public final class HookDebugDisable implements Action {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Dump hook debugging information to standard output."}, name = "dump")
    public final class HookDebugDump implements Action {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Enable hook debugging."}, name = "enable")
    public final class HookDebugEnable implements Action {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Inspect or change Xposed modules."}, name = "module", subcommands = {ModuleList.class, ModuleShow.class, ModuleEnable.class, ModuleDisable.class, ModuleDisableAll.class})
    public final class Module {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Disable an Xposed module for one user."}, name = "disable")
    public final class ModuleDisable implements Action {

        @h5(index = "0", paramLabel = "PACKAGE")
        public String packageName;

        @f5(names = {"--user"}, paramLabel = "ID", required = true)
        public int userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Disable all Xposed modules for one or all users."}, name = "disable-all")
    public final class ModuleDisableAll implements Action {

        @c2(multiplicity = "1")
        public UserSelection selection;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Enable an Xposed module for one user."}, name = "enable")
    public final class ModuleEnable implements Action {

        @h5(index = "0", paramLabel = "PACKAGE")
        public String packageName;

        @f5(names = {"--user"}, paramLabel = "ID", required = true)
        public int userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public final class ModuleFilter {

        @f5(description = {"Show only disabled modules."}, names = {"--disabled"})
        public boolean disabled;

        @f5(description = {"Show only enabled modules."}, names = {"--enabled"})
        public boolean enabled;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"List installed Xposed modules."}, name = "list")
    public final class ModuleList implements Action {

        @c2
        public ModuleFilter filter;

        @f5(description = {"Limit results to one Android user."}, names = {"--user"}, paramLabel = "ID")
        public Integer userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Show details for an Xposed module."}, name = "show")
    public final class ModuleShow implements Action {

        @h5(index = "0", paramLabel = "PACKAGE")
        public String packageName;

        @f5(description = {"Show state for one Android user."}, names = {"--user"}, paramLabel = "ID")
        public Integer userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public final class Parsed extends h0 {
        private final Object command;
        private final y5 commandLine;
        private final n5 parseResult;
        private final CliCommand root;

        public Parsed(CliCommand cliCommand, n5 n5Var, y5 y5Var, Object obj) {
            this.root = cliCommand;
            this.parseResult = n5Var;
            this.commandLine = y5Var;
            this.command = obj;
        }

        public Object command() {
            return this.command;
        }

        public y5 commandLine() {
            return this.commandLine;
        }

        public final boolean equals(Object obj) {
            if (!(obj instanceof Parsed)) {
                return false;
            }
            Parsed parsed = (Parsed) obj;
            return this.root == parsed.root && this.parseResult == parsed.parseResult && this.commandLine == parsed.commandLine && Objects.equals(this.command, parsed.command);
        }

        public final int hashCode() {
            CliCommand cliCommand = this.root;
            n5 n5Var = this.parseResult;
            y5 y5Var = this.commandLine;
            Object obj = this.command;
            return Objects.hashCode(obj) + ((Objects.hashCode(y5Var) + ((Objects.hashCode(n5Var) + (Objects.hashCode(cliCommand) * 31)) * 31)) * 31);
        }

        public boolean helpRequested() {
            for (n5 n5VarB = this.parseResult; n5VarB != null; n5VarB = n5VarB.b()) {
                if (n5VarB.c || n5VarB.d) {
                    return true;
                }
            }
            return false;
        }

        public boolean isDeveloper() {
            return !(this.command instanceof Public);
        }

        public boolean isNotAction() {
            return !(this.command instanceof Action);
        }

        public String missingSubcommandMessage() {
            Object obj = this.command;
            Objects.requireNonNull(obj);
            boolean z = obj instanceof Module;
            String[] strArr = wa.f219a;
            if (z) {
                return wa.h(-2370821946373L, strArr);
            }
            if (obj instanceof Scope) {
                return wa.h(-2482491096069L, strArr);
            }
            if (!(obj instanceof HookDebug)) {
                return wa.h(-2718714297349L, strArr);
            }
            return wa.h(-2589865278469L, strArr);
        }

        public n5 parseResult() {
            return this.parseResult;
        }

        public CliCommand root() {
            return this.root;
        }

        public final String toString() {
            Object[] objArr = {this.root, this.parseResult, this.commandLine, this.command};
            String[] strArrSplit = "root;parseResult;commandLine;command".length() == 0 ? new String[0] : "root;parseResult;commandLine;command".split(";");
            StringBuilder sb = new StringBuilder(Parsed.class.getSimpleName());
            sb.append("[");
            for (int i = 0; i < strArrSplit.length; i++) {
                sb.append(strArrSplit[i]);
                sb.append("=");
                sb.append(objArr[i]);
                if (i != strArrSplit.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public interface Public extends Action {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Inspect or change Xposed module scopes."}, name = "scope", subcommands = {ScopeList.class, ScopeAdd.class, ScopeRemove.class, ScopeSet.class, ScopeClear.class})
    public final class Scope {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Add packages to a module's scope."}, name = "add")
    public final class ScopeAdd implements Action {

        @o3
        public ScopeTargets options = new ScopeTargets();
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Clear a module's scope."}, name = "clear")
    public final class ScopeClear implements Action {

        @h5(index = "0", paramLabel = "MODULE")
        public String modulePackageName;

        @c2(multiplicity = "1")
        public UserSelection selection;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"List a module's scope."}, name = "list")
    public final class ScopeList implements Action {

        @h5(index = "0", paramLabel = "MODULE")
        public String modulePackageName;

        @f5(description = {"Limit results to one Android user."}, names = {"--user"}, paramLabel = "ID")
        public Integer userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Remove packages from a module's scope."}, name = "remove")
    public final class ScopeRemove implements Action {

        @o3
        public ScopeTargets options = new ScopeTargets();
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Replace a module's scope for one user."}, name = "set")
    public final class ScopeSet implements Action {

        @o3
        public ScopeTargets options = new ScopeTargets();
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public final class ScopeTargets {

        @h5(index = "0", paramLabel = "MODULE")
        public String modulePackageName;

        @h5(arity = "1..*", index = "1..*", paramLabel = "TARGET")
        public List targets = new ArrayList();

        @f5(names = {"--user"}, paramLabel = "ID", required = true)
        public int userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Show LSPosed daemon status."}, name = "status")
    public final class Status implements Public {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    @i2(description = {"Stop the LSPosed daemon."}, name = "stop")
    public final class Stop implements Public {
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public final class UserSelection {

        @f5(names = {"--all-users"})
        public boolean allUsers;

        @f5(names = {"--user"}, paramLabel = "ID")
        public Integer userId;
    }

    /* JADX INFO: compiled from: r8-map-id-20841d502da1f5ed2956c355091bf116615120fe4b66bb630f26ef17b9bcb1cc */
    public final class VersionProvider implements j3 {
        @Override // defpackage.j3
        public String[] getVersion() {
            return new String[]{wa.h(-11579231828997L, wa.f219a)};
        }
    }

    public static Parsed parse(String[] strArr, boolean z) throws Throwable {
        CliCommand cliCommand = new CliCommand();
        y5 y5Var = new y5(cliCommand);
        if (!z) {
            removeDeveloperCommands(y5Var);
        }
        y5Var.f235a.o.f137a = false;
        y5Var.q();
        m3 m3Var = y5Var.b;
        y5 y5Var2 = m3Var.g;
        b5.l(strArr, "argument array");
        m5 m5VarS = y5.s();
        if (m5VarS.c()) {
            h0.b(3, m5VarS, "Picocli version: %s", "4.7.7, JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + " " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + "), OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        }
        if (m5VarS.c()) {
            h0.b(3, m5VarS, "Parsing %d command line args %s", Integer.valueOf(strArr.length), Arrays.toString(strArr));
        }
        if (m5VarS.b()) {
            y5Var2.f235a.b.getClass();
            Boolean bool = Boolean.FALSE;
            y5Var2.f235a.f203a.getClass();
            h0.b(4, m5VarS, "Parser configuration: optionsCaseInsensitive=%s, subcommandsCaseInsensitive=%s, %s", bool, bool, m3Var.n());
        }
        if (m5VarS.b()) {
            h0.b(4, m5VarS, "(ANSI is %s by default: systemproperty[picocli.ansi]=%s, isatty=%s, TERM=%s, OSTYPE=%s, isWindows=%s, JansiConsoleInstalled=%s, ANSICON=%s, ConEmuANSI=%s, NO_COLOR=%s, CLICOLOR=%s, CLICOLOR_FORCE=%s)", t2.f187a.a() ? "enabled" : "disabled", System.getProperty("picocli.ansi"), Boolean.valueOf(t2.d()), System.getenv("TERM"), System.getenv("OSTYPE"), Boolean.valueOf(t2.e()), Boolean.valueOf(t2.b()), System.getenv("ANSICON"), System.getenv("ConEmuANSI"), System.getenv("NO_COLOR"), System.getenv("CLICOLOR"), System.getenv("CLICOLOR_FORCE"));
        }
        ArrayList arrayList = new ArrayList();
        for (String str : strArr) {
            m3Var.a(str, arrayList, new LinkedHashSet());
        }
        Stack stack = new Stack();
        Collections.reverse(arrayList);
        stack.addAll(arrayList);
        m3Var.D(new ArrayList(), stack, strArr, new ArrayList(), new HashSet(), new LinkedHashSet());
        j5 j5Var = m3Var.f;
        n5 n5Var = j5Var == null ? null : new n5(j5Var);
        n5 n5VarB = n5Var;
        while (n5VarB.b() != null) {
            n5VarB = n5VarB.b();
        }
        v3 v3Var = n5VarB.f131a;
        return new Parsed(cliCommand, n5Var, v3Var.t, v3Var.s.a());
    }

    private static void removeDeveloperCommands(y5 y5Var) {
        y5Var.getClass();
        ArrayList arrayList = new ArrayList(new u3(y5Var.f235a.f203a).f196a.values());
        int size = arrayList.size();
        int i = 0;
        while (i < size) {
            Object obj = arrayList.get(i);
            i++;
            y5 y5Var2 = (y5) obj;
            removeDeveloperCommands(y5Var2);
            if (!(y5Var2.f235a.s.a() instanceof Public)) {
                v3 v3Var = y5Var.f235a;
                String strU = y5Var2.f235a.u();
                u3 u3Var = v3Var.f203a;
                v3Var.o.getClass();
                TreeSet treeSet = new TreeSet();
                y5 y5Var3 = (y5) u3Var.f196a.remove(strU);
                if (y5Var3 != null) {
                    treeSet.add(strU);
                    u3Var.remove(y5Var3.f235a.u());
                    treeSet.add(y5Var3.f235a.u());
                    for (String str : y5Var3.f235a.j()) {
                        u3Var.remove(str);
                        treeSet.add(str);
                    }
                }
                m5 m5VarS = y5.s();
                if (m5VarS.b()) {
                    h0.b(4, m5VarS, "Removed %d subcommand entries %s for key '%s' from '%s'", Integer.valueOf(treeSet.size()), treeSet, strU, v3Var.v(" "));
                }
            }
        }
    }
}
