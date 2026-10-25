import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads every .p particle file with libGDX 1.14.2's real parser.
 *
 * <p>Why this exists: on the iPhone the game freezes right after tapping start. The log
 * stops immediately after a burst of {@code ResourceManager add: particle/*.p} entries,
 * and {@code ResourceManager.waitLoadFinsh()} spins forever on {@code while (!update())}
 * because {@code update()} swallows the exception and returns false. So any single asset
 * that fails to load becomes an unrecoverable freeze. This harness reproduces the load on
 * a desktop JVM (with the native gdx library extracted and dlopen'ed by hand, since the
 * RoboVM-oriented shim turns SharedLibraryLoader.load into a no-op) so a 12-minute CI round
 * trip is not needed to test the hypothesis.
 */
public final class ParticleCheck2 {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ParticleCheck2 <nativesJar> <particleDir>");
            System.exit(2);
        }
        loadNative(new File(args[0]));
        File dir = new File(args[1]);
        List<File> files = new ArrayList<File>();
        collect(dir, files);

        int ok = 0;
        int failed = 0;
        for (File f : files) {
            try {
                ParticleEffect effect = new ParticleEffect();
                effect.load(new FileHandle(f), new FileHandle(dir));
                ok++;
            } catch (Throwable t) {
                failed++;
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                System.out.println("FAIL " + f.getName() + " :: "
                        + root.getClass().getName() + ": " + root.getMessage());
            }
        }
        System.out.println("ParticleCheck2 files=" + files.size()
                + " ok=" + ok + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Extracts gdx64.dll (or the .so/.dylib twin) and System.load()s it. */
    private static void loadNative(File jar) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String entry;
        if (os.contains("win")) {
            entry = arch.contains("64") ? "gdx64.dll" : "gdx.dll";
        } else if (os.contains("mac")) {
            entry = arch.contains("aarch64") ? "libgdxarm64.dylib" : "libgdx64.dylib";
        } else {
            entry = arch.contains("aarch64") ? "libgdxarm64.so" : "libgdx64.so";
        }
        File tmp = File.createTempFile("gdxnat", entry.substring(entry.lastIndexOf('.')));
        tmp.deleteOnExit();
        ZipFile zf = new ZipFile(jar);
        try {
            ZipEntry ze = zf.getEntry(entry);
            if (ze == null) {
                throw new IllegalStateException("missing " + entry + " in " + jar);
            }
            InputStream in = zf.getInputStream(ze);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
        } finally {
            zf.close();
        }
        System.load(tmp.getAbsolutePath());
        System.out.println("native loaded: " + entry);
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                collect(k, out);
            } else if (k.getName().endsWith(".p")) {
                out.add(k);
            }
        }
    }
}
