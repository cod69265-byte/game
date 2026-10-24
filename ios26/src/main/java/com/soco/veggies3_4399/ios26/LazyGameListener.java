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

    /**
     * RoboVM's AOT only compiles classes reachable from bytecode references.
     * {@code CryptoProvider} registers its services by class <em>name</em> (a
     * string in its constructor), and {@code Provider.Service.newInstance} then
     * resolves the name through reflection at runtime — so without this anchor
     * the reachability analysis never sees {@code SHA1_MessageDigestImpl} and
     * the provider registration from run #11 failed with
     * {@code MessageDigest SHA-1 implementation not found: ClassNotFoundException}.
     * An {@code LDC} class constant is a real bytecode reference, which pulls
     * the implementation class (and its dependency chain) into the build.
     * {@code SHA1PRNG_SecureRandomImpl} is anchored for the same reason:
     * anything calling {@code new SecureRandom()} would hit it.
     */
    @SuppressWarnings("unused")
    private static final Class<?>[] PROVIDER_ANCHORS = {
            org.apache.harmony.security.provider.crypto.SHA1_MessageDigestImpl.class,
            org.apache.harmony.security.provider.crypto.SHA1PRNG_SecureRandomImpl.class,
    };

    /**
     * Same reachability problem, second victim: {@code java.net.URL}'s constructor.
     *
     * <p>RoboVM's {@code java.net.URL} keeps RoboVM/Android's handler table, so the
     * first {@code new URL("http://...")} runs
     * {@code Class.forName("com.android.okhttp.HttpHandler")}. Those classes ship in
     * robovm-rt but are referenced only by name, so AOT dropped them and the game died
     * with:
     * <pre>
     * java.lang.AssertionError: java.lang.ClassNotFoundException: com.android.okhttp.HttpHandler
     *     at java.net.URL.setupStreamHandler(URL.java:432)
     *     at java.net.URL.&lt;init&gt;(URL.java:180)
     *     at com.net.Http.run(Unknown Source)
     * </pre>
     *
     * <h2>Why that froze the game on the waiting spinner</h2>
     * {@code AssertionError} is an {@link Error}, and {@code com.net.Http.run()} only
     * catches {@code IOException} and {@code Exception}. The throw therefore escaped
     * {@code run()} and killed the network thread <em>after</em> the request had already
     * been parked in {@code Http.blocks} by {@code sendMessage(req, true, ...)}.
     * Nothing was left to drain that table, so {@code Http.isBlock()} stayed true
     * forever, {@code UI_Waiting.isWaiting()} stayed true forever, and
     * {@code texture/waiting.png} spun over a dead screen — the game never reached the
     * battle module. Anchoring the handlers lets {@code new URL(...)} succeed; the
     * connect then fails with an ordinary {@code IOException}, which
     * {@code Http.run()} does catch, so it clears {@code blocks} and the offline build
     * continues instead of hanging.
     */
    @SuppressWarnings("unused")
    private static final Class<?>[] URL_HANDLER_ANCHORS = {
            com.android.okhttp.HttpHandler.class,
            com.android.okhttp.HttpsHandler.class,
            libcore.net.url.FileHandler.class,
    };

    static {
        // See "Crash 2" above. Harmless if something else registered it first:
        // insertProviderAt ignores a duplicate class name.
        try {
            java.security.Security.insertProviderAt(
                    new org.apache.harmony.security.provider.crypto.CryptoProvider(), 1);
        } catch (Throwable t) {
            // Never block launch over the provider: the first save would fail
            // instead, which is a smaller blast radius than a dead app.
            // Cannot use Gdx.app here: this static block runs from
            // createApplication(), which libGDX calls before Gdx.app is assigned.
            System.err.println("LazyGameListener: registering CryptoProvider failed: " + t);
            t.printStackTrace();
        }

        // See URL_HANDLER_ANCHORS below for the reachability side of this. Even with
        // the handlers present, the game's servers are gone, so every request would
        // still stall until its socket timeout and then pop a "connection failed"
        // dialog. The game already ships an offline switch: V2scNetCallBack.isDanji()
        // returns GameConfig.danJi, and Start.getHttp() routes to com.net.Danji when it
        // is true. Danji overrides sendMessage() to return true without ever touching
        // Http.blocks, so Http.isBlock() stays false, UI_Waiting never engages, and the
        // game proceeds straight into the battle module instead of spinning forever on
        // texture/waiting.png.
        try {
            com.soco.GameEngine.GameConfig.danJi = true;
            // Danji.getInstance() returns a static field that the game never assigns
            // (no caller of Danji.setInstance anywhere in the decompiled sources), and
            // Start.getHttp() calls init() on it unconditionally, so it must be
            // installed before the first request or offline mode becomes an NPE.
            com.net.Danji.setInstance(new OfflineHttp());
        } catch (Throwable t) {
            System.err.println("LazyGameListener: enabling offline (danji) mode failed: " + t);
            t.printStackTrace();
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