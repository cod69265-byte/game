import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Instruments {@code com.soco.util.libgdx.ResourceManager} so a stalled or failing asset
 * load can neither freeze the game forever nor stay invisible.
 *
 * <p>Original code:
 * <pre>
 *   public static boolean update () {
 *       try { return assetManager.update(); }
 *       catch (Exception e) { Log.error(e); return false; }
 *   }
 *   public static void waitLoadFinsh () {
 *       while (!update()) { try { Thread.sleep(1L); } catch (InterruptedException e) { e.printStackTrace(); } }
 *   }
 * </pre>
 *
 * <p>Why the game hangs on the iPhone while the music keeps playing: when one asset fails,
 * libGDX 1.14.2's {@code AssetManager.handleTaskError} has no error listener installed, so
 * it rethrows. The descriptor stays in {@code loadQueue}, {@code update()} returns false,
 * and the next iteration retries the same broken asset. The loop in
 * {@code waitLoadFinsh} has no deadline, so the GL thread never returns. Worse,
 * {@code Log.error} prints only {@code exception.getMessage()}, which is null for a
 * {@code GdxRuntimeException(Throwable)} wrapper, so the device console shows nothing at
 * all - exactly what the logs showed: 153 {@code ResourceManager add:} lines then silence.
 *
 * <p>Two edits, both in {@code ResourceManager}:
 * <ol>
 *   <li>{@code update()}'s catch block logs {@code String.valueOf(e)} (class name survives a
 *       null message) instead of relying on the game's message-only logger.</li>
 *   <li>{@code waitLoadFinsh()} gains a deadline; on expiry it logs
 *       {@code AssetManager.getDiagnostics()} (which names every queued/loaded asset) and
 *       returns, so the game continues in a degraded state instead of freezing.</li>
 * </ol>
 *
 * <p>Usage: {@code java PatchResourceManager <in.jar> <out.jar>}
 */
public final class PatchResourceManager {

    /** How long a single waitLoadFinsh() call may spin before giving up. */
    private static final long TIMEOUT_MS = 30000L;

