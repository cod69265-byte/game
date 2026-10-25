package com.soco.util.libgdx;

import java.util.Map;

/**
 * Crash-site diagnostics for the "start game" freeze.
 *
 * <p>Symptom: after the opening movie starts the game stops updating, while the music keeps
 * playing (the audio thread is independent) and the process stays alive. Nothing is logged
 * at all, so the freeze cannot be told apart between a failing asset, a deadlock on a
 * monitor, and a native GL call that never returns.
 *
 * <h2>Why a watchdog thread instead of a timeout inside the loader</h2>
 * The first attempt patched {@code ResourceManager.waitLoadFinsh()} to give up after 30
 * seconds and log. It never fired: {@code AssetManager.update()} already wraps its body in
 * {@code catch (Throwable)}, so a failing asset surfaces as an exception there rather than
 * as a hang, and a genuine hang happens <em>inside</em> a single {@code update()} call -
 * before any loop counter can advance. A helper thread that samples stacks on a timer is
 * the only thing that can observe that state.
 *
 * <h2>What the dump distinguishes</h2>
 * <ul>
 *   <li>GL thread parked in {@code Object.wait} / {@code BLOCKED} on a monitor: a Java-level
 *       deadlock, and the two dumps name both owners.</li>
 *   <li>GL thread inside {@code Thread.sleep}: the loader is still spinning, so the asset
 *       never completes but does not throw either.</li>
 *   <li>GL thread on a {@code com.badlogic.gdx.graphics.GL20} or MetalANGLE frame: the block
 *       is native, i.e. the driver never returns from that call.</li>
 * </ul>
 *
 * <p>Logging goes through {@code System.err} as well as {@link Log}, because this class can
 * run before the game's own logger is configured and {@code Log} drops records below the
 * configured level.
 */
public final class DshDiag {

    /** Frames kept per thread; RoboVM stacks are deep and the head is what matters. */
    private static final int MAX_FRAMES = 14;

    /** How often the watchdog samples, in milliseconds. */
    private static final long PERIOD_MS = 15000L;

    /**
     * Dumps after this many samples stop, so a long session cannot flood the log. 20 samples
     * at 15s covers five minutes from launch, which is enough for the opening movie plus the
     * first tap on "start".
     */
    private static final int MAX_DUMPS = 20;

    private static volatile boolean started;

    private DshDiag() {
    }

    /**
     * Starts the sampling thread once. Safe to call from any thread and any number of times;
     * later calls are no-ops.
     */
    public static void startWatchdog() {
        if (started) {
            return;
        }
        started = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 1; i <= MAX_DUMPS; i++) {
                        try {
                            Thread.sleep(PERIOD_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        dump(i);
                    }
                }
            }, "dsh-watchdog");
            // Daemon: the diagnostic must never keep the process alive on its own.
            t.setDaemon(true);
            t.start();
            emit("[dsh] watchdog started, sampling every " + PERIOD_MS + "ms");
        } catch (Throwable t) {
            emit("[dsh] watchdog failed to start: " + t);
        }
    }

    /** Logs a full thread dump tagged with the sample number. Never throws. */
    public static void dump(int sample) {
        try {
            emit("[dsh] --- dump " + sample + " begin ---");
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                StringBuilder b = new StringBuilder(320);
                b.append("[dsh] T ").append(t.getName())
                        .append(" id=").append(t.getId())
                        .append(" daemon=").append(t.isDaemon())
                        .append(" ").append(t.getState());
                StackTraceElement[] st = e.getValue();
                int n = st == null ? 0 : Math.min(st.length, MAX_FRAMES);
                for (int i = 0; i < n; i++) {
                    b.append(" | ").append(st[i].toString());
                }
                emit(b.toString());
            }
            emit("[dsh] --- dump " + sample + " end ---");
        } catch (Throwable t) {
            emit("[dsh] dump " + sample + " failed: " + t);
        }
    }

    /**
     * Writes one line to both the device stderr and the game log. {@code Log} is wrapped
     * because it dereferences {@code Platform.platform}, which is null before the platform
     * layer is installed.
     */
    private static void emit(String line) {
        try {
            System.err.println(line);
        } catch (Throwable ignored) {
            // stderr can be closed during teardown; the log call below may still work.
        }
        try {
            Log.warning(line);
        } catch (Throwable ignored) {
            // Same reasoning, mirrored.
        }
    }
}
