import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Simulates RoboVM's Objective-C class-name derivation and cross-checks every
 * UISceneDelegateClassName reference in the supplied Info.plist files.
 *
 * <p>Why this check exists: {@code UISceneDelegateClassName} is a bare string.
 * RoboVM registers a Java class as an ObjC class under the name produced by
 * {@code ObjCMemberPlugin.getCustomClassName}:
 * <ul>
 *   <li>{@code @CustomClass("X")} present -&gt; {@code X}</li>
 *   <li>otherwise -&gt; {@code "j_" + fullyQualifiedName.replace('.', '_')}</li>
 * </ul>
 * Only '.' is substituted, so an inner class keeps its '$'. If the plist name and
 * the registered name differ, UIKit finds no class, never calls
 * {@code scene:willConnectToSession:options:}, the window is never attached to the
 * scene, and the app shows a black screen with no diagnostic. Neither javac nor
 * RoboVM reports this.
 *
 * <p>The ObjC-registration predicate is {@code isNSObject()}: the class must have
 * {@code org/robovm/apple/foundation/NSObject} somewhere in its superclass chain.
 * That chain leaves the jar being inspected almost immediately, so the RoboVM
 * binding jars must be supplied as well.
 *
 * <p>Usage: {@code java ObjCNameCheck <any.jar...> <any.plist...>} - files ending
 * in {@code .jar} are indexed, files ending in {@code .xml} are checked.
 *
 * <p>Last line: {@code classes=N custom=N checked=M mismatches=K}. Exits 1 on any
 * mismatch. Output is deliberately ASCII-only so it survives any console codepage.
 */
public class ObjCNameCheck {

    /** ObjC name -> Java internal name */
    static final Map<String, String> NAMES = new TreeMap<>();
    /** Java internal name -> direct superclass internal name */
    static final Map<String, String> SUPERS = new HashMap<>();
    /** Java internal name -> @CustomClass value */
    static final Map<String, String> CUSTOM = new HashMap<>();

    static final String NS_OBJECT = "org/robovm/apple/foundation/NSObject";

    static boolean isNSObject(String internal) {
        String cur = internal;
        for (int hop = 0; cur != null && hop < 64; hop++) {
            if (NS_OBJECT.equals(cur)) return true;
            cur = SUPERS.get(cur);
        }
        return false;
    }

    static void index(File jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        String internal;

                        @Override
                        public void visit(int version, int access, String name,
                                          String signature, String superName, String[] ifaces) {
                            internal = name;
                            if (superName != null) SUPERS.put(name, superName);
                        }

                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String desc, boolean visible) {
                            if ("Lorg/robovm/objc/annotation/CustomClass;".equals(desc)) {
                                return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String key, Object value) {
                                        if ("value".equals(key)) CUSTOM.put(internal, (String) value);
                                    }
                                };
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<File> jars = new ArrayList<>();
        List<File> plists = new ArrayList<>();
        for (String a : args) {
            if (a.toLowerCase().endsWith(".jar")) jars.add(new File(a));
            else plists.add(new File(a));
        }
        if (jars.isEmpty()) {
            System.out.println("usage: ObjCNameCheck <any.jar...> <any.plist...>");
            System.exit(2);
        }

        for (File j : jars) index(j);

        int custom = 0;
        for (Map.Entry<String, String> e : SUPERS.entrySet()) {
            String internal = e.getKey();
            String c = CUSTOM.get(internal);
            if (c != null && !c.trim().isEmpty()) {
                NAMES.put(c.trim(), internal);
                custom++;
            } else if (isNSObject(internal)) {
                NAMES.put("j_" + internal.replace('/', '_'), internal);
            }
        }

        System.out.println("=== ObjC classes RoboVM would register: " + NAMES.size() + " ===");
        int shown = 0;
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            if (shown++ >= 12) {
                System.out.println("  ... (" + NAMES.size() + " total, truncated)");
                break;
            }
            System.out.println("  " + e.getKey() + "  <-  " + e.getValue());
        }

        Pattern p = Pattern.compile(
                "<key>UISceneDelegateClassName</key>\\s*<string>([^<]+)</string>");

        int checked = 0;
        int mismatches = 0;
        for (File pl : plists) {
            String text = new String(java.nio.file.Files.readAllBytes(pl.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = p.matcher(text);
            boolean any = false;
            while (m.find()) {
                any = true;
                String want = m.group(1).trim();
                checked++;
                String hit = NAMES.get(want);
                if (hit != null) {
                    System.out.println("ok   " + pl.getName() + " -> " + want + "  (" + hit + ")");
                } else {
                    mismatches++;
                    System.out.println("FAIL " + pl.getName() + " -> " + want + "  [not registered]");
                    for (String cand : NAMES.keySet()) {
                        if (cand.contains("SceneDelegate")) {
                            System.out.println("       candidate: " + cand);
                        }
                    }
                }
            }
            if (!any) {
                System.out.println("info " + pl.getName()
                        + ": no UISceneDelegateClassName (legacy UIApplicationDelegate lifecycle)");
            }
        }

        System.out.println("classes=" + NAMES.size() + " custom=" + custom
                + " checked=" + checked + " mismatches=" + mismatches);
        if (mismatches != 0) System.exit(1);
    }
}
