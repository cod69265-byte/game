package com.soco.veggies3_4399.ios;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.soco.V3Main;
import com.soco.veggies3_4399.IOSPlatForm;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

/**
 * RoboVM entry point for 燃烧的蔬菜3HD.
 *
 * <p>Mirrors {@code com.soco.veggies3_4399.MainActivity} on Android: it builds the
 * single {@link IOSPlatForm} instance, hands it to {@link V3Main}, and lets the
 * libGDX RoboVM backend drive the application lifecycle.
 */
public class IOSLauncher extends IOSApplication.Delegate {

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        config.orientationLandscape = false;
        config.orientationPortrait = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.allowIpod = true;
        config.preventScreenDimming = true;
        config.preferredFramesPerSecond = 60;
        return new IOSApplication(new V3Main(new IOSPlatForm()), config);
    }

    public static void main(String[] args) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(args, null, IOSLauncher.class);
        pool.close();
    }
}
