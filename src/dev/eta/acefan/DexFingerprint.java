package dev.eta.acefan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded, read-only DEX evidence for the supported OEM policy. No code is executed.
 * Pool indices/debug offsets can change between APKs; opcodes, registers, constants,
 * branches and resolved member references must not. Unknown instructions fail closed.
 * This is a deliberately strict code profile, not a general DEX decompiler.
 */
public final class DexFingerprint {
    private static final int MAX_DEX = 32 * 1024 * 1024;
    private static final int MAX_TOTAL = 96 * 1024 * 1024;
    private final byte[] data;
    private final String[] strings, types, protos;
    private final int fieldCount, fieldOffset, methodCount, methodOffset;
    private final Map<String, String> aliases;

    private DexFingerprint(byte[] data, Map<String, String> aliases) throws IOException {
        this.data = data;
        this.aliases = aliases;
        if (data.length < 112 || data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n'
                || u32(32) != data.length || u32(36) != 112 || u32(40) != 0x12345678)
            throw new IOException("unsupported DEX header");
        int sc = table(56, 4), so = u32(60);
        strings = new String[sc];
        for (int i = 0; i < sc; i++) {
            int[] at = {u32(so + 4 * i)};
            uleb(at); // UTF-16 size; payload below is decoded deterministically for fingerprinting.
            int start = at[0], end = start;
            while (end < data.length && data[end] != 0 && end - start <= 65536) end++;
            if (end >= data.length || end - start > 65536) throw new IOException("DEX string bounds");
            strings[i] = new String(data, start, end - start, StandardCharsets.UTF_8);
        }
        int tc = table(64, 4), to = u32(68);
        types = new String[tc];
        for (int i = 0; i < tc; i++) types[i] = string(u32(to + 4 * i));
        int pc = table(72, 12), po = u32(76);
        protos = new String[pc];
        for (int i = 0; i < pc; i++) {
            int p = po + i * 12, args = u32(p + 8);
            StringBuilder s = new StringBuilder("(");
            if (args != 0) {
                int n = u32(args);
                bounds(args + 4, (long) n * 2);
                for (int j = 0; j < n; j++) s.append(type(u16(args + 4 + j * 2)));
            }
            protos[i] = s.append(')').append(type(u32(p + 4))).toString();
        }
        fieldCount = table(80, 8); fieldOffset = u32(84);
        methodCount = table(88, 8); methodOffset = u32(92);
    }

