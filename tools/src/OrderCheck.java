import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Proves the {@code Gdx.app not available} fix is real, statically.
 *
 * <h2>The bug</h2>
 * libGDX 1.14.2 {@code IOSApplication.Delegate.didFinishLaunching} calls
 * {@code createApplication()} <em>before</em> {@code IOSApplication} assigns
 * {@code Gdx.app}. Anything a launcher constructs inside {@code createApplication()}
 * therefore runs with {@code Gdx.app == null}. {@code V3Main} extends
 * {@code SocoMain}, whose constructor builds a {@code GestureDetector}, whose
 * {@code Timer.Task} constructor throws:
 * <pre>
 * java.lang.IllegalStateException: Gdx.app not available.
 * </pre>
 *
 * <h2>What this tool asserts</h2>
 * <ol>
 *   <li>{@code IOSLauncher26} and {@code IOSLauncher26Scene} do <b>not</b> construct
 *       {@code V3Main}/{@code SocoMain} anywhere inside {@code createApplication()}.
 *       They must pass a {@code LazyGameListener} instead.</li>
 *   <li>{@code LazyGameListener}'s constructor does not reference {@code V3Main},
 *       {@code SocoMain} or any {@code com/badlogic/gdx/Gdx} field — so building it
 *       before {@code Gdx.app} exists is safe.</li>
 *   <li>{@code LazyGameListener} <em>does</em> construct {@code V3Main} in some other
 *       method (the lazy accessor), proving the work is deferred and not dropped.</li>
 *   <li>{@code IOSPlatForm.<init>} does not touch {@code Gdx} either, because the
 *       launchers build it eagerly.</li>
 * </ol>
 *
 * <p>Usage: {@code java OrderCheck <classesDirOrJar>}. Last line is
 * {@code ORDER CHECK PASSED} or {@code ORDER CHECK FAILED (n)}.
 */
public final class OrderCheck {

    private static final String V3MAIN = "com/soco/V3Main";
    private static final String SOCOMAIN = "com/soco/SocoMain";
    private static final String GDX = "com/badlogic/gdx/Gdx";

    private static final String LAUNCHER_LEGACY =
            "com/soco/veggies3_4399/ios26/IOSLauncher26";
    private static final String LAUNCHER_SCENE =
            "com/soco/veggies3_4399/ios26/scene/IOSLauncher26Scene";
    private static final String LAZY =
            "com/soco/veggies3_4399/ios26/LazyGameListener";
    private static final String PLATFORM =
            "com/soco/veggies3_4399/IOSPlatForm";

    private final List<String> failures = new ArrayList<String>();
    private final List<String> notes = new ArrayList<String>();

