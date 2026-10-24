import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableColorFormat;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableDepthFormat;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableMultisample;
import com.badlogic.gdx.backends.iosrobovm.bindings.metalangle.MGLDrawableStencilFormat;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.soco.veggies3_4399.ios26.IOSLauncher26;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 核对 iOS 26 启动器用到的每一个 API 都真的存在且可用。
 *
 * IOSLauncher26 在桌面 JVM 上不能 new IOSApplication（要真机 UIKit），但配置
 * 对象、枚举常量、Delegate 的抽象方法都可以静态检查。这里把启动器实际读写的
 * 字段全列一遍，确认：
 *   1) IOSApplicationConfiguration 的字段名和类型没变；
 *   2) MetalANGLE 的枚举常量值都在；
 *   3) IOSLauncher26 覆写的 createApplication 真的是 Delegate 的抽象方法。
 *
 * 用法:
 *   java LaunchCheck26
 */
public class LaunchCheck26 {

    static int ok;
    static int bad;

    static void check(String what, boolean cond) {
        if (cond) {
            ok++;
            System.out.println("ok   " + what);
        } else {
            bad++;
            System.out.println("FAIL " + what);
        }
    }

    static void checkField(String name, Class<?> type) {
        try {
            Field f = IOSApplicationConfiguration.class.getField(name);
            check("config." + name + " : " + f.getType().getSimpleName()
                            + (f.getType() == type ? "" : " (期望 " + type.getSimpleName() + ")"),
                    f.getType() == type);
        } catch (NoSuchFieldException e) {
            check("config." + name + " exists", false);
        }
    }

    public static void main(String[] args) throws Exception {
        // --- IOSApplicationConfiguration 上启动器真正赋值的字段 ---
        checkField("orientationPortrait", boolean.class);
        checkField("orientationLandscape", boolean.class);
        checkField("useAccelerometer", boolean.class);
        checkField("useCompass", boolean.class);
        checkField("useHaptics", boolean.class);
        checkField("allowIpod", boolean.class);
        checkField("preventScreenDimming", boolean.class);
        checkField("preferredFramesPerSecond", int.class);
        checkField("useGL30", boolean.class);
        checkField("statusBarVisible", boolean.class);
        checkField("hideHomeIndicator", boolean.class);
        checkField("useAudio", boolean.class);
        checkField("colorFormat", MGLDrawableColorFormat.class);
        checkField("depthFormat", MGLDrawableDepthFormat.class);
        checkField("stencilFormat", MGLDrawableStencilFormat.class);
        checkField("multisample", MGLDrawableMultisample.class);
        checkField("hdpiMode", HdpiMode.class);

        // --- 启动器用到的枚举常量 ---
        check("MGLDrawableColorFormat.RGBA8888",
                MGLDrawableColorFormat.valueOf("RGBA8888") != null);
        check("MGLDrawableDepthFormat._16",
                MGLDrawableDepthFormat.valueOf("_16") != null);
        check("HdpiMode.Logical",
                HdpiMode.valueOf("Logical") != null);

        // --- Delegate 的抽象入口 ---
        // 注意：不能用 Class.forName(name) —— 那会触发 ObjCObject 的 <clinit>，
        // 它要加载 RoboVM 原生运行时（org.robovm.rt.bro.Dl.open），桌面 JVM 上必然
        // UnsatisfiedLinkError。用 initialize=false 只读方法表，不执行静态初始化。
        Class<?> delegate = Class.forName(
                "com.badlogic.gdx.backends.iosrobovm.IOSApplication$Delegate", false,
                LaunchCheck26.class.getClassLoader());
        Method create = delegate.getDeclaredMethod("createApplication");
        check("Delegate.createApplication is abstract",
                java.lang.reflect.Modifier.isAbstract(create.getModifiers()));
        check("IOSLauncher26 extends Delegate",
                delegate.isAssignableFrom(IOSLauncher26.class));
        check("IOSLauncher26 overrides createApplication",
                IOSLauncher26.class.getDeclaredMethod("createApplication")
                        .getDeclaringClass() == IOSLauncher26.class);
        check("IOSLauncher26 has static main(String[])",
                java.lang.reflect.Modifier.isStatic(
                        IOSLauncher26.class.getDeclaredMethod("main", String[].class)
                                .getModifiers()));

        // --- IOSApplication 的构造器签名 ---
        Class<?> app = Class.forName(
                "com.badlogic.gdx.backends.iosrobovm.IOSApplication", false,
                LaunchCheck26.class.getClassLoader());
        boolean ctor = false;
        for (Constructor<?> c : app.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2
                    && p[0].getName().equals("com.badlogic.gdx.ApplicationListener")
                    && p[1] == IOSApplicationConfiguration.class) {
                ctor = true;
            }
        }
        check("IOSApplication(ApplicationListener, IOSApplicationConfiguration)", ctor);

        // --- V3Main 能被 IOSApplication 接受（实现 ApplicationListener）---
        Class<?> listener = Class.forName("com.badlogic.gdx.ApplicationListener");
        Class<?> v3 = Class.forName("com.soco.V3Main");
        check("V3Main implements ApplicationListener", listener.isAssignableFrom(v3));

        System.out.println(bad == 0 ? "LAUNCH CHECK PASSED" : "LAUNCH CHECK FAILED");
        if (bad != 0) System.exit(1);
    }
}
