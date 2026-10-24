import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/** Injects interface methods added in libGDX 1.14.2 into legacy game classes:
 *  - com/soco/SocoMain.touchCancelled(IIII)Z -> false
 *  - com/soco/SocoMain.pinchStop()V -> empty
 *  Skips any method already present. Usage: AddTouchCancelled &lt;in.jar&gt; &lt;out.jar&gt; */
public class AddTouchCancelled {
    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarInputStream jin = new JarInputStream(new FileInputStream(in))) {
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int r;
                while ((r = jin.read(buf)) > 0) bos.write(buf, 0, r);
                entries.put(e.getName(), bos.toByteArray());
            }
        }
        byte[] cls = entries.get("com/soco/SocoMain.class");
        if (cls == null) { System.err.println("SocoMain.class not found"); System.exit(2); }
        final Set<String> existing = new HashSet<>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                existing.add(n + d); return null;
            }
        }, ClassReader.SKIP_CODE);
        final List<String> injected = new ArrayList<>();
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public void visitEnd() {
                add(cw, injected, existing, "touchCancelled", "(IIII)Z", Opcodes.ICONST_0, Opcodes.IRETURN);
                add(cw, injected, existing, "pinchStop", "()V", -1, Opcodes.RETURN);
                super.visitEnd();
            }
        }, 0);
        entries.put("com/soco/SocoMain.class", cw.toByteArray());
        try (JarOutputStream jout = new JarOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                jout.putNextEntry(new JarEntry(en.getKey()));
                jout.write(en.getValue());
                jout.closeEntry();
            }
        }
        System.out.println("injected: " + injected);
        System.out.println("written " + out + " entries=" + entries.size());
    }

    static void add(ClassVisitor cv, List<String> log, Set<String> existing, String name, String desc, int push, int ret) {
        if (existing.contains(name + desc)) return;
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        if (push >= 0) { mv.visitInsn(push); mv.visitInsn(ret); }
        else mv.visitInsn(ret);
        mv.visitMaxs(push >= 0 ? 1 : 0, push >= 0 ? 5 : 1);
        mv.visitEnd();
        log.add(name + desc);
    }
}
