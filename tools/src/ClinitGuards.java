import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 找出在 &lt;clinit&gt; 里直接调用 Platform 方法的类。
 *
 * StaticInitOrder 只查了 Platform.platform 这个字段的读写。还有一种更隐蔽的
 * 情况：类的静态初始化里调用 Platform 的实例方法。因为 Platform.platform 在
 * SocoMain 构造前是 null，invokevirtual 会在解析接收者时 NPE，栈里只会指向
 * 那个类的 &lt;clinit&gt;，看不出是谁触发的加载。
 *
 * 另外也列出 &lt;clinit&gt; 里访问 Gdx.* 的类 —— Gdx.app 在 IOSApplication 完成
 * 初始化前也是 null，RoboVM 的 AOT 会把静态初始化提前到类首次被引用时，
 * 顺序和 Dalvik 不同，容易在这里翻车。
 *
 * 用法:
 *   java ClinitGuards <jar> [more jars...]
 */
public class ClinitGuards {

    public static void main(String[] args) throws Exception {
        Set<String> platformCalls = new TreeSet<>();
        Set<String> gdxCalls = new TreeSet<>();

        for (String jarPath : args) {
            try (ZipFile zf = new ZipFile(new File(jarPath))) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                            String owner;

                            @Override
                            public void visit(int version, int access, String name,
                                              String signature, String superName, String[] ifaces) {
                                owner = name;
                            }

                            @Override
                            public MethodVisitor visitMethod(int access, String name, String desc,
                                                             String signature, String[] exceptions) {
                                if (!"<clinit>".equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String o, String mname,
                                                                String mdesc, boolean itf) {
                                        if ("com/soco/util/platform/Platform".equals(o)) {
                                            platformCalls.add(owner + " -> " + mname + mdesc);
                                        }
                                        if ("com/badlogic/gdx/Gdx".equals(o)) {
                                            gdxCalls.add(owner + " -> " + mname + mdesc);
                                        }
                                    }

                                    @Override
                                    public void visitFieldInsn(int opcode, String o, String fname,
                                                               String fdesc) {
                                        if ("com/badlogic/gdx/Gdx".equals(o)) {
                                            gdxCalls.add(owner + " -> " + fname);
                                        }
                                    }
                                };
                            }
                        }, 0);
                    }
                }
            }
        }

        System.out.println("=== <clinit> 里调用 Platform.* 的类（会 NPE） ===");
        if (platformCalls.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String s : platformCalls) System.out.println("  " + s);
        }

        System.out.println("=== <clinit> 里访问 Gdx.* 的类（app 未就绪时危险） ===");
        if (gdxCalls.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String s : gdxCalls) System.out.println("  " + s);
        }

        System.out.println("platformClinit=" + platformCalls.size()
                + " gdxClinit=" + gdxCalls.size());
    }
}
