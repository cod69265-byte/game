import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.utils.Array;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads every .p particle file with libGDX 1.14.2's parser and reports failures.
 *
 * <p>Motivation: on device the game froze inside
 * {@code ResourceManager.waitLoadFinsh()}, which spins on
 * {@code while (!update())} where {@code update()} swallows the exception and returns
 * false. The last thing the log showed before the freeze was a burst of
 * {@code ResourceManager add: particle/*.p}, so the prime suspect is that the 1.14.2
 * {@link com.badlogic.gdx.graphics.g2d.ParticleEffectLoader} cannot parse the 1.1.0-era
 * particle format this game ships.
 *
 * <p>This runs on the desktop JVM (no GL needed) so the answer comes in seconds instead
 * of a 12-minute CI round trip.
 */
public final class ParticleCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ParticleCheck <particleDir>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory()) {
            System.err.println("not a directory: " + dir);
            System.exit(2);
        }
        List<File> files = new ArrayList<File>();
        collect(dir, files);

        int ok = 0;
        int failed = 0;
        for (File f : files) {
            try {
                ParticleEffect effect = new ParticleEffect();
                effect.load(new FileHandle(f), new FileHandle(dir));
                Array<?> emitters = null;
                try {
                    emitters = (Array<?>) ParticleEffect.class.getMethod("getEmitters")
                            .invoke(effect);
                } catch (NoSuchMethodException ignored) {
                    // 1.1.0 had no getEmitters(); presence is enough.
                }
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
        System.out.println("ParticleCheck files=" + files.size()
                + " ok=" + ok + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
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
