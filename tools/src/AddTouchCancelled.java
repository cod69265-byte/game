import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/** Injects touchCancelled(IIII)Z (return false) into com/soco/SocoMain.class so the
 *  legacy 1.1.0-compiled InputProcessor implementation satisfies the 1.14.2 interface. */
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
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean added = false;
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (name.equals("touchCancelled") && desc.equals("(IIII)Z")) { added = true; }
                return super.visitMethod(access, name, desc, sig, ex);
            }
            @Override public void visitEnd() {
                if (!added) {
                    MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "touchCancelled", "(IIII)Z", null, null);
                    mv.visitCode();
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    mv.visitMaxs(1, 5);
                    mv.visitEnd();
                    System.out.println("injected touchCancelled into com/soco/SocoMain");
                } else {
                    System.out.println("touchCancelled already present");
                }
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
        System.out.println("written " + out + " entries=" + entries.size());
    }
}