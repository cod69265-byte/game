import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 检查跨 jar 的成员可见性。
 *
 * LinkCheck 只回答"这个类/字段/方法在不在"，不回答"调用方有没有权限碰它"。
 * 游戏字节码是按 libGDX 1.1.0 编的，1.1.0 里 com.badlogic.gdx 的某些成员是
 * protected 或 package-private，游戏自己的 SocoTexture / SocoFreeTypeFontGenerator
 * 就放在 com.badlogic.gdx.graphics 包下，靠同包关系直接访问。换到 1.14.2 之后
 * 如果可见性收紧，编译期查不出来，真机上会在第一次访问时抛 IllegalAccessError。
 *
 * 用法:
 *   java AccessCheck <client.jar> <target.jar> [more targets...]
 *
 * 输出最后一行:
 *   refs=N violations=M
 */
public class AccessCheck {

    /** 成员定义位置与可见性位。 */
    static final class Member {
        int access;
        String owner;
    }

    static final Map<String, Map<String, Member>> FIELDS = new HashMap<>();
    static final Map<String, Map<String, Member>> METHODS = new HashMap<>();
    static final Map<String, String> SUPERS = new HashMap<>();
    /** 只对这些类判可见性：JDK/未提供 jar 的类没有字节码，判不了。 */
    static final java.util.Set<String> SCANNED = new java.util.HashSet<>();

    /** 实例字段，因为匿名内部类不能改外部局部变量。 */
    private int refs;
    private final List<String> violations = new ArrayList<>();

    static void load(File jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
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
                                          String signature, String superName, String[] interfaces) {
                            this.owner = name;
                            if (superName != null) SUPERS.put(name, superName);
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String desc,
                                                       String signature, Object value) {
                            Member m = new Member();
                            m.owner = owner;
                            m.access = access;
                            FIELDS.computeIfAbsent(owner, k -> new HashMap<>()).put(name + desc, m);
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc,
                                                         String signature, String[] exceptions) {
                            Member m = new Member();
                            m.owner = owner;
                            m.access = access;
                            METHODS.computeIfAbsent(owner, k -> new HashMap<>()).put(name + desc, m);
                            SCANNED.add(owner);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
            }
        }
    }

    /**
     * 只登记继承关系，不索引成员。
     *
     * 为什么需要：RoboVM 的 robovm-rt 把 JDK 类库一起打了进去，所以
     * java/lang/Enum、java/util/TimerTask 会进 METHODS/SCANNED。而调用方
     * （BlendMode extends Enum、MyTask2 extends TimerTask）在 client jar 里，
     * 若 client 不被登记，SUPERS 就查不到那条父类边，protected 构造器的
     * subclass 判定会误报。这里只补 SUPERS/SCANNED，不参与"成员是否存在"。
     */
    static void loadHierarchy(File jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name,
                                          String signature, String superName, String[] interfaces) {
                            if (superName != null) SUPERS.put(name, superName);
                            if (interfaces != null) {
                                for (String itf : interfaces) SUPERS.putIfAbsent(name + "#" + itf, itf);
                            }
                            SCANNED.add(name);
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
            }
        }
    }
    static Member findField(String owner, String key) {
        String cur = owner;
        for (int i = 0; i < 64 && cur != null; i++) {
            Map<String, Member> m = FIELDS.get(cur);
            if (m != null && m.containsKey(key)) return m.get(key);
            cur = SUPERS.get(cur);
        }
        return null;
    }

    static Member findMethod(String owner, String key) {
        String cur = owner;
        for (int i = 0; i < 64 && cur != null; i++) {
            Map<String, Member> m = METHODS.get(cur);
            if (m != null && m.containsKey(key)) return m.get(key);
            cur = SUPERS.get(cur);
        }
        return null;
    }

    static String pkg(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : internalName.substring(0, i);
    }

    /** 调用方能否访问这个成员。protected 沿继承链放宽，只报确定非法的。 */
    static boolean accessible(int access, String memberOwner, String callerClass) {
        // JDK classes (java/lang/Enum, java/util/TimerTask ...) are not in the scan set:
        // their hierarchy is invisible, so the protected-subclass rule would false-positive.
        if (!SCANNED.contains(memberOwner)) return true;
        if ((access & Opcodes.ACC_PUBLIC) != 0) return true;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return memberOwner.equals(callerClass);
        if (pkg(memberOwner).equals(pkg(callerClass))) return true;
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            String cur = SUPERS.get(callerClass);
            for (int i = 0; i < 64 && cur != null; i++) {
                if (cur.equals(memberOwner)) return true;
                cur = SUPERS.get(cur);
            }
            return false;
        }
        return false;
    }

    private void checkField(String owner, String key, String caller) {
        if (owner.startsWith("[")) return;
        Member m = findField(owner, key);
        if (m == null) return; // 缺失由 LinkCheck 负责
        refs++;
        if (!accessible(m.access, m.owner, caller)) {
            violations.add(caller + " -> " + m.owner + "." + key
                    + " (access=0x" + Integer.toHexString(m.access) + ")");
        }
    }

    private void checkMethod(String owner, String mname, String mdesc, String caller) {
        if (owner.startsWith("[")) return;
        Member m = findMethod(owner, mname + mdesc);
        if (m == null) return;
        refs++;
        if (!accessible(m.access, m.owner, caller)) {
            violations.add(caller + " -> " + m.owner + "." + mname + mdesc
                    + " (access=0x" + Integer.toHexString(m.access) + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        File client = new File(args[0]);
        // client 自身必须先索引：常量池里的符号引用按 owner 精确解析，若 client 的
        // 成员不在表里，findMethod 会沿继承链落到父类的同名 private 方法上，产生
        // 假越权（例：SocoTexture.addManagedTexture 被误判成 Texture.addManagedTexture）。
        load(client);
        for (int i = 1; i < args.length; i++) load(new File(args[i]));
        // 父类边：RoboVM 的 robovm-rt 打包了 JDK 类库，protected 构造器的 subclass
        // 判定需要 client 的继承关系（见 loadHierarchy 注释）。
        loadHierarchy(client);

        final AccessCheck self = new AccessCheck();

        try (ZipFile zf = new ZipFile(client)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        String caller;

                        @Override
                        public void visit(int version, int access, String name,
                                          String signature, String superName, String[] interfaces) {
                            caller = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc,
                                                         String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner,
                                                           String fname, String fdesc) {
                                    self.checkField(owner, fname + fdesc, caller);
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String mname,
                                                            String mdesc, boolean itf) {
                                    self.checkMethod(owner, mname, mdesc, caller);
                                }
                            };
                        }
                    }, 0);
                }
            }
        }

        for (String v : self.violations) System.out.println("VIOLATION " + v);
        System.out.println("refs=" + self.refs + " violations=" + self.violations.size());
    }
}
