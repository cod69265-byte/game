import org.objectweb.asm.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 给 libGDX 1.14.2 注入 1.1.0 兼容桥接。
 *
 * <p>游戏字节码按 libGDX 1.1.0 编译，1.14.2 移除了若干 API，直接链接会失败。
 * 本工具把这些成员补回 libGDX 侧，游戏 jar 保持原样不动。
 *
 * <p>用法：{@code java InjectShim <in.jar> <out.jar>}
 */
public final class InjectShim {

    private static final String TEXTBOUNDS = "com/badlogic/gdx/graphics/g2d/BitmapFont$TextBounds";
    private static final String GLYPHLAYOUT = "com/badlogic/gdx/graphics/g2d/GlyphLayout";
    private static final String BITMAPFONT = "com/badlogic/gdx/graphics/g2d/BitmapFont";
    private static final String BITMAPFONTDATA = "com/badlogic/gdx/graphics/g2d/BitmapFont$BitmapFontData";
    private static final String BATCH = "com/badlogic/gdx/graphics/g2d/Batch";
    private static final String TEXREGION = "com/badlogic/gdx/graphics/g2d/TextureRegion";
    private static final String TEXTURE = "com/badlogic/gdx/graphics/Texture";
    private static final String GLTEXTURE = "com/badlogic/gdx/graphics/GLTexture";
    private static final String GLFRAMEBUFFER = "com/badlogic/gdx/graphics/glutils/GLFrameBuffer";
    private static final String ARRAY = "com/badlogic/gdx/utils/Array";
    private static final String PARTICLEEMITTER = "com/badlogic/gdx/graphics/g2d/ParticleEmitter";
    private static final String SCALEDNUM = "com/badlogic/gdx/graphics/g2d/ParticleEmitter$ScaledNumericValue";
    private static final String SPRITE = "com/badlogic/gdx/graphics/g2d/Sprite";
    private static final String POOLEFFECT = "com/badlogic/gdx/graphics/g2d/ParticleEffectPool$PooledEffect";
    private static final String POOL = "com/badlogic/gdx/utils/Pool";
    private static final String FRAMEBUFFER = "com/badlogic/gdx/graphics/glutils/FrameBuffer";
    private static final String INPUT = "com/badlogic/gdx/Input";
    private static final String TEXTINPUTLISTENER = "com/badlogic/gdx/Input$TextInputListener";
    private static final String KEYS = "com/badlogic/gdx/Input$Keys";
    private static final String PIXMAPPACKER = "com/badlogic/gdx/graphics/g2d/PixmapPacker";
    private static final String PIXMAPPACKERRECT = "com/badlogic/gdx/graphics/g2d/PixmapPacker$PixmapPackerRectangle";
    private static final String RECTANGLE = "com/badlogic/gdx/math/Rectangle";
    private static final String PIXMAP = "com/badlogic/gdx/graphics/Pixmap";
    private static final String COLOR = "com/badlogic/gdx/graphics/Color";
    private static final String FREETYPE = "com/badlogic/gdx/graphics/g2d/freetype/FreeType";
    private static final String FTFACE = "com/badlogic/gdx/graphics/g2d/freetype/FreeType$Face";
    private static final String FTGLYPHSLOT = "com/badlogic/gdx/graphics/g2d/freetype/FreeType$GlyphSlot";
    private static final String FTBITMAP = "com/badlogic/gdx/graphics/g2d/freetype/FreeType$Bitmap";
    private static final String FTFONTDATA =
            "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator$FreeTypeBitmapFontData";
    private static final String FTFONTGEN = "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator";
    private static final String FTFONTPARAM =
            "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator$FreeTypeFontParameter";
    private static final String OS = "com/badlogic/gdx/utils/Os";
    private static final String SHAREDLIB = "com/badlogic/gdx/utils/SharedLibraryLoader";

