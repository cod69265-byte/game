import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Constant-pool link checker.
 *
 * <p>Answers one question: if the classes in {@code <jar>} are loaded against
 * exactly the classpath given on the command line, does every class, field and
 * method reference they contain actually resolve?
 *
 * <p>This is the measurement that decides whether the ported game bytecode can
 * be linked against a newer libGDX than the 1.1.0 it was compiled against.
 *
 * <p>Usage: {@code java LinkCheck <jar> [cp...]}
 */
public final class LinkCheck {

    private static final Map<String, Set<String>> MISSING =
            new TreeMap<String, Set<String>>();

    private static URLClassLoader loader;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: LinkCheck <jar> [cp...]");
            System.exit(2);
        }
        File jar = new File(args[0]);
        List<URL> urls = new ArrayList<URL>();
        urls.add(jar.toURI().toURL());
        for (int i = 1; i < args.length; i++) {
            urls.add(new File(args[i]).toURI().toURL());
        }
        loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        List<String> names = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith(".class")) {
                    names.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        } finally {
            zip.close();
        }
        Collections.sort(names);

        int classes = 0;
        for (String name : names) {
            byte[] bytes = read(name);
            if (bytes == null) {
                continue;
            }
            classes++;
            try {
                new ClassReader(bytes).accept(new RefVisitor(name), 0);
            } catch (Throwable t) {
                note(name, "<reader> " + t);
            }
        }

        int total = 0;
        for (Map.Entry<String, Set<String>> e : MISSING.entrySet()) {
            for (String ref : e.getValue()) {
                System.out.println(e.getKey() + " -> " + ref);
                total++;
            }
        }
        System.out.println("classes=" + classes + " unresolved=" + total
                + " affectedClasses=" + MISSING.size());
    }

    private static final class RefVisitor extends ClassVisitor {
        private final String owner;

        RefVisitor(String owner) {
            super(Opcodes.ASM9);
            this.owner = owner;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            if (superName != null) {
                resolveClass(superName);
            }
            if (interfaces != null) {
                for (String itf : interfaces) {
                    resolveClass(itf);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String signature, Object value) {
            if ((access & Opcodes.ACC_STATIC) != 0 && value instanceof String) {
                // constant String, nothing to resolve
            }
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                        String d, boolean visible) {
                    resolveDesc(d);
                    return null;
                }
            };
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc,
                                                                  boolean visible) {
            resolveDesc(desc);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            resolveMethodDesc(desc);
            if (exceptions != null) {
                for (String ex : exceptions) {
                    resolveClass(ex);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    resolveClass(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String o, String n, String d) {
                    resolveDesc(d);
                    resolveField(o, n, d);
                }

                @Override
                public void visitMethodInsn(int opcode, String o, String n, String d,
                                            boolean itf) {
                    resolveMethodDesc(d);
                    resolveMethod(o, n, d);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof org.objectweb.asm.Type) {
                        org.objectweb.asm.Type t = (org.objectweb.asm.Type) value;
                        if (t.getSort() == org.objectweb.asm.Type.OBJECT) {
                            resolveClass(t.getInternalName());
                        } else if (t.getSort() == org.objectweb.asm.Type.ARRAY) {
                            resolveDesc(t.getDescriptor());
                        } else if (t.getSort() == org.objectweb.asm.Type.METHOD) {
                            resolveMethodDesc(t.getDescriptor());
                        }
                    }
                }

                @Override
                public void visitMultiANewArrayInsn(String desc, int dims) {
                    resolveDesc(desc);
                }

                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                        String desc, boolean visible) {
                    resolveDesc(desc);
                    return null;
                }

                @Override
                public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(
                        int typeRef, org.objectweb.asm.TypePath typePath,
                        org.objectweb.asm.Label[] start, org.objectweb.asm.Label[] end,
                        int[] index, String desc, boolean visible) {
                    resolveDesc(desc);
                    return null;
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                               org.objectweb.asm.Label end,
                                               org.objectweb.asm.Label handler,
                                               String type) {
                    if (type != null) {
                        resolveClass(type);
                    }
                }
            };
        }
    }

    // ------------------------------------------------------------------ refs

    private static void resolveClass(String internalName) {
        if (internalName == null || internalName.startsWith("[")) {
            resolveDesc(internalName);
            return;
        }
        if (isJdk(internalName) || present(internalName)) {
            return;
        }
        note(internalName, "<class>");
    }

    private static void resolveDesc(String desc) {
        if (desc == null) {
            return;
        }
        for (String name : descClasses(desc)) {
            resolveClass(name);
        }
    }

    private static void resolveMethodDesc(String desc) {
        resolveDesc(desc);
    }

    private static void resolveField(String owner, String name, String desc) {
        if (isJdk(owner) || !present(owner)) {
            return;
        }
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
            if (hasField(c, name, desc)) {
                return;
            }
            note(owner, "<field> " + name + " " + desc);
        } catch (Throwable t) {
            note(owner, "<field-lookup-failed> " + name + " " + t);
        }
    }

    private static void resolveMethod(String owner, String name, String desc) {
        if (isJdk(owner) || !present(owner)) {
            return;
        }
        if (name.startsWith("<")) {
            return; // <init>/<clinit> always match their own class
        }
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
            if (hasMethod(c, name, desc)) {
                return;
            }
            note(owner, "<method> " + name + desc);
        } catch (Throwable t) {
            note(owner, "<method-lookup-failed> " + name + " " + t);
        }
    }

    private static boolean hasMethod(Class<?> c, String name, String desc) {
        if (c == null) {
            return false;
        }
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)
                    && org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) {
                return true;
            }
        }
        if (hasMethod(c.getSuperclass(), name, desc)) {
            return true;
        }
        for (Class<?> itf : c.getInterfaces()) {
            if (hasMethod(itf, name, desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasField(Class<?> c, String name, String desc) {
        if (c == null) {
            return false;
        }
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (f.getName().equals(name)
                    && org.objectweb.asm.Type.getDescriptor(f.getType()).equals(desc)) {
                return true;
            }
        }
        if (hasField(c.getSuperclass(), name, desc)) {
            return true;
        }
        for (Class<?> itf : c.getInterfaces()) {
            if (hasField(itf, name, desc)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- helpers

    private static boolean isJdk(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("org/w3c/")
                || internalName.startsWith("org/xml/");
    }

    private static boolean present(String internalName) {
        return loader.getResource(internalName + ".class") != null;
    }

    private static List<String> descClasses(String desc) {
        List<String> out = new ArrayList<String>();
        if (desc == null) {
            return out;
        }
        int i = 0;
        while (i < desc.length()) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    break;
                }
                out.add(desc.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static byte[] read(String className) {
        InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class");
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // nothing
            }
        }
    }

    private static void note(String owner, String what) {
        Set<String> set = MISSING.get(owner);
        if (set == null) {
            set = new LinkedHashSet<String>();
            MISSING.put(owner, set);
        }
        set.add(what);
    }

    private LinkCheck() {
    }
}