    private static final String TARGET = "com/soco/util/libgdx/ResourceManager";
    private static final String ASSET_MANAGER = "com/badlogic/gdx/assets/AssetManager";
    private static final String LOG = "com/soco/util/libgdx/Log";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PatchResourceManager <in.jar> <out.jar>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);

        // Collect the hierarchy first so COMPUTE_FRAMES can resolve common supertypes
        // without loading classes (these RoboVM-oriented jars are not loadable here).
        final Map<String, String> supers = new HashMap<String, String>();
        ZipFile zf = new ZipFile(in);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (!ze.getName().endsWith(".class")) {
                    continue;
                }
                ClassReader cr = new ClassReader(readAll(zf.getInputStream(ze)));
                supers.put(cr.getClassName(), cr.getSuperName());
            }
        } finally {
            zf.close();
        }

        int patched = 0;
        ZipFile zin = new ZipFile(in);
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out));
        try {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                byte[] data = readAll(zin.getInputStream(ze));
                if (ze.getName().equals(TARGET + ".class")) {
                    data = patch(data, supers);
                    patched++;
                }
                ZipEntry copy = new ZipEntry(ze.getName());
                copy.setTime(ze.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        } finally {
            zin.close();
            zout.close();
        }
        System.out.println("PatchResourceManager patched=" + patched + " out=" + out.length() + "B");
    }

    private static byte[] patch(byte[] data, final Map<String, String> supers) {
        ClassReader cr = new ClassReader(data);
        final ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                if (a.equals(b)) {
                    return a;
                }
                String cur = a;
                for (int i = 0; i < 64 && cur != null; i++) {
                    if (cur.equals(b)) {
                        return a;
                    }
                    cur = supers.get(cur);
                }
                cur = b;
                for (int i = 0; i < 64 && cur != null; i++) {
                    if (cur.equals(a)) {
                        return b;
                    }
                    cur = supers.get(cur);
                }
                return "java/lang/Object";
            }
        };
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private boolean replacedWait;
            private boolean replacedUpdate;

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] exceptions) {
                if ("waitLoadFinsh".equals(name) && "()V".equals(desc)) {
                    replacedWait = true;
                    return null; // drop the original body
                }
                if ("update".equals(name) && "()Z".equals(desc)) {
                    replacedUpdate = true;
                    return null; // drop the original body
                }
                return super.visitMethod(access, name, desc, sig, exceptions);
            }

            @Override
            public void visitEnd() {
                if (replacedWait) {
                    MethodVisitor mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "waitLoadFinsh", "()V", null, null);
                    mv.visitCode();
                    emitWaitLoadFinsh(mv);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }
                if (replacedUpdate) {
                    MethodVisitor mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "update", "()Z", null, null);
                    mv.visitCode();
                    emitUpdate(mv);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     * <pre>
     *   public static boolean update () {
     *       try { return assetManager.update(); }
     *       catch (Exception e) {
     *           Log.warning("[dsh] update failed: " + String.valueOf(e));
     *           return false;
     *       }
     *   }
     * </pre>
     */
    private static void emitUpdate(MethodVisitor v) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();

        v.visitTryCatchBlock(start, end, handler, "java/lang/Exception");
        v.visitLabel(start);
        v.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "assetManager", "L" + ASSET_MANAGER + ";");
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER, "update", "()Z", false);
        v.visitLabel(end);
        v.visitInsn(Opcodes.IRETURN);

        v.visitLabel(handler);
        // stack: exception
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        v.visitLdcInsn("[dsh] update failed: ");
        v.visitInsn(Opcodes.SWAP);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, LOG, "warning", "(Ljava/lang/String;)V", false);
        v.visitInsn(Opcodes.ICONST_0);
        v.visitInsn(Opcodes.IRETURN);
    }

    /**
     * <pre>
     *   public static void waitLoadFinsh () {
     *       long deadline = System.currentTimeMillis() + TIMEOUT_MS;
     *       while (!update()) {
     *           if (System.currentTimeMillis() > deadline) {
     *               Log.warning("[dsh] waitLoadFinsh TIMEOUT " + assetManager.getDiagnostics());
     *               return;
     *           }
     *           try { Thread.sleep(1L); } catch (InterruptedException e) { e.printStackTrace(); }
     *       }
     *   }
     * </pre>
     */
    private static void emitWaitLoadFinsh(MethodVisitor v) {
        Label loop = new Label();
        Label wait = new Label();
        Label done = new Label();
        Label sleepStart = new Label();
        Label sleepEnd = new Label();
        Label sleepHandler = new Label();

        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis",
                "()J", false);
        v.visitLdcInsn(Long.valueOf(TIMEOUT_MS));
        v.visitInsn(Opcodes.LADD);
        v.visitVarInsn(Opcodes.LSTORE, 0);

        v.visitLabel(loop);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, "update", "()Z", false);
        v.visitJumpInsn(Opcodes.IFNE, done);

        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis",
                "()J", false);
        v.visitVarInsn(Opcodes.LLOAD, 0);
        v.visitInsn(Opcodes.LCMP);
        v.visitJumpInsn(Opcodes.IFLE, wait);

        // Log.warning("[dsh] waitLoadFinsh TIMEOUT " + assetManager.getDiagnostics());
        v.visitLdcInsn("[dsh] waitLoadFinsh TIMEOUT ");
        v.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "assetManager", "L" + ASSET_MANAGER + ";");
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER, "getDiagnostics",
                "()Ljava/lang/String;", false);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, LOG, "warning", "(Ljava/lang/String;)V", false);
        v.visitInsn(Opcodes.RETURN);

        v.visitLabel(wait);
        v.visitTryCatchBlock(sleepStart, sleepEnd, sleepHandler, "java/lang/InterruptedException");
        v.visitLabel(sleepStart);
        v.visitLdcInsn(Long.valueOf(1L));
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
        v.visitLabel(sleepEnd);
        v.visitJumpInsn(Opcodes.GOTO, loop);

        v.visitLabel(sleepHandler);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/InterruptedException",
                "printStackTrace", "()V", false);
        v.visitJumpInsn(Opcodes.GOTO, loop);

        v.visitLabel(done);
        v.visitInsn(Opcodes.RETURN);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}
