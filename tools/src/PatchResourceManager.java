import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
 * Instruments {@code com.soco.util.libgdx.ResourceManager} so a missing or not-yet-loaded
 * asset can never freeze the game.
 *
 * <p>Three independent defects are repaired, all found from an on-device thread dump
 * ({@code [dsh] dump N}) plus 637 {@code Asset not loaded} exceptions:
 *
 * <ol>
 *   <li><b>Getters rethrow.</b> The game already guards {@code getMusic}:
 *       <pre>
 *   public static Music getMusic (String name) {
 *       name = AudioUtil.getKey(name);
 *       try { return (Music) assetManager.get(name); }
 *       catch (Exception e) { return null; }
 *   }
 *       </pre>
 *       but {@code getSound}, {@code getTexture}, {@code getBitmapFont}, {@code getParticle}
 *       and {@code getTextureAtlas} have no such guard, so {@code AssetManager.get} throwing
 *       {@code GdxRuntimeException("Asset not loaded: ...")} escapes straight into
 *       {@code GameManager.logic()} -> {@code SocoMain.render()} -> {@code IOSGraphics.draw()}.
 *       The frame never finishes, so the picture freezes while the audio thread keeps playing.
 *       All three {@code getSound} call sites in {@code AudioUtil} already test the result for
 *       null, so returning null is the semantics the game expects.</li>
 *
 *   <li><b>unload() cancels a pending load.</b> {@code add(name)} only calls
 *       {@code assetManager.load(name, ...)}, which queues the asset; {@code update()} is what
 *       actually loads it. If {@code unload(name)} runs in between, the reference count drops
 *       to zero and {@code AssetManager.unload} removes the descriptor from {@code loadQueue},
 *       so the asset is never loaded and every later {@code get} fails. On the device this
 *       happened for {@code Audio/Sound/gamebegin.wav}: {@code add} at 10:40:39.598, {@code
 *       unload} at 10:40:47.554, then permanent failure. The guard below skips the unload while
 *       {@code isLoaded(name)} is still false, letting the queued load finish.</li>
 *
 *   <li><b>waitLoadFinsh() has no deadline.</b> libGDX 1.14.2's {@code handleTaskError}
 *       rethrows when no error listener is installed, so {@code update()} would spin forever.
 *       A 30 s deadline plus {@code getDiagnostics()} logging turns a hang into a degraded
 *       but playable state.</li>
 * </ol>
 *
 * <p>Usage: {@code java PatchResourceManager <in.jar> <out.jar>}
 */
public final class PatchResourceManager {

    /** How long a single waitLoadFinsh() call may spin before giving up. */
    private static final long TIMEOUT_MS = 30000L;

    private static final String TARGET = "com/soco/util/libgdx/ResourceManager";
    private static final String ASSET_MANAGER = "com/badlogic/gdx/assets/AssetManager";
    private static final String AUDIO_UTIL = "com/soco/util/libgdx/AudioUtil";
    private static final String LOG = "com/soco/util/libgdx/Log";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";

