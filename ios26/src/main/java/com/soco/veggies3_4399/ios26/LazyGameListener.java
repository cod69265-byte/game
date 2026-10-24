package com.soco.veggies3_4399.ios26;

import com.badlogic.gdx.ApplicationListener;
import com.soco.V3Main;
import com.soco.util.platform.Platform;

/**
 * Defers construction of {@link V3Main} until libGDX has published {@code Gdx.app},
 * and repairs the JCA provider list RoboVM ships empty.
 *
 * <h2>Crash 1 this fixes: Gdx.app not available</h2>
 * libGDX 1.14.2's {@code IOSApplication.Delegate.didFinishLaunching} calls
 * {@code createApplication()} <em>first</em> and only afterwards enters
 * {@code IOSApplication.didFinishLaunching}, whose very first statement is
 * {@code Gdx.app = this}. So everything a launcher builds inside
 * {@code createApplication()} runs with {@code Gdx.app == null}.
 *
 * <p>That is harmless for a plain {@code ApplicationListener}, but {@code V3Main}
 * extends {@code SocoMain}, whose constructor builds a {@code GestureDetector}.
 * {@code GestureDetector}'s inner {@code Task} extends {@code Timer.Task}, and
 * {@code Timer.Task}'s constructor asserts {@code Gdx.app != null}. On hardware the
 * result was:
 * <pre>
 * java.lang.IllegalStateException: Gdx.app not available.
 *     at com.badlogic.gdx.utils.Timer$Task.&lt;init&gt;(Timer.java:212)
 *     at com.badlogic.gdx.input.GestureDetector$1.&lt;init&gt;(GestureDetector.java:55)
 *     at com.badlogic.gdx.input.GestureDetector.&lt;init&gt;(GestureDetector.java:65)
 *     at com.soco.SocoMain.&lt;init&gt;
 *     at com.soco.V3Main.&lt;init&gt;
 * </pre>
 * Under libGDX 1.1.0 (the Android build) the ordering happened to be benign, which
 * is why the game's own code never had to care.
 *
 * <h2>Crash 2 this fixes: MessageDigest SHA implementation not found</h2>
 * Once the game runs it saves its state on the title screen through
 * {@code GameData.save()} -> {@code ObjectOutputStream}, and Java serialization
 * computes every class's serialVersionUID with a SHA message digest. That threw
 * <pre>
 * java.security.NoSuchAlgorithmException: MessageDigest SHA implementation not found
 *     at org.apache.harmony.security.fortress.Engine.notFound(Engine.java:177)
 *     at java.security.MessageDigest.getInstance(...)
 *     at java.io.ObjectStreamClass.computeDefaultSUID(...)
 * </pre>
 * because RoboVM's {@code java/security/security.properties} names five providers
 * that are all Android-only and none of them exists in robovm-rt:
 * {@code com.android.org.conscrypt.OpenSSLProvider}, the DRL cert factory, the
 * Android BouncyCastle build, {@code CryptoProvider} and the conscrypt JSSE
 * provider. {@code Security.loadProviders} therefore registers nothing and every
 * JCA lookup fails. robovm-rt does bundle Harmony's crypto provider with a working
 * {@code SHA-1} implementation; it just is never wired up. Registering it by hand
 * from the first listener callback gives {@code MessageDigest.getInstance("SHA")},
 * which is all {@code ObjectStreamClass} needs.
 *
 * <h2>Why deferring V3Main is the safe fix</h2>
 * {@code IOSApplication.didFinishLaunching} assigns {@code Gdx.app} (line 118) and
 * only calls {@code listener.create()} at line 160, by which point {@code Gdx.gl},
 * {@code Gdx.files}, {@code Gdx.graphics}, {@code Gdx.audio}, {@code Gdx.input} and
 * {@code Gdx.net} are all populated too. Building {@code V3Main} on the first
 * {@link #create()} therefore sees a fully initialised {@code Gdx} — the exact same
 * state the Android launcher had when it ran {@code initialize()}.
 *
 * <p>{@code IOSGraphics.draw()} returns early on the dummy first frame before
 * touching {@code app.listener.render()}, so no listener method can be reached
 * before {@link #create()} has run.
 */
public final class LazyGameListener implements ApplicationListener {

    private final Platform platform;
    private ApplicationListener delegate;

    static {
        // See "Crash 2" above. Harmless if something else registered it first:
        // insertProviderAt ignores a duplicate class name.
        try {
            java.security.Security.insertProviderAt(
                    new org.apache.harmony.security.provider.crypto.CryptoProvider(), 1);
        } catch (Throwable t) {
            // Never block launch over the provider: the first save would fail
            // instead, which is a smaller blast radius than a dead app.
            com.badlogic.gdx.Gdx.app.error("LazyGameListener",
                    "registering CryptoProvider failed", t);
        }
    }

    public LazyGameListener(Platform platform) {
        this.platform = platform;
    }

    /** Built on demand; by then {@code Gdx.app} and friends are set. */
    private ApplicationListener delegate() {
        if (delegate == null) {
            delegate = new V3Main(platform);
        }
        return delegate;
    }

    @Override
    public void create() {
        delegate().create();
    }

    @Override
    public void resize(int width, int height) {
        delegate().resize(width, height);
    }

    @Override
    public void render() {
        delegate().render();
    }

    @Override
    public void pause() {
        if (delegate != null) delegate.pause();
    }

    @Override
    public void resume() {
        if (delegate != null) delegate.resume();
    }

    @Override
    public void dispose() {
        if (delegate != null) delegate.dispose();
    }
}