    /** class name -> method name -> referenced owners. */
    private static final java.util.Map<String, java.util.Map<String, Set<String>>> REFS =
            new java.util.HashMap<String, java.util.Map<String, Set<String>>>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: OrderCheck <classesDirOrJar> [more...]");
            System.exit(2);
        }
        OrderCheck check = new OrderCheck();
        check.run(args);
        for (String n : check.notes) System.out.println("  ok   " + n);
        for (String f : check.failures) System.out.println("  FAIL " + f);
        if (check.failures.isEmpty()) {
            System.out.println("ORDER CHECK PASSED");
        } else {
            System.out.println("ORDER CHECK FAILED (" + check.failures.size() + ")");
            System.exit(1);
        }
    }

    private void run(String[] roots) throws Exception {
        List<byte[]> classes = new ArrayList<byte[]>();
        for (String r : roots) {
            File root = new File(r);
            if (!root.exists()) {
                failures.add("path not found: " + r);
                continue;
            }
            if (root.isDirectory()) {
                collectDir(root, classes);
            } else {
                collectJar(root, classes);
            }
        }
        for (byte[] b : classes) index(b);

        // 1. launchers must not construct V3Main/SocoMain in createApplication
        for (String launcher : new String[] {LAUNCHER_LEGACY, LAUNCHER_SCENE}) {
            java.util.Map<String, Set<String>> methods = REFS.get(launcher);
            if (methods == null) {
                failures.add(launcher + " not found");
                continue;
            }
            Set<String> inCreate = methods.get("createApplication");
            if (inCreate == null) {
                failures.add(launcher + " has no createApplication");
                continue;
            }
            if (inCreate.contains(V3MAIN) || inCreate.contains(SOCOMAIN)) {
                failures.add(launcher + ".createApplication still constructs V3Main/SocoMain "
                        + "(runs before Gdx.app is assigned)");
            } else {
                notes.add(launcher + ".createApplication does not construct V3Main/SocoMain");
            }
            if (!inCreate.contains(LAZY)) {
                failures.add(launcher + ".createApplication does not reference LazyGameListener");
            } else {
                notes.add(launcher + ".createApplication passes LazyGameListener");
            }
        }

        // 2. LazyGameListener.<init> must be Gdx-free
        java.util.Map<String, Set<String>> lazy = REFS.get(LAZY);
        if (lazy == null) {
            failures.add(LAZY + " not found");
        } else {
            Set<String> ctor = lazy.get("<init>");
            if (ctor == null) {
                failures.add(LAZY + " has no constructor");
            } else {
                boolean bad = ctor.contains(V3MAIN) || ctor.contains(SOCOMAIN) || ctor.contains(GDX);
                if (bad) {
                    failures.add(LAZY + ".<init> touches V3Main/SocoMain/Gdx and would still throw");
                } else {
                    notes.add(LAZY + ".<init> is Gdx-free");
                }
            }
            // 3. the deferral must actually happen somewhere
            boolean deferred = false;
            for (Set<String> refs : lazy.values()) {
                if (refs.contains(V3MAIN)) { deferred = true; break; }
            }
            if (deferred) {
                notes.add(LAZY + " constructs V3Main lazily outside its constructor");
            } else {
                failures.add(LAZY + " never constructs V3Main; the game would never start");
            }
        }

        // 4. IOSPlatForm.<init> must be Gdx-free too
        java.util.Map<String, Set<String>> plat = REFS.get(PLATFORM);
        if (plat == null) {
            failures.add(PLATFORM + " not found");
        } else {
            Set<String> ctor = plat.get("<init>");
            if (ctor != null && ctor.contains(GDX)) {
                failures.add(PLATFORM + ".<init> touches Gdx (built eagerly in createApplication)");
            } else {
                notes.add(PLATFORM + ".<init> is Gdx-free");
            }
        }
    }

    private void index(byte[] bytes) {
        final Set<String>[] current = new Set[] {null};
        ClassReader cr = new ClassReader(bytes);
        final String[] owner = new String[1];
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String sig,
                              String superName, String[] itfs) {
                owner[0] = name;
                REFS.put(name, new java.util.HashMap<String, Set<String>>());
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                final Set<String> refs = new LinkedHashSet<String>();
                REFS.get(owner[0]).put(name, refs);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int op, String type) {
                        if (op == Opcodes.NEW) refs.add(strip(type));
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        refs.add(strip(o));
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String n, String d) {
                        refs.add(strip(o));
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof org.objectweb.asm.Type) {
                            String s = ((org.objectweb.asm.Type) value).getInternalName();
                            refs.add(strip(s));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (bsm != null && bsm.getOwner() != null) refs.add(strip(bsm.getOwner()));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static String strip(String t) {
        if (t == null) return "";
        if (t.startsWith("[")) return t;
        int i = t.indexOf('$');
        return i < 0 ? t : t.substring(0, i);
    }

    private static void collectDir(File dir, List<byte[]> out) throws Exception {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectDir(k, out);
            else if (k.getName().endsWith(".class")) out.add(readAll(k));
        }
    }

    private static void collectJar(File jar, List<byte[]> out) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".class")) {
                    java.io.InputStream in = zf.getInputStream(e);
                    try { out.add(readAll(in)); } finally { in.close(); }
                }
            }
        } finally {
            zf.close();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}