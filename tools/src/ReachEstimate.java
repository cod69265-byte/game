package tools;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Estimates how many classes RoboVM's AOT compiler will compile, without
 * running RoboVM.
 *
 * Why this exists
 * ---------------
 * A full AOT build takes 60-90 minutes on a 3-vCPU macos-15 runner. Using CI as
 * the only feedback loop for "is the force-link set too big?" costs an hour per
 * guess. This walks the same class-reachability graph RoboVM's tree shaker walks
 * (AppCompiler.getRootClasses -> DependencyGraph) locally, in seconds.
 *
 * What it models
 * --------------
 * RoboVM starts from the main class plus every forceLinkClasses match, then for
 * each reachable class follows: superclass, interfaces, declared field types,
 * method parameter/return/exception types, and every type appearing in a method
 * body (NEW, CHECKCAST, INSTANCEOF, ANEWARRAY, MULTIANEWARRAY, LDC class
 * constants, field and method owners, catch types).
 *
 * What it does NOT model
 * ----------------------
 *   - Generic signature erasure is handled loosely: type arguments are followed,
 *     which over-approximates slightly.
 *   - Method-level tree shaking. This counts classes, so it is an upper bound on
 *     the class count and says nothing about the method count.
 *   - The ObjC plugin's synthetic roots. Not needed here: every @CustomClass in
 *     this project is reachable from the launcher.
 *
 * Usage
 * -----
 *   java ReachEstimate <mainClass> [--force pkg.**]... <jar> [jar]...
 */
public final class ReachEstimate {

    private static final Map<String, byte[]> BYTES = new HashMap<>();
    private static final Map<String, Set<String>> EDGES = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ReachEstimate <mainClass> [--force pat]... <jar>...");
            System.exit(2);
        }

        String mainClass = args[0].replace('.', '/');
        List<String> patterns = new ArrayList<>();
        List<File> jars = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if ("--force".equals(args[i])) {
                patterns.add(args[++i].trim());
            } else {
                jars.add(new File(args[i]));
            }
        }

        for (File jar : jars) {
            load(jar);
        }
        System.out.printf("scanned %d classes from %d jar(s)%n", BYTES.size(), jars.size());

        // ---- roots, mirroring AppCompiler.getRootClasses ----
        Set<String> roots = new LinkedHashSet<>();
        int forced = 0;
        Map<String, Integer> perPattern = new TreeMap<>();

        for (String pattern : patterns) {
            String prefix = pattern.endsWith("**")
                    ? pattern.substring(0, pattern.length() - 2).replace('.', '/')
                    : pattern.replace('.', '/');
            int hits = 0;
            for (String name : BYTES.keySet()) {
                if (name.startsWith(prefix) && roots.add(name)) {
                    forced++;
                    hits++;
                }
            }
            perPattern.put(pattern, hits);
        }
        roots.add(mainClass);

        System.out.println();
        System.out.println("force-link patterns:");
        if (perPattern.isEmpty()) {
            System.out.println("  (none)");
        }
        for (Map.Entry<String, Integer> e : perPattern.entrySet()) {
            System.out.printf("  %-42s %5d classes%n", e.getKey(), e.getValue());
        }
        System.out.printf("  %-42s %5d%n", "forced roots total", forced);

        // ---- closure ----
        Set<String> reached = new LinkedHashSet<>(roots);
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            for (String dep : edges(name)) {
                if (BYTES.containsKey(dep) && reached.add(dep)) {
                    queue.add(dep);
                }
            }
        }

        System.out.println();
        System.out.printf("main class        %s  (%s)%n", mainClass,
                BYTES.containsKey(mainClass) ? "found" : "MISSING");
        System.out.printf("reachable classes %d of %d scanned (%.1f%%)%n",
                reached.size(), BYTES.size(), 100.0 * reached.size() / BYTES.size());
        System.out.printf("  forced roots        : %d%n", forced);
        System.out.printf("  reached by analysis : %d%n", reached.size() - forced);

        Map<String, Integer> pkg = new TreeMap<>();
        for (String name : reached) {
            pkg.merge(packageOf(name), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("reached, by package (top 30):");
        pkg.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(30)
                .forEach(e -> System.out.printf("  %-40s %5d%n", e.getKey(), e.getValue()));

        System.out.println();
        System.out.printf("AOT class count estimate: %d%n", reached.size());
        // Measured on macos-15 (3 vCPU): dominated by one clang invocation per
        // generated LLVM module, so wall-clock scales with class count.
        System.out.printf("wall-clock at 3 vCPU (1.2-1.8 s/class): %.0f-%.0f min%n",
                reached.size() * 1.2 / 60.0, reached.size() * 1.8 / 60.0);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "(default)" : internalName.substring(0, slash);
    }

    private static void load(File jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(zf.entries())) {
                String n = entry.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(entry)) {
                    BYTES.put(n.substring(0, n.length() - 6), in.readAllBytes());
                }
            }
        }
    }

    private static Set<String> edges(String internalName) {
        Set<String> cached = EDGES.get(internalName);
        if (cached != null) {
            return cached;
        }

        final Set<String> out = new HashSet<>();
        EDGES.put(internalName, out);

        byte[] bytes = BYTES.get(internalName);
        if (bytes == null) {
            return out;
        }

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                if (superName != null) {
                    out.add(superName);
                }
                if (interfaces != null) {
                    Collections.addAll(out, interfaces);
                }
                collect(out, signature);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                collect(out, descriptor);
                collect(out, signature);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                collect(out, descriptor);
                collect(out, signature);
                if (exceptions != null) {
                    Collections.addAll(out, exceptions);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (type.startsWith("[")) {
                            collect(out, type);
                        } else {
                            out.add(type);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String desc) {
                        out.add(owner);
                        collect(out, desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n,
                                                String desc, boolean itf) {
                        out.add(owner);
                        collect(out, desc);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type) {
                            Type t = (Type) value;
                            if (t.getSort() == Type.OBJECT) {
                                out.add(t.getInternalName());
                            } else if (t.getSort() == Type.ARRAY) {
                                collect(out, t.getDescriptor());
                            }
                        }
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int dims) {
                        collect(out, descriptor);
                    }

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler,
                                                   String type) {
                        if (type != null) {
                            out.add(type);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        return out;
    }

    /**
     * Adds every object type named in a field/method descriptor or a generic
     * signature. Signatures use the same L...; form for classes, plus T...; for
     * type variables (skipped) and <...> for type arguments (descended into).
     */
    private static void collect(Set<String> out, String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 'L') {
                int end = s.indexOf(';', i);
                if (end < 0) {
                    return;
                }
                String body = s.substring(i + 1, end);
                int lt = body.indexOf('<');
                if (lt >= 0) {
                    String raw = body.substring(0, lt);
                    if (!raw.isEmpty()) {
                        out.add(raw);
                    }
                    collect(out, body.substring(lt));
                } else {
                    out.add(body);
                }
                i = end + 1;
            } else if (c == 'T') {
                int end = s.indexOf(';', i);
                i = end < 0 ? s.length() : end + 1;
            } else {
                i++;
            }
        }
    }

    private ReachEstimate() {
    }
}
