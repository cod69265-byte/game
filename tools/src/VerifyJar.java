import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.Textifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static link/verify pass over the ported game classes.
 *
 * <p>RoboVM performs its own AOT verification, but doing a JVM-level
 * {@code CheckClassAdapter.verify} over the whole jar first turns a device-side
 * crash into a fast, readable report: every missing superclass, interface and
 * field/method descriptor shows up here.
 *
 * <p>Usage: {@code java VerifyJar <jar> [classpath entries...]}
 */
public final class VerifyJar {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: VerifyJar <jar> [cp...]");
            System.exit(2);
        }
        File jar = new File(args[0]);

        List<URL> urls = new ArrayList<URL>();
        urls.add(jar.toURI().toURL());
        for (int i = 1; i < args.length; i++) {
            urls.add(new File(args[i]).toURI().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        List<String> names = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class")) {
                    names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
        } finally {
            zip.close();
        }
        java.util.Collections.sort(names);

        int ok = 0;
        int failed = 0;
        for (String name : names) {
            byte[] bytes;
            try {
                bytes = read(loader, name);
            } catch (Exception e) {
                System.out.println("MISSING " + name + " : " + e);
                failed++;
                continue;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassNode node = new ClassNode();
                reader.accept(node, 0);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                CheckClassAdapter.verify(reader, loader, false, pw);
                pw.flush();
                String report = sw.toString();
                if (report.contains("AnalyzerException") || report.contains("Error") ) {
                    System.out.println("WARN " + name);
                    System.out.println(indent(report));
                    failed++;
                } else {
                    ok++;
                }
            } catch (Exception e) {
                System.out.println("ERROR " + name + " : " + e);
                failed++;
            }
        }
        System.out.println("verified=" + ok + " problems=" + failed + " total=" + names.size());
    }

    private static byte[] read(ClassLoader loader, String name) throws Exception {
        java.io.InputStream in = loader.getResourceAsStream(name.replace('.', '/') + ".class");
        if (in == null) {
            throw new IllegalStateException("not found");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    private VerifyJar() {
    }
}