    /**
     * Semantic evidence for the same methods, collected in a second bounded pass over the DEX.
     * Kept separate from instruction hashing so a recompile can change one without the other.
     */
    public static Map<String, MethodEvidence> readEvidence(String path, Set<String> wanted,
                                                           Map<String, String> aliases) throws Exception {
        Map<String, MethodEvidence> result = new HashMap<String, MethodEvidence>();
        int total = 0;
        try (ZipFile zip = new ZipFile(path)) {
            for (int i = 1; i <= 16; i++) {
                ZipEntry entry = zip.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (entry == null) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (bytes.size() + n > MAX_DEX || total > MAX_TOTAL) throw new IOException("DEX budget exceeded");
                        bytes.write(buf, 0, n);
                    }
                }
                new DexFingerprint(bytes.toByteArray(), aliases).collectEvidence(wanted, result);
            }
        }
        if (!result.keySet().containsAll(wanted)) {
            Set<String> missing = new HashSet<String>(wanted); missing.removeAll(result.keySet());
            throw new IOException("DEX methods missing: " + missing);
        }
        return result;
    }

    private void collectEvidence(Set<String> wanted, Map<String, MethodEvidence> result) throws Exception {
        int count = table(96, 32), offset = u32(100);
        for (int i = 0; i < count; i++) {
            int cd = u32(offset + i * 32 + 24);
            if (cd == 0) continue;
            int[] at = {cd};
            int sf = uleb(at), inf = uleb(at), dm = uleb(at), vm = uleb(at);
            if ((long) sf + inf + dm + vm > 200000) throw new IOException("class data budget");
            for (int n = 0; n < sf + inf; n++) { uleb(at); uleb(at); }
            evidenceMethods(at, dm, wanted, result); evidenceMethods(at, vm, wanted, result);
        }
    }

    private void evidenceMethods(int[] at, int count, Set<String> wanted, Map<String, MethodEvidence> result) throws Exception {
        int index = 0;
        for (int i = 0; i < count; i++) {
            index = Math.addExact(index, uleb(at)); uleb(at); int code = uleb(at);
            String key = method(index);
            if (!wanted.contains(key) || code == 0) continue;
            result.put(key, evidenceOf(code));
        }
    }

    public static Map<String, String> readApk(String path, Set<String> wanted,
                                             Map<String, String> aliases) throws Exception {
        Map<String, String> result = new HashMap<String, String>();
        int total = 0;
        try (ZipFile zip = new ZipFile(path)) {
            for (int i = 1; i <= 16; i++) {
                ZipEntry entry = zip.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (entry == null) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (bytes.size() + n > MAX_DEX || total > MAX_TOTAL) throw new IOException("DEX budget exceeded");
                        bytes.write(buf, 0, n);
                    }
                }
                new DexFingerprint(bytes.toByteArray(), aliases).collect(wanted, result);
            }
        }
        if (!result.keySet().equals(wanted)) {
            Set<String> missing = new HashSet<String>(wanted); missing.removeAll(result.keySet());
            throw new IOException("DEX methods missing: " + missing);
        }
        return result;
    }

    private void collect(Set<String> wanted, Map<String, String> result) throws Exception {
        int count = table(96, 32), offset = u32(100);
        for (int i = 0; i < count; i++) {
            int cd = u32(offset + i * 32 + 24);
            if (cd == 0) continue;
            int[] at = {cd};
            int sf = uleb(at), inf = uleb(at), dm = uleb(at), vm = uleb(at);
            if ((long) sf + inf + dm + vm > 200000) throw new IOException("class data budget");
            for (int n = 0; n < sf + inf; n++) { uleb(at); uleb(at); }
            methods(at, dm, wanted, result); methods(at, vm, wanted, result);
        }
    }

    private void methods(int[] at, int count, Set<String> wanted, Map<String, String> result) throws Exception {
        int index = 0;
        for (int i = 0; i < count; i++) {
            index = Math.addExact(index, uleb(at)); uleb(at); int code = uleb(at);
            String key = method(index);
            if (!wanted.contains(key)) continue;
            if (code == 0 || result.containsKey(key)) throw new IOException("missing/duplicate method body: " + key);
            result.put(key, fingerprint(code));
        }
    }

    /**
     * Semantic evidence: the members this method references and the strings it loads.
     * Independent of instruction encoding, so it survives recompilation and register changes.
     */
    MethodEvidence evidenceOf(int code) throws Exception {
        bounds(code, 16);
        int units = u32(code + 12), start = code + 16;
        if (units > 100000) throw new IOException("method budget");
        bounds(start, (long) units * 2);
        java.util.TreeSet<String> fields = new java.util.TreeSet<String>();
        java.util.TreeSet<String> calls = new java.util.TreeSet<String>();
        java.util.TreeSet<String> strings = new java.util.TreeSet<String>();
        for (int pc = 0; pc < units;) {
            int first = u16(start + 2 * pc), op = first & 255;
            int width = width(op);
            if (op == 0 && first != 0) {
                if (first == 0x0100) width = 4 + 2 * u16(start + 2 * (pc + 1));
                else if (first == 0x0200) width = 2 + 4 * u16(start + 2 * (pc + 1));
                else if (first == 0x0300) {
                    long size = (long) u16(start + 2 * (pc + 1)) * (u32(start + 2 * (pc + 2)) & 0xffffffffL);
                    if (size > 200000) throw new IOException("payload budget");
                    width = 4 + (int) ((size + 1) / 2);
                } else width = 1;
                pc += width; continue;
            }
            if (pc + width > units) throw new IOException("instruction bounds");
            if (op == 0x1a) strings.add(normalize("string:" + string(u16(start + 2 * (pc + 1)))));
            else if (op == 0x1b) strings.add(normalize("string:" + string(u32(start + 2 * (pc + 1)))));
            else if (op >= 0x52 && op <= 0x6d) fields.add(normalize("field:" + field(u16(start + 2 * (pc + 1)))));
            else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78))
                calls.add(normalize("method:" + method(u16(start + 2 * (pc + 1)))));
            pc += width;
        }
        return new MethodEvidence(join(fields), join(calls), join(strings));
    }

    private static String join(java.util.TreeSet<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (b.length() > 0) b.append(", ");
            b.append(v);
        }
        return b.toString();
    }

    private String fingerprint(int code) throws Exception {
        bounds(code, 16);
        int tries = u16(code + 6);
        int units = u32(code + 12), start = code + 16;
        if (units > 100000) throw new IOException("method budget");
        bounds(start, (long) units * 2);
        StringBuilder b = new StringBuilder();
        b.append(u16(code)).append('/').append(u16(code + 2)).append('/').append(u16(code + 4)).append('/').append(units).append(';');
        for (int pc = 0; pc < units;) {
            int first = u16(start + 2 * pc), op = first & 255, width = width(op);
            if (op == 0 && first != 0) {
                if (first == 0x0100) width = 4 + 2 * u16(start + 2 * (pc + 1));
                else if (first == 0x0200) width = 2 + 4 * u16(start + 2 * (pc + 1));
                else if (first == 0x0300) {
                    long size = (long) u16(start + 2 * (pc + 1)) * (u32(start + 2 * (pc + 2)) & 0xffffffffL);
                    if (size > 200000) throw new IOException("payload budget");
                    width = 4 + (int) ((size + 1) / 2);
                } else throw new IOException("unknown payload");
            }
            if (pc + width > units) throw new IOException("instruction bounds");
            String ref = null; int refUnits = 1;
            if (op == 0x1a || op == 0x1b) {
                refUnits = op == 0x1b ? 2 : 1;
                ref = "string:" + string(op == 0x1b ? u32(start + 2 * (pc + 1)) : u16(start + 2 * (pc + 1)));
            } else if (op == 0x1c || op == 0x1f || op == 0x20 || op == 0x22 || op == 0x23 || op == 0x24 || op == 0x25) {
                ref = "type:" + type(u16(start + 2 * (pc + 1)));
            } else if (op >= 0x52 && op <= 0x6d) {
                ref = "field:" + field(u16(start + 2 * (pc + 1)));
            } else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                ref = "method:" + method(u16(start + 2 * (pc + 1)));
            }
            b.append('[').append(first).append(':');
            for (int j = 1; j < width; j++) {
                if (ref != null && j == 1) {
                    String canonical = normalize(ref);
                    b.append(canonical.length()).append(':').append(canonical).append(',');
                    j += refUnits - 1;
                } else b.append(u16(start + 2 * (pc + j))).append(',');
            }
            b.append(']'); pc += width;
        }
        if (tries > 0) {
            int items = start + units * 2 + (units % 2) * 2;
            bounds(items, (long) tries * 8);
            int handlers = items + tries * 8;
            int[] at = {handlers}; int count = uleb(at);
            if (count > 65535) throw new IOException("exception budget");
            Map<Integer,Integer> indices = new HashMap<Integer,Integer>();
            StringBuilder catches = new StringBuilder();
            for (int i = 0; i < count; i++) {
                indices.put(at[0] - handlers, i);
                int size = sleb(at);
                if (size == Integer.MIN_VALUE || Math.abs(size) > 65535) throw new IOException("exception size");
                catches.append('{').append(size).append(':');
                for (int j = 0; j < Math.abs(size); j++) {
                    String t = normalize("type:" + type(uleb(at)));
                    catches.append(t.length()).append(':').append(t).append('@').append(uleb(at)).append(',');
                }
                if (size <= 0) catches.append("all@").append(uleb(at));
                catches.append('}');
            }
            b.append("tries:").append(tries);
            for (int i = 0; i < tries; i++) {
                int p = items + i * 8; Integer handler = indices.get(u16(p + 6));
                if (handler == null) throw new IOException("invalid exception handler");
                b.append('[').append(u32(p)).append(',').append(u16(p + 4)).append(',').append(handler).append(']');
            }
            b.append(catches);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte x : digest) hex.append(String.format(java.util.Locale.US, "%02x", x & 255));
        return hex.toString();
    }

    private String normalize(String value) {
        // Exact full-symbol aliases first, then class descriptors. No fuzzy substring member names.
        for (Map.Entry<String, String> e : aliases.entrySet())
            if (value.equals("method:" + e.getKey()) || value.equals("field:" + e.getKey()))
                return value.substring(0, value.indexOf(':') + 1) + e.getValue();
        for (Map.Entry<String, String> e : aliases.entrySet())
            if (e.getKey().startsWith("L") && e.getKey().endsWith(";") && e.getKey().indexOf("->") < 0)
                value = value.replace(e.getKey(), e.getValue());
        return value;
    }

    private static int width(int op) throws IOException {
        if (op == 0x02 || op == 0x05 || op == 0x08 || op == 0x13 || op == 0x15 || op == 0x16 || op == 0x19
                || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20 || op == 0x22 || op == 0x23 || op == 0x29
                || (op >= 0x2d && op <= 0x3d) || (op >= 0x44 && op <= 0x6d)
                || (op >= 0x90 && op <= 0xaf) || (op >= 0xd0 && op <= 0xe2)) return 2;
        if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17 || op == 0x1b
                || (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c)
                || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) return 3;
        if (op == 0x18) return 5;
        if ((op >= 0x3e && op <= 0x43) || op == 0x73 || op == 0x79 || op == 0x7a || op >= 0xe3)
            throw new IOException("instruction outside profile: " + op);
        return 1;
    }

    private String method(int i) throws IOException {
        if (i < 0 || i >= methodCount) throw new IOException("method index");
        int p = methodOffset + 8 * i, proto = u16(p + 2);
        if (proto >= protos.length) throw new IOException("proto index");
        return type(u16(p)) + "->" + string(u32(p + 4)) + protos[proto];
    }
    private String field(int i) throws IOException {
        if (i < 0 || i >= fieldCount) throw new IOException("field index");
        int p = fieldOffset + 8 * i;
        return type(u16(p)) + "->" + string(u32(p + 4)) + ':' + type(u16(p + 2));
    }
    private String string(int i) throws IOException { if (i < 0 || i >= strings.length) throw new IOException("string index"); return strings[i]; }
    private String type(int i) throws IOException { if (i < 0 || i >= types.length) throw new IOException("type index"); return types[i]; }
    private int table(int header, int item) throws IOException {
        int n = u32(header), off = u32(header + 4);
        if (n < 0 || n > 200000) throw new IOException("table budget");
        bounds(off, (long) n * item); return n;
    }
    private void bounds(int at, long length) throws IOException {
        if (at < 0 || length < 0 || at + length > data.length) throw new IOException("DEX bounds");
    }
    private int u16(int at) throws IOException { bounds(at, 2); return (data[at] & 255) | ((data[at + 1] & 255) << 8); }
    private int u32(int at) throws IOException { return u16(at) | (u16(at + 2) << 16); }
    private int sleb(int[] at) throws IOException {
        int out = 0, shift = 0, b;
        do {
            bounds(at[0], 1); b = data[at[0]++] & 255;
            if (shift >= 35) throw new IOException("SLEB overflow");
            out |= (b & 127) << shift; shift += 7;
        } while ((b & 128) != 0);
        if (shift < 32 && (b & 64) != 0) out |= -1 << shift;
        return out;
    }
    private int uleb(int[] at) throws IOException {
        int out = 0;
        for (int i = 0; i < 5; i++) {
            bounds(at[0], 1); int b = data[at[0]++] & 255;
            if (i == 4 && (b & 0xf8) != 0) throw new IOException("ULEB overflow");
            out |= (b & 127) << (7 * i);
            if ((b & 128) == 0) return out;
        }
        throw new IOException("ULEB bounds");
    }
}
