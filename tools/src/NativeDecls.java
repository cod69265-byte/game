import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * List every {@code native} method declared by the classes in a jar, rendered as
 * the JNI symbol name the runtime will look for.
 *
 * <p>JNI naming rule: the short form {@code Java_pkg_Class_method} is used when
 * the name is unique within its class; the long form with an encoded argument
 * signature is used only when the method is overloaded. Getting this wrong makes
 * every symbol look missing, so the overload decision is made per class here.
 *
 * <p>Usage: {@code java NativeDecls <jar> [packagePrefix]}
 */
public final class NativeDecls {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: NativeDecls <jar> [prefix]");
            System.exit(2);
        }
        String prefix = args.length > 1 ? args[1].replace('.', '/') : "";
        File jar = new File(args[0]);

        List<String> entries = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith(".class") && n.startsWith(prefix)) {
                    entries.add(n);
                }
            }
        } finally {
            zip.close();
        }
        Collections.sort(entries);

        Set<String> out = new LinkedHashSet<String>();
        for (String entry : entries) {
            byte[] bytes = read(jar, entry);
            if (bytes == null) {
                continue;
            }
            new ClassReader(bytes).accept(new Collector(out), 0);
        }
        for (String s : out) {
            System.out.println(s);
        }
        System.err.println("native methods: " + out.size());
    }

    private static final class Collector extends ClassVisitor {
        private final Set<String> out;
        private String owner;
        /** method name -> list of descriptors, only for native methods */
        private final Map<String, List<String>> natives =
                new LinkedHashMap<String, List<String>>();

        Collector(Set<String> out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            owner = name;
            natives.clear();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                List<String> list = natives.get(name);
                if (list == null) {
                    list = new ArrayList<String>();
                    natives.put(name, list);
                }
                list.add(desc);
            }
            return null;
        }

        @Override
        public void visitEnd() {
            for (Map.Entry<String, List<String>> e : natives.entrySet()) {
                String name = e.getKey();
                List<String> descs = e.getValue();
                String base = "Java_" + mangle(owner) + "_" + mangle(name);
                if (descs.size() == 1) {
                    out.add(base);
                } else {
                    // Overloaded: emit the long form for every descriptor.
                    for (String desc : descs) {
                        out.add(base + mangleOverload(desc));
                    }
                }
            }
        }
    }

    private static String mangle(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '.') {
                sb.append('_');
            } else if (c == '_') {
                sb.append("_1");
            } else if (c == ';') {
                sb.append("_2");
            } else if (c == '[') {
                sb.append("_3");
            } else if (c == '$') {
                // Inner classes appear as Foo$Bar in Java and Foo_00024Bar in JNI.
                sb.append("_00024");
            } else if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * javah long form: {@code __} followed by every argument type mangled and
     * concatenated, with no argument count and no separators.
     */
    private static String mangleOverload(String desc) {
        StringBuilder sb = new StringBuilder("__");
        int i = desc.indexOf('(') + 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') {
                i++;
            }
            if (desc.charAt(i) == 'L') {
                i = desc.indexOf(';', i) + 1;
            } else {
                i++;
            }
            sb.append(mangle(desc.substring(start, i)));
        }
        return sb.toString();
    }

    private static byte[] read(File jar, String entry) {
        try {
            ZipFile zip = new ZipFile(jar);
            try {
                InputStream in = zip.getInputStream(zip.getEntry(entry));
                if (in == null) {
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                in.close();
                return out.toByteArray();
            } finally {
                zip.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private NativeDecls() {
    }
}
