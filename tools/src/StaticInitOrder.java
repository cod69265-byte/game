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
 * 找出所有在 &lt;clinit&gt;（静态初始化）里访问 Platform.platform 的类。
 *
 * Platform.platform 是 public static 字段，唯一赋值点是 SocoMain 的构造器
 * （com/soco/SocoMain.&lt;init&gt; 里 Platform.platform = platform）。
 * 如果一个类的静态初始化块在 SocoMain 被 new 之前就跑了，而它又读
 * Platform.platform，就会拿到 null → 真机第一帧之前 NPE，且栈里看不出是谁
 * 先触发的。
 *
 * 这个检查把这类类全部列出来，用于人工确认触发顺序。
 *
 * 用法:
 *   java StaticInitOrder <jar> [more jars...]
 */
public class StaticInitOrder {

    public static void main(String[] args) throws Exception {
        Set<String> readers = new TreeSet<>();
        Set<String> writers = new TreeSet<>();
        Set<String> callers = new TreeSet<>();

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
                                final boolean inClinit = "<clinit>".equals(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(int opcode, String o, String fname,
                                                               String fdesc) {
                                        if (!"com/soco/util/platform/Platform".equals(o)) return;
                                        if (!"platform".equals(fname)) return;
                                        if (opcode == Opcodes.PUTSTATIC) {
                                            writers.add(owner + ".<clinit>=" + inClinit);
                                        } else if (opcode == Opcodes.GETSTATIC && inClinit) {
                                            readers.add(owner);
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(int opcode, String o, String mname,
                                                                String mdesc, boolean itf) {
                                        if ("<clinit>".equals(mname)) {
                                            // 记录"谁的 <clinit> 触发了别的类的 <clinit>"
                                            return;
                                        }
                                        if (inClinit) callers.add(owner);
                                    }
                                };
                            }
                        }, 0);
                    }
                }
            }
        }

        System.out.println("=== 写入 Platform.platform 的位置 ===");
        for (String w : writers) System.out.println("  " + w);

        System.out.println("=== 在 <clinit> 里读 Platform.platform 的类（危险） ===");
        if (readers.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String r : readers) System.out.println("  " + r);
        }

        System.out.println("=== 在 <clinit> 里调用其他方法的类（可能间接触发） ===");
        int shown = 0;
        for (String c : callers) {
            if (shown++ >= 40) {
                System.out.println("  ... 共 " + callers.size() + " 个");
                break;
            }
            System.out.println("  " + c);
        }

        System.out.println("clinitReaders=" + readers.size()
                + " clinitCallers=" + callers.size()
                + " writers=" + writers.size());
    }
}
