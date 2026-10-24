package com.soco.veggies3_4399.ios26;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableColorFormat;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableDepthFormat;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.soco.veggies3_4399.IOSPlatForm;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

/**
 * RoboVM entry point for 燃烧的蔬菜3HD on iOS 26.
 *
 * <p>Identical in structure to {@code com.soco.veggies3_4399.ios.IOSLauncher},
 * but bound to libGDX 1.14.2 + gdx-backend-robovm-metalangle. The Metal backend
 * is selected purely by which backend jar is on the classpath; the only code
 * differences are the configuration knobs 1.14.2 added and the MetalANGLE
 * colour/depth format enums.
 *
 * <p>The game itself is unchanged: {@link V3Main} plus {@link IOSPlatForm} run
 * the exact bytecode that shipped in the APK.
 */
public class IOSLauncher26 extends IOSApplication.Delegate {

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();

        // The game is authored at 533x854 and lays itself out for portrait only.
        config.orientationPortrait = true;
        config.orientationLandscape = false;

        // Touch-only title; leaving the sensors on costs battery and does nothing.
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useHaptics = false;

        config.allowIpod = true;
        config.preventScreenDimming = true;
        config.preferredFramesPerSecond = 60;

        // MetalANGLE presents through a CAMetalLayer; RGBA8888 + 16-bit depth matches
        // what the original GLES2 build asked for and keeps sprite batching cheap.
        config.colorFormat = MGLDrawableColorFormat.RGBA8888;
        config.depthFormat = MGLDrawableDepthFormat._16;
        config.useGL30 = false;

        config.statusBarVisible = false;
        config.hideHomeIndicator = true;
        config.hdpiMode = HdpiMode.Logical;

        // The game replaces Ogg with Wav on iOS (IOSPlatForm.useWavRepaceOgg),
        // so the audio engine must be present.
        config.useAudio = true;

        // LazyGameListener, not "new V3Main(...)": createApplication() runs before
        // IOSApplication assigns Gdx.app, and V3Main's constructor needs Gdx.app.
        // See LazyGameListener for the full failure trace.
        return new IOSApplication(new LazyGameListener(new IOSPlatForm()), config);
    }

    public static void main(String[] args) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(args, null, IOSLauncher26.class);
        pool.close();
    }
}
