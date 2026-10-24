package com.soco.veggies3_4399.ios26.scene;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableColorFormat;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableDepthFormat;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.soco.veggies3_4399.IOSPlatForm;
import com.soco.veggies3_4399.ios26.LazyGameListener;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIScene;
import org.robovm.apple.uikit.UISceneConfiguration;
import org.robovm.apple.uikit.UISceneConnectionOptions;
import org.robovm.apple.uikit.UISceneSession;
import org.robovm.apple.uikit.UISceneSessionRole;
import org.robovm.apple.uikit.UIWindow;
import org.robovm.apple.uikit.UIWindowScene;
import org.robovm.apple.uikit.UIWindowSceneDelegateAdapter;

/**
 * iOS 26/27 UIScene-lifecycle entry point for 燃烧的蔬菜3HD.
 *
 * <h2>Why this class exists</h2>
 * Apple's TN3187 splits the UIScene migration into two stages:
 * <ul>
 *   <li><b>iOS 26</b> — an app that still uses the legacy
 *       {@code UIApplicationDelegate} lifecycle launches normally and only logs
 *       {@code "This process does not adopt the UIScene lifecycle."}</li>
 *   <li><b>The SDK after iOS 26</b> — the same app fails to launch outright.</li>
 * </ul>
 * So on an iOS 26.3 device the legacy path in
 * {@code com.soco.veggies3_4399.ios26.IOSLauncher26} is still correct and is the
 * default build. This class is the forward-compatible variant: it opts in to the
 * scene lifecycle today so the binary keeps launching once the hard requirement
 * lands.
 *
 * <h2>Why it can be done without forking libGDX</h2>
 * libGDX 1.14.2's {@code IOSApplication} has no scene support at all — its
 * {@code didFinishLaunching} is {@code final} and unconditionally builds a
 * {@code new UIWindow(UIScreen.getMainScreen().getBounds())}. That window has no
 * {@code UIWindowScene}, which under the scene lifecycle means it would never be
 * displayed.
 *
 * <p>What makes adoption possible from the outside is that UIKit calls
 * {@code application:didFinishLaunchingWithOptions:} <em>first</em> and
 * {@code scene:willConnectToSession:options:} <em>second</em>. libGDX finishes
 * its whole GL/audio/input bootstrap in the first callback, so by the time the
 * scene connects the window, the {@code MGLKViewController} and the render loop
 * all already exist. The only thing missing is the scene association, and
 * {@code UIWindow.setWindowScene(UIWindowScene)} is public API. Assigning it in
 * {@code willConnect} is therefore enough to put libGDX's window on screen.
 *
 * <h2>Status</h2>
 * This path is opt-in and has not been exercised on hardware (no macOS host in
 * this build environment). The default {@code robovm-ios26.xml} still points at
 * the legacy launcher. To switch, see {@code ios26/README-scene.md}.
 */
public class IOSLauncher26Scene extends IOSApplication.Delegate {

    /**
     * Set by {@link #createApplication()} so the scene delegate can reach the
     * libGDX application. {@code IOSApplication.Delegate} keeps its own
     * reference private with no getter, and {@code IOSApplication.getUIWindow()}
     * is the only public handle we need.
     */
    private static IOSApplication app;

    /**
     * Kept alive so RoboVM has already materialised the Objective-C class by the
     * time {@code setDelegateClass} hands it to UIKit. RoboVM registers an
     * {@code @CustomClass}-less adapter subclass lazily, on first instantiation;
     * passing a bare {@code Class} literal would hand UIKit an unregistered
     * class and {@code willConnect} would never fire.
     */
    private static VegSceneDelegate sceneDelegate;

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();

        config.orientationPortrait = true;
        config.orientationLandscape = false;

        config.useAccelerometer = false;
        config.useCompass = false;
        config.useHaptics = false;

        config.allowIpod = true;
        config.preventScreenDimming = true;
        config.preferredFramesPerSecond = 60;

        config.colorFormat = MGLDrawableColorFormat.RGBA8888;
        config.depthFormat = MGLDrawableDepthFormat._16;
        config.useGL30 = false;

        config.statusBarVisible = false;
        config.hideHomeIndicator = true;
        config.hdpiMode = HdpiMode.Logical;

        config.useAudio = true;

        // LazyGameListener, not "new V3Main(...)": createApplication() runs before
        // IOSApplication assigns Gdx.app, and V3Main's constructor needs Gdx.app.
        // See LazyGameListener for the full failure trace.
        app = new IOSApplication(new LazyGameListener(new IOSPlatForm()), config);
        return app;
    }

    /**
     * Opts the process in to the scene lifecycle. Implementing this method is one
     * of the two signals UIKit accepts (the other being the
     * {@code UIApplicationSceneManifest} key in Info.plist); the scene build
     * supplies both.
     */
    @Override
    public UISceneConfiguration getConfigurationForConnectingSceneSession(
            UIApplication application,
            UISceneSession connectingSceneSession,
            UISceneConnectionOptions options) {

        UISceneConfiguration config =
                new UISceneConfiguration("Veggies3HD Default", UISceneSessionRole.Application);
        config.setSceneClass(UIWindowScene.class);

        if (sceneDelegate == null) sceneDelegate = new VegSceneDelegate();
        config.setDelegateClass(VegSceneDelegate.class);

        return config;
    }

    public static void main(String[] args) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(args, null, IOSLauncher26Scene.class);
        pool.close();
    }

    /**
     * Attaches libGDX's already-built window to the scene UIKit just created.
     */
    public static class VegSceneDelegate extends UIWindowSceneDelegateAdapter {

        @Override
        public void willConnect(UIScene scene, UISceneSession session,
                                UISceneConnectionOptions connectionOptions) {
            if (app == null || !(scene instanceof UIWindowScene)) return;

            UIWindow window = app.getUIWindow();
            if (window == null) return;

            window.setWindowScene((UIWindowScene) scene);
            window.makeKeyAndVisible();
            setWindow(window);
        }
    }
}
