package tools;

import org.robovm.compiler.config.Config;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a RoboVM config XML on any OS, without Xcode and without AOT.
 *
 * Why this exists
 * ---------------
 * RoboVM parses robovm.xml with Simple-XML, which is strict: an element that
 * does not correspond to an @Element-annotated field on Config makes the whole
 * file fail with "Unable to match element ... to object". That parse step is
 * Config.Builder.read(File), pure Java, and runs fine on Windows. build() is the
 * part that needs xcode-select, so we never call it.
 *
 * This turns "wait 90 minutes for CI to reject a typo" into a 2-second local
 * check, and it is the single biggest lever on iteration speed in this project.
 *
 * Two independent layers are checked:
 *   1. Structure  -- Config.Builder.read() must accept the file. Catches
 *                    unknown/misspelled elements, bad nesting, wrong casing.
 *   2. References -- every <framework>, <lib>, <iosInfoPList>, <resources>
 *                    directory and <frameworkPaths> entry is resolved on disk,
 *                    mirroring what build() would do much later in the pipeline.
 *
 * Layer 2 exists because a wrong framework name is otherwise invisible until
 * the linker runs, which on a cold CI runner is an hour away.
 *
 * What it does NOT validate
 * -------------------------
 * That <mainClass> exists on the classpath (use LinkCheck / LaunchCheck26Scene),
 * that ObjC delegate class names match RoboVM's mangling (use ObjCNameCheck),
 * that the requested iOS SDK version is installed (CI only).
 *
 * Usage
 * -----
 *   java ValidateConfig <robovm.xml> [robovm.xml]...
 *
 * Exit code 0 when every file passes, 1 otherwise.
 */
public final class ValidateConfig {

    /** Framework names provided by the iOS SDK itself rather than by a local path. */
    private static final String[] SYSTEM_FRAMEWORKS = {
            "UIKit", "Foundation", "CoreGraphics", "CoreFoundation", "CoreText",
            "CoreAudio", "CoreMedia", "CoreAnimation", "CoreHaptics", "CoreImage",
            "CoreLocation", "CoreVideo", "CoreData", "AudioToolbox", "AVFoundation",
            "AVFAudio", "OpenAL", "OpenGLES", "Metal", "MetalKit", "MetalPerformanceShaders",
            "QuartzCore", "SystemConfiguration", "Security", "StoreKit", "GameController",
            "GameKit", "GLKit", "ImageIO", "MobileCoreServices", "Photos", "SafariServices",
            "WebKit", "Accounts", "AdSupport", "AssetsLibrary", "CFNetwork", "Contacts",
            "EventKit", "HealthKit", "HomeKit", "Intents", "LocalAuthentication", "MapKit",
            "MediaPlayer", "MessageUI", "Network", "NetworkExtension", "NotificationCenter",
            "PassKit", "PDFKit", "SceneKit", "Social", "SpriteKit", "Twitter", "UserNotifications",
            "VideoToolbox", "Vision", "WatchConnectivity", "WebKit", "iAd", "libc++", "libz",
            "z", "c++", "sqlite3", "xml2", "bz2", "iconv", "c", "m"
    };

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ValidateConfig <robovm.xml>...");
            System.exit(2);
        }

        int failed = 0;
        for (String arg : args) {
            failed += validate(new File(arg)) ? 0 : 1;
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("CONFIG OK");
        } else {
            System.out.printf("CONFIG INVALID (%d file(s))%n", failed);
            System.exit(1);
        }
    }

    private static boolean validate(File f) {
        System.out.printf("== %s%n", f.getPath());
        if (!f.isFile()) {
            System.out.println("   FAIL file not found");
            return false;
        }

        boolean ok = true;

        // ---- Layer 1: does RoboVM's own Simple-XML mapping accept this file? ----
        try {
            Config.Builder b = new Config.Builder();
            b.read(f);
            System.out.println("   ok   parsed by Config.Builder.read()");
        } catch (Throwable t) {
            System.out.printf("   FAIL Config.Builder.read(): %s: %s%n",
                    t.getClass().getSimpleName(), t.getMessage());
            return false;
        }

        // ---- Layer 2: do the referenced paths exist? ----
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setErrorHandler(null);
            doc = db.parse(f);
        } catch (Exception e) {
            System.out.printf("   FAIL XML not well-formed: %s%n", e.getMessage());
            return false;
        }

        File base = f.getAbsoluteFile().getParentFile();

        List<File> frameworkPaths = new ArrayList<File>();
        NodeList fp = doc.getElementsByTagName("frameworkPaths");
        for (int i = 0; i < fp.getLength(); i++) {
            NodeList kids = fp.item(i).getChildNodes();
            for (int k = 0; k < kids.getLength(); k++) {
                Node n = kids.item(k);
                if (n instanceof Element && "path".equals(n.getNodeName())) {
                    File p = new File(base, text(n).trim());
                    frameworkPaths.add(p);
                    if (p.isDirectory()) {
                        System.out.printf("   ok   frameworkPath %s%n", rel(base, p));
                    } else {
                        System.out.printf("   FAIL frameworkPath missing: %s%n", rel(base, p));
                        ok = false;
                    }
                }
            }
        }

        // <frameworks> holds either local frameworks (resolved against
        // frameworkPaths) or SDK frameworks (never on disk here). A name is
        // considered resolved if it is a known SDK framework, or if a matching
        // bundle exists under one of the declared paths.
        NodeList fws = doc.getElementsByTagName("frameworks");
        for (int i = 0; i < fws.getLength(); i++) {
            NodeList kids = fws.item(i).getChildNodes();
            for (int k = 0; k < kids.getLength(); k++) {
                Node n = kids.item(k);
                if (!(n instanceof Element) || !"framework".equals(n.getNodeName())) {
                    continue;
                }
                String name = text(n).trim();
                String where = locateFramework(name, frameworkPaths);
                if (where != null) {
                    System.out.printf("   ok   framework %-22s -> %s%n", name, where);
                } else if (isSystemFramework(name)) {
                    System.out.printf("   ok   framework %-22s (iOS SDK)%n", name);
                } else {
                    System.out.printf("   FAIL framework %-22s not found in %s nor a known SDK framework%n",
                            name, frameworkPaths.isEmpty() ? "(no frameworkPaths)" : rel(base, frameworkPaths.get(0)));
                    ok = false;
                }
            }
        }

        // <libs> holds either linker flags (z, c++) or .a/.dylib files relative
        // to the config dir. Anything that looks like a path must exist.
        NodeList libs = doc.getElementsByTagName("libs");
        for (int i = 0; i < libs.getLength(); i++) {
            NodeList kids = libs.item(i).getChildNodes();
            for (int k = 0; k < kids.getLength(); k++) {
                Node n = kids.item(k);
                if (!(n instanceof Element) || !"lib".equals(n.getNodeName())) {
                    continue;
                }
                String name = text(n).trim();
                if (isSystemFramework(name)) {
                    System.out.printf("   ok   lib       %-22s (linker flag)%n", name);
                    continue;
                }
                File asGiven = new File(base, name);
                if (asGiven.isFile()) {
                    System.out.printf("   ok   lib       %-22s -> %s%n", name, rel(base, asGiven));
                    continue;
                }
                File withLib = new File(base, "lib" + name + ".a");
                if (withLib.isFile()) {
                    System.out.printf("   ok   lib       %-22s -> %s%n", name, rel(base, withLib));
                    continue;
                }
                System.out.printf("   FAIL lib       %-22s not found%n", name);
                ok = false;
            }
        }

        NodeList plists = doc.getElementsByTagName("iosInfoPList");
        for (int i = 0; i < plists.getLength(); i++) {
            File p = new File(base, text(plists.item(i)).trim());
            if (p.isFile()) {
                System.out.printf("   ok   iosInfoPList %s%n", rel(base, p));
            } else {
                System.out.printf("   FAIL iosInfoPList missing: %s%n", rel(base, p));
                ok = false;
            }
        }

        NodeList res = doc.getElementsByTagName("resources");
        for (int i = 0; i < res.getLength(); i++) {
            NodeList kids = res.item(i).getChildNodes();
            for (int k = 0; k < kids.getLength(); k++) {
                Node n = kids.item(k);
                if (!(n instanceof Element) || !"directory".equals(n.getNodeName())) {
                    continue;
                }
                File p = new File(base, text(n).trim());
                if (p.isDirectory()) {
                    int count = countFiles(p);
                    System.out.printf("   ok   resources %s (%d files)%n", rel(base, p), count);
                } else {
                    System.out.printf("   FAIL resources directory missing: %s%n", rel(base, p));
                    ok = false;
                }
            }
        }

        // <mainClass> cannot be resolved without a classpath, but an empty or
        // placeholder value is always wrong.
        NodeList mc = doc.getElementsByTagName("mainClass");
        for (int i = 0; i < mc.getLength(); i++) {
            String v = text(mc.item(i)).trim();
            if (v.isEmpty() || v.contains("${")) {
                System.out.printf("   FAIL mainClass unusable: '%s'%n", v);
                ok = false;
            } else {
                System.out.printf("   ok   mainClass %s%n", v);
            }
        }

        // <arch> must be one RoboVM actually knows.
        NodeList archs = doc.getElementsByTagName("arch");
        for (int i = 0; i < archs.getLength(); i++) {
            String v = text(archs.item(i)).trim();
            if ("arm64".equals(v) || "thumbv7".equals(v) || "x86_64".equals(v)) {
                System.out.printf("   ok   arch %s%n", v);
            } else {
                System.out.printf("   FAIL arch not supported by RoboVM 2.3.19: '%s'%n", v);
                ok = false;
            }
        }

        System.out.println(ok ? "   -> PASS" : "   -> FAIL");
        return ok;
    }

    /**
     * Mirrors what build() does with a local framework: look for
     * {@code <name>.framework/<name>} under each declared frameworkPath, then
     * for a bare dylib/archive with the same stem.
     */
    private static String locateFramework(String name, List<File> paths) {
        for (File p : paths) {
            File bundle = new File(p, name + ".framework");
            if (new File(bundle, name).isFile()) {
                return bundle.getPath();
            }
            File dylib = new File(p, name + ".framework");
            if (dylib.isDirectory()) {
                return dylib.getPath() + " (bundle, binary name unverified)";
            }
            File plain = new File(p, "lib" + name + ".a");
            if (plain.isFile()) {
                return plain.getPath();
            }
        }
        return null;
    }

    private static boolean isSystemFramework(String name) {
        for (String s : SYSTEM_FRAMEWORKS) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static int countFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (File k : kids) {
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    private static String text(Node n) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static String rel(File base, File f) {
        String b = base.getAbsolutePath();
        String t = f.getAbsolutePath();
        return t.startsWith(b) ? t.substring(b.length()).replace('\\', '/') : t;
    }

    private ValidateConfig() {
    }
}