    /**
     * name, descriptor, return internal name, whether the original starts with
     * {@code name = AudioUtil.getKey(name)}.
     */
    private static final String[][] GETTERS = {
            {"getSound", "(Ljava/lang/String;)Lcom/badlogic/gdx/audio/Sound;",
                    "com/badlogic/gdx/audio/Sound", "1"},
            {"getTexture", "(Ljava/lang/String;)Lcom/badlogic/gdx/graphics/Texture;",
                    "com/badlogic/gdx/graphics/Texture", "0"},
            {"getBitmapFont", "(Ljava/lang/String;)Lcom/badlogic/gdx/graphics/g2d/BitmapFont;",
                    "com/badlogic/gdx/graphics/g2d/BitmapFont", "0"},
            {"getParticle", "(Ljava/lang/String;)Lcom/badlogic/gdx/graphics/g2d/ParticleEffect;",
                    "com/badlogic/gdx/graphics/g2d/ParticleEffect", "0"},
            {"getTextureAtlas", "(Ljava/lang/String;)Lcom/badlogic/gdx/graphics/g2d/TextureAtlas;",
                    "com/badlogic/gdx/graphics/g2d/TextureAtlas", "0"},
    };

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
            private final java.util.List<String[]> replacedGetters = new java.util.ArrayList<String[]>();

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
                for (String[] g : GETTERS) {
                    if (g[0].equals(name) && g[1].equals(desc)) {
                        replacedGetters.add(g);
                        return null; // drop the original body, re-emitted in visitEnd
                    }
                }
                // unload(String) / clear(String): keep the body, but guard the unload call.
                final MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if (!"unload".equals(name) && !"clear".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String mDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && ASSET_MANAGER.equals(owner)
                                && "unload".equals(mName)
                                && "(Ljava/lang/String;)V".equals(mDesc)) {
                            // stack on entry: [assetManager, name]
                            //   dup2 / isLoaded -> [am, name, Z]
                            //   ifeq skip          -> [am, name]
                            //   unload             -> []
                            //   goto after
                            // skip: pop2           -> []
                            Label skip = new Label();
                            Label after = new Label();
                            super.visitInsn(Opcodes.DUP2);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER,
                                    "isLoaded", "(Ljava/lang/String;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, skip);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER,
                                    "unload", "(Ljava/lang/String;)V", false);
                            super.visitJumpInsn(Opcodes.GOTO, after);
                            super.visitLabel(skip);
                            super.visitInsn(Opcodes.POP2);
                            super.visitLabel(after);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                    }
                };
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
                for (String[] g : replacedGetters) {
                    MethodVisitor mv = cw.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            g[0], g[1], null, null);
                    mv.visitCode();
                    emitGetter(mv, g[0], g[2], "1".equals(g[3]));
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }
                // Fail loudly: a silently unmatched descriptor would leave the original
                // rethrowing getter in place and the game would still freeze.
                if (replacedGetters.size() != GETTERS.length) {
                    throw new IllegalStateException("expected " + GETTERS.length
                            + " getters to patch, matched " + replacedGetters.size());
                }
                if (!replacedUpdate || !replacedWait) {
                    throw new IllegalStateException("missing update/waitLoadFinsh: update="
                            + replacedUpdate + " waitLoadFinsh=" + replacedWait);
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

    /**
     * <pre>
     *   public static T getX (String name) {
     *       try {
     *           [name = AudioUtil.getKey(name);]
     *           return (T) assetManager.get(name[, T.class]);
     *       } catch (Throwable t) {
     *           Log.warning("[dsh] asset get failed: " + name + " -> " + t);
     *           return null;
     *       }
     *   }
     * </pre>
     */
    private static void emitGetter(MethodVisitor v, String methodName, String retType,
                                   boolean useAudioKey) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();

        v.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        v.visitLabel(start);
        if (useAudioKey) {
            v.visitVarInsn(Opcodes.ALOAD, 0);
            v.visitMethodInsn(Opcodes.INVOKESTATIC, AUDIO_UTIL, "getKey",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
            v.visitVarInsn(Opcodes.ASTORE, 0);
        }
        v.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "assetManager", "L" + ASSET_MANAGER + ";");
        v.visitVarInsn(Opcodes.ALOAD, 0);
        if ("getSound".equals(methodName)) {
            v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER, "get",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
        } else {
            v.visitLdcInsn(Type.getObjectType(retType));
            v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ASSET_MANAGER, "get",
                    "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        }
        v.visitTypeInsn(Opcodes.CHECKCAST, retType);
        v.visitLabel(end);
        v.visitInsn(Opcodes.ARETURN);

        v.visitLabel(handler);
        // stack: Throwable
        v.visitVarInsn(Opcodes.ASTORE, 1);
        v.visitTypeInsn(Opcodes.NEW, STRING_BUILDER);
        v.visitInsn(Opcodes.DUP);
        v.visitLdcInsn("[dsh] asset get failed: ");
        v.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>",
                "(Ljava/lang/String;)V", false);
        v.visitVarInsn(Opcodes.ALOAD, 0);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        v.visitLdcInsn(" -> ");
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        v.visitVarInsn(Opcodes.ALOAD, 1);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString",
                "()Ljava/lang/String;", false);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, LOG, "warning", "(Ljava/lang/String;)V", false);
        v.visitInsn(Opcodes.ACONST_NULL);
        v.visitInsn(Opcodes.ARETURN);
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