    private static int injected;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: InjectShim <in.jar> <out.jar>");
            System.exit(2);
        }
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        ZipFile zin = new ZipFile(args[0]);
        try {
            Enumeration<? extends ZipEntry> e = zin.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                entries.put(ze.getName(), readAll(zin.getInputStream(ze)));
            }
        } finally {
            zin.close();
        }

        entries.put(TEXTBOUNDS + ".class", makeTextBounds());
        // 1.14.2 把 SharedLibraryLoader 与 Os 拆到了独立模块，但 UIUtils / FreeType 的
        // 静态初始化还引用它们。iOS 上静态库是直接链接进可执行文件的，不需要
        // System.loadLibrary，所以这两个类只需要能被解析且语义安全即可。
        entries.put(OS + ".class", makeOs());
        entries.put(SHAREDLIB + ".class", makeSharedLibraryLoader());
        SafeClassWriter.register(entries);

        patch(entries, BITMAPFONT, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                addField(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "xChars", "[C", null);
                addField(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "capChars", "[C", null);

                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC,
                        "setScale", "(F)V");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, BITMAPFONT, "data", "L" + BITMAPFONTDATA + ";");
                    m.visitVarInsn(Opcodes.FLOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BITMAPFONTDATA, "setScale", "(F)V", false);
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(2, 2);
                    m.visitEnd();
                }

                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getRegions", "()[L" + TEXREGION + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, BITMAPFONT, "regions", "L" + ARRAY + ";");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY, "toArray", "()[Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, "[L" + TEXREGION + ";");
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(2, 1);
                    m.visitEnd();
                }

                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getBounds",
                        "(Ljava/lang/CharSequence;)L" + TEXTBOUNDS + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitTypeInsn(Opcodes.NEW, TEXTBOUNDS);
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, TEXTBOUNDS, "<init>", "()V", false);
                    m.visitVarInsn(Opcodes.ASTORE, 2);
                    m.visitTypeInsn(Opcodes.NEW, GLYPHLAYOUT);
                    m.visitInsn(Opcodes.DUP);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, GLYPHLAYOUT, "<init>",
                            "(L" + BITMAPFONT + ";Ljava/lang/CharSequence;)V", false);
                    m.visitVarInsn(Opcodes.ASTORE, 3);
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitFieldInsn(Opcodes.GETFIELD, GLYPHLAYOUT, "width", "F");
                    m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "width", "F");
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitFieldInsn(Opcodes.GETFIELD, GLYPHLAYOUT, "height", "F");
                    m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "height", "F");
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(4, 4);
                    m.visitEnd();
                }

                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "draw",
                        "(L" + BATCH + ";Ljava/lang/CharSequence;FF)L" + TEXTBOUNDS + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitVarInsn(Opcodes.FLOAD, 3);
                    m.visitVarInsn(Opcodes.FLOAD, 4);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BITMAPFONT, "draw",
                            "(L" + BATCH + ";Ljava/lang/CharSequence;FF)L" + GLYPHLAYOUT + ";", false);
                    m.visitVarInsn(Opcodes.ASTORE, 5);
                    m.visitTypeInsn(Opcodes.NEW, TEXTBOUNDS);
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, TEXTBOUNDS, "<init>", "()V", false);
                    m.visitVarInsn(Opcodes.ASTORE, 6);
                    m.visitVarInsn(Opcodes.ALOAD, 6);
                    m.visitVarInsn(Opcodes.ALOAD, 5);
                    m.visitFieldInsn(Opcodes.GETFIELD, GLYPHLAYOUT, "width", "F");
                    m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "width", "F");
                    m.visitVarInsn(Opcodes.ALOAD, 6);
                    m.visitVarInsn(Opcodes.ALOAD, 5);
                    m.visitFieldInsn(Opcodes.GETFIELD, GLYPHLAYOUT, "height", "F");
                    m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "height", "F");
                    m.visitVarInsn(Opcodes.ALOAD, 6);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(6, 7);
                    m.visitEnd();
                }
            }
        });

        patch(entries, PARTICLEEMITTER, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getScale",
                        "()L" + SCALEDNUM + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLEEMITTER, "getXScale",
                            "()L" + SCALEDNUM + ";", false);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(1, 1);
                    m.visitEnd();
                }

                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getSprite", "()L" + SPRITE + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLEEMITTER, "getSprites",
                            "()L" + ARRAY + ";", false);
                    m.visitVarInsn(Opcodes.ASTORE, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitFieldInsn(Opcodes.GETFIELD, ARRAY, "size", "I");
                    Label empty = new Label();
                    m.visitJumpInsn(Opcodes.IFLE, empty);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY, "get", "(I)Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, SPRITE);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitLabel(empty);
                    m.visitInsn(Opcodes.ACONST_NULL);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(2, 2);
                    m.visitEnd();
                }
            }
        });

        patch(entries, "com/badlogic/gdx/graphics/g2d/ParticleEffectPool", new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "obtain",
                        "()L" + POOLEFFECT + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POOL, "obtain",
                            "()Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, POOLEFFECT);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(1, 1);
                    m.visitEnd();
                }
            }
        });

        patch(entries, FRAMEBUFFER, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getColorBufferTexture",
                        "()L" + TEXTURE + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, GLFRAMEBUFFER, "getColorBufferTexture",
                            "()Lcom/badlogic/gdx/graphics/GLTexture;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, TEXTURE);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(1, 1);
                    m.visitEnd();
                }
            }
        });

        patch(entries, GLTEXTURE, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing,
                        Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC, "createGLHandle", "()I");
                if (m != null) {
                    m.visitCode();
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "com/badlogic/gdx/utils/BufferUtils", "newIntBuffer",
                            "(I)Ljava/nio/IntBuffer;", false);
                    m.visitVarInsn(Opcodes.ASTORE, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer", "position",
                            "(I)Ljava/nio/Buffer;", false);
                    m.visitInsn(Opcodes.POP);
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/badlogic/gdx/Gdx", "gl",
                            "()Lcom/badlogic/gdx/graphics/GL20;", false);
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/badlogic/gdx/graphics/GL20",
                            "glGenTextures", "(ILjava/nio/IntBuffer;)V", true);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer", "get", "(I)I", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(3, 1);
                    m.visitEnd();
                }
            }
        });

        patch(entries, INPUT, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "setCatchBackKey", "(Z)V");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETSTATIC, KEYS, "BACK", "I");
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "setCatchKey", "(IZ)V", true);
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(3, 2);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "setCatchMenuKey", "(Z)V");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETSTATIC, KEYS, "MENU", "I");
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "setCatchKey", "(IZ)V", true);
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(3, 2);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getTextInput",
                        "(L" + TEXTINPUTLISTENER + ";Ljava/lang/String;Ljava/lang/String;)V");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitInsn(Opcodes.ACONST_NULL);
                    m.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "getTextInput",
                            "(L" + TEXTINPUTLISTENER + ";Ljava/lang/String;Ljava/lang/String;"
                                    + "Ljava/lang/String;)V", true);
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(5, 4);
                    m.visitEnd();
                }
            }
        });

        patch(entries, PIXMAPPACKER, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                // 1.14.2 返回 PixmapPackerRectangle（不再继承 Rectangle）；
                // 1.1.0 返回 Rectangle。加一个协变桥方法，按字节码允许的同名不同返回类型。
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "pack",
                        "(Ljava/lang/String;L" + PIXMAP + ";)L" + RECTANGLE + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PIXMAPPACKER, "pack",
                            "(Ljava/lang/String;L" + PIXMAP + ";)L" + PIXMAPPACKERRECT + ";", false);
                    m.visitVarInsn(Opcodes.ASTORE, 3);
                    m.visitTypeInsn(Opcodes.NEW, RECTANGLE);
                    m.visitInsn(Opcodes.DUP);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PIXMAPPACKERRECT, "getX", "()I", false);
                    m.visitInsn(Opcodes.I2F);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PIXMAPPACKERRECT, "getY", "()I", false);
                    m.visitInsn(Opcodes.I2F);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PIXMAPPACKERRECT, "getWidth", "()I", false);
                    m.visitInsn(Opcodes.I2F);
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PIXMAPPACKERRECT, "getHeight", "()I", false);
                    m.visitInsn(Opcodes.I2F);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, RECTANGLE, "<init>", "(FFFF)V", false);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(7, 4);
                    m.visitEnd();
                }
            }
        });

        patch(entries, FREETYPE, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                // 1.1.0 是静态工具方法；1.14.2 改成实例方法挂在 Face/GlyphSlot 上。
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "setPixelSizes", "(L" + FTFACE + ";II)Z");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitVarInsn(Opcodes.ILOAD, 2);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTFACE, "setPixelSizes", "(II)Z", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(3, 3);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "loadChar", "(L" + FTFACE + ";II)Z");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitVarInsn(Opcodes.ILOAD, 2);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTFACE, "loadChar", "(II)Z", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(3, 3);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "getCharIndex", "(L" + FTFACE + ";I)I");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTFACE, "getCharIndex", "(I)I", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(2, 2);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "getKerning", "(L" + FTFACE + ";III)I");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitVarInsn(Opcodes.ILOAD, 2);
                    m.visitVarInsn(Opcodes.ILOAD, 3);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTFACE, "getKerning", "(III)I", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(4, 4);
                    m.visitEnd();
                }
                m = addMethod(cv, existing, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "renderGlyph", "(L" + FTGLYPHSLOT + ";I)Z");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTGLYPHSLOT, "renderGlyph", "(I)Z", false);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitMaxs(2, 2);
                    m.visitEnd();
                }
            }
        });

        patch(entries, FTBITMAP, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getPixmap",
                        "(L" + PIXMAP + "$Format;)L" + PIXMAP + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitTypeInsn(Opcodes.NEW, COLOR);
                    m.visitInsn(Opcodes.DUP);
                    m.visitInsn(Opcodes.FCONST_1);
                    m.visitInsn(Opcodes.FCONST_1);
                    m.visitInsn(Opcodes.FCONST_1);
                    m.visitInsn(Opcodes.FCONST_1);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, COLOR, "<init>", "(FFFF)V", false);
                    m.visitInsn(Opcodes.FCONST_1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTBITMAP, "getPixmap",
                            "(L" + PIXMAP + "$Format;L" + COLOR + ";F)L" + PIXMAP + ";", false);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(7, 2);
                    m.visitEnd();
                }
            }
        });

        patch(entries, FTFONTDATA, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                addField(cv, existing, Opcodes.ACC_PUBLIC, "spaceWidth", "F", null);
                // 1.1.0 的 FreeTypeBitmapFontData 直接暴露 TextureRegion[]；1.14.2 同名字段
                // 改成了 Array<TextureRegion>。JVM 允许同名字段共存（按 name+descriptor 区分），
                // 所以补一个数组形态的隐藏字段供旧字节码读写。
                addField(cv, existing, Opcodes.ACC_PUBLIC, "regions", "[L" + TEXREGION + ";", null);
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "getTextureRegions",
                        "()[L" + TEXREGION + ";");
                if (m != null) {
                    Label done = new Label();
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, FTFONTDATA, "regions", "[L" + TEXREGION + ";");
                    m.visitVarInsn(Opcodes.ASTORE, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitJumpInsn(Opcodes.IFNONNULL, done);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, FTFONTDATA, "regions", "L" + ARRAY + ";");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY, "toArray", "()[Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, "[L" + TEXREGION + ";");
                    m.visitVarInsn(Opcodes.ASTORE, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitFieldInsn(Opcodes.PUTFIELD, FTFONTDATA, "regions", "[L" + TEXREGION + ";");
                    m.visitLabel(done);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(2, 2);
                    m.visitEnd();
                }
            }
        });

        patch(entries, FTFONTGEN, new Patcher() {
            public void apply(ClassVisitor cv, Set<String> existing) {
                addField(cv, existing, Opcodes.ACC_FINAL, "filePath", "Ljava/lang/String;", null);
                // 1.1.0 的便捷重载，1.14.2 只留了 FreeTypeFontParameter 版本。
                MethodVisitor m = addMethod(cv, existing, Opcodes.ACC_PUBLIC, "generateData",
                        "(ILjava/lang/String;Z)L" + FTFONTDATA + ";");
                if (m != null) {
                    m.visitCode();
                    m.visitTypeInsn(Opcodes.NEW, FTFONTPARAM);
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, FTFONTPARAM, "<init>", "()V", false);
                    m.visitVarInsn(Opcodes.ASTORE, 4);
                    m.visitVarInsn(Opcodes.ALOAD, 4);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitFieldInsn(Opcodes.PUTFIELD, FTFONTPARAM, "size", "I");
                    m.visitVarInsn(Opcodes.ALOAD, 4);
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitFieldInsn(Opcodes.PUTFIELD, FTFONTPARAM, "characters", "Ljava/lang/String;");
                    m.visitVarInsn(Opcodes.ALOAD, 4);
                    m.visitVarInsn(Opcodes.ILOAD, 3);
                    m.visitFieldInsn(Opcodes.PUTFIELD, FTFONTPARAM, "flip", "Z");
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 4);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FTFONTGEN, "generateData",
                            "(L" + FTFONTPARAM + ";)" + "L" + FTFONTDATA + ";", false);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(2, 5);
                    m.visitEnd();
                }
            }
        });

        ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(args[1])));
        zout.setLevel(Deflater.BEST_COMPRESSION);
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            ZipEntry ze = new ZipEntry(en.getKey());
            ze.setTime(0L);
            zout.putNextEntry(ze);
            zout.write(en.getValue());
            zout.closeEntry();
        }
        zout.close();
        System.out.println("shim injected=" + injected + " entries=" + entries.size()
                + " -> " + args[1]);
    }

    // ------------------------------------------------------------------ helpers

    private interface Patcher {
        void apply(ClassVisitor cv, Set<String> existing);
    }

    private static void patch(Map<String, byte[]> entries, String internalName, Patcher p) {
        byte[] orig = entries.get(internalName + ".class");
        if (orig == null) {
            System.out.println("skip (absent): " + internalName);
            return;
        }
        ClassReader cr = new ClassReader(orig);
        final Set<String> existing = new HashSet<String>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                existing.add("F:" + n + d);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                existing.add("M:" + n + d);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        ClassWriter cw = new SafeClassWriter(cr);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                p.apply(cv, existing);
                super.visitEnd();
            }
        }, 0);
        entries.put(internalName + ".class", cw.toByteArray());
    }

    private static void addField(ClassVisitor cv, Set<String> existing, int access,
                                 String name, String desc, Object value) {
        // 字段按 name+descriptor 唯一：1.14.2 的 regions 是 Array，旧字节码要的是数组，
        // 两者可以合法共存，所以去重键必须带描述符。
        if (existing.contains("F:" + name + desc)) {
            return;
        }
        FieldVisitor fv = cv.visitField(access, name, desc, null, value);
        fv.visitEnd();
        injected++;
    }

    private static MethodVisitor addMethod(ClassVisitor cv, Set<String> existing,
                                           int access, String name, String desc) {
        if (existing.contains("M:" + name + desc)) {
            return null;
        }
        injected++;
        return cv.visitMethod(access, name, desc, null, null);
    }

    private static byte[] makeTextBounds() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TEXTBOUNDS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC, "width", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "height", "F", null, null).visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();

        m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + TEXTBOUNDS + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, TEXTBOUNDS, "width", "F");
        m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "width", "F");
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, TEXTBOUNDS, "height", "F");
        m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "height", "F");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();

        m = cw.visitMethod(Opcodes.ACC_PUBLIC, "set", "(L" + TEXTBOUNDS + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, TEXTBOUNDS, "width", "F");
        m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "width", "F");
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, TEXTBOUNDS, "height", "F");
        m.visitFieldInsn(Opcodes.PUTFIELD, TEXTBOUNDS, "height", "F");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 重建被 1.14.2 拆走的 {@code com.badlogic.gdx.utils.Os}。
     *
     * <p>只有 UIUtils 的静态初始化读它的常量，按 iOS 取值即可。
     */
    private static byte[] makeOs() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                OS, null, "java/lang/Object", null);

        for (String name : new String[]{"Android", "Windows", "Linux", "MacOsX", "IOS"}) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    name, "L" + OS + ";", null, null).visitEnd();
        }

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();

        m = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        String[] names = {"Android", "Windows", "Linux", "MacOsX", "IOS"};
        for (String name : names) {
            m.visitTypeInsn(Opcodes.NEW, OS);
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, OS, "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, OS, name, "L" + OS + ";");
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 重建被拆走的 {@code SharedLibraryLoader}。
     *
     * <p>RoboVM 把原生库静态链接进可执行文件，运行期没有 .dylib 可加载，
     * 所以 {@code load} 是空操作，{@code os} 固定为 {@link #OS} 的 IOS。
     */
    private static byte[] makeSharedLibraryLoader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                SHAREDLIB, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "os", "L" + OS + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "isWindows",
                "Z", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "isLinux",
                "Z", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "isMacOsX",
                "Z", null, null).visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();

        // load(String) / load(String, ClassLoader) 都退化成空操作。
        for (String desc : new String[]{"(Ljava/lang/String;)V",
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)V"}) {
            m = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", desc, null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1 + (desc.startsWith("(Ljava/lang/String;Ljava") ? 2 : 1));
            m.visitEnd();
        }

        m = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", "(Ljava/lang/String;Ljava/lang/Class;)V",
                null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 3);
        m.visitEnd();

        m = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, OS, "IOS", "L" + OS + ";");
        m.visitFieldInsn(Opcodes.PUTSTATIC, SHAREDLIB, "os", "L" + OS + ";");
        m.visitInsn(Opcodes.ICONST_0);
        m.visitFieldInsn(Opcodes.PUTSTATIC, SHAREDLIB, "isWindows", "Z");
        m.visitInsn(Opcodes.ICONST_0);
        m.visitFieldInsn(Opcodes.PUTSTATIC, SHAREDLIB, "isLinux", "Z");
        m.visitInsn(Opcodes.ICONST_0);
        m.visitFieldInsn(Opcodes.PUTSTATIC, SHAREDLIB, "isMacOsX", "Z");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    private InjectShim() {
    }

    /**
     * COMPUTE_FRAMES 需要判定公共父类。默认实现会 Class.forName，从而把
     * 待注入的 jar 真的加载起来；这里改为查 jar 内的字节码，查不到就退化成
     * java/lang/Object（注入的方法都不依赖精确的公共父类）。
     */
    private static final class SafeClassWriter extends ClassWriter {
        private static final int FRAMES = ClassWriter.COMPUTE_FRAMES;

        private static final Map<String, String> SUPERS = new HashMap<String, String>();
        private static final Set<String> IFACES = new HashSet<String>();

        SafeClassWriter(ClassReader cr) {
            super(cr, FRAMES);
        }

        static void register(Map<String, byte[]> entries) {
            SUPERS.clear();
            IFACES.clear();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                if (!name.endsWith(".class")) {
                    continue;
                }
                try {
                    ClassReader r = new ClassReader(e.getValue());
                    String self = r.getClassName();
                    String sup = r.getSuperName();
                    SUPERS.put(self, sup == null ? "java/lang/Object" : sup);
                    for (String itf : r.getInterfaces()) {
                        IFACES.add(self + "|" + itf);
                    }
                } catch (RuntimeException ignored) {
                    // 坏类不影响注入，跳过
                }
            }
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if (IFACES.contains(type1 + "|" + type2) || IFACES.contains(type2 + "|" + type1)) {
                return "java/lang/Object";
            }
            if (isAssignable(type1, type2)) {
                return type1;
            }
            if (isAssignable(type2, type1)) {
                return type2;
            }
            String a = type1;
            while (a != null) {
                a = SUPERS.get(a);
                if (a != null && isAssignable(a, type2)) {
                    return a;
                }
            }
            return "java/lang/Object";
        }

        private static boolean isAssignable(String from, String to) {
            String c = from;
            for (int guard = 0; c != null && guard < 64; guard++) {
                if (c.equals(to)) {
                    return true;
                }
                c = SUPERS.get(c);
            }
            return false;
        }
    }
}
