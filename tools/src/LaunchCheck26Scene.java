import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Semantic checks for the UIScene launcher that neither LinkCheck nor
 * VerifyJar can express.
 *
 * <p>LinkCheck proves every referenced member exists; VerifyJar proves the
 * bytecode is well formed. Neither proves that the scene wiring is <em>shaped</em>
 * correctly, and the failure mode of a mis-shaped scene setup is silent: UIKit
 * finds no delegate, never calls {@code willConnect}, the window stays detached,
 * and the app renders nothing while logging nothing.
 *
 * <p>What is checked:
 * <ul>
 *   <li>{@code getConfigurationForConnectingSceneSession} is a real override of
 *       {@code UIApplicationDelegateAdapter}, with the exact parameter and return
 *       types UIKit will look for.</li>
 *   <li>{@code willConnect} is a real override of {@code UISceneDelegateAdapter}.</li>
 *   <li>The scene delegate extends {@code UIWindowSceneDelegateAdapter} (not the
 *       plain {@code UISceneDelegateAdapter}), because only the window-scene
 *       variant carries {@code window} / {@code setWindow}.</li>
 *   <li>{@code UIWindow.setWindowScene(UIWindowScene)} exists and is public.</li>
 *   <li>{@code UISceneConfiguration.setDelegateClass(Class)} and
 *       {@code setSceneClass(Class)} exist.</li>
 *   <li>{@code IOSApplication.getUIWindow()} is public, so the scene delegate can
 *       reach the window libGDX already built.</li>
 *   <li>{@code UISceneSessionRole.Application} exists (the role string UIKit
 *       matches against {@code UIWindowSceneSessionRoleApplication}).</li>
 *   <li>The static {@code main} is present and the class is public.</li>
 * </ul>
 *
 * <p>Usage: {@code java LaunchCheck26Scene <classpath already set>}. Last line is
 * {@code LAUNCH CHECK PASSED} or {@code LAUNCH CHECK FAILED (n)}.
 */
public class LaunchCheck26Scene {

    static final List<String> FAIL = new ArrayList<>();
    static int checks = 0;

    static Class<?> load(String name) throws Exception {
        // initialize=false: RoboVM binding classes run Bro.bind in <clinit>, which
        // needs the native runtime and throws UnsatisfiedLinkError on a desktop JVM.
        return Class.forName(name, false, LaunchCheck26Scene.class.getClassLoader());
    }

    static void ok(String what) {
        checks++;
        System.out.println("ok   " + what);
    }

    static void bad(String what) {
        checks++;
        FAIL.add(what);
        System.out.println("FAIL " + what);
    }

    static void require(boolean cond, String what) {
        if (cond) ok(what); else bad(what);
    }

    /** 找到一个签名精确匹配的方法，沿继承链向上找。 */
    static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 继续向上
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        final String LAUNCHER = "com.soco.veggies3_4399.ios26.scene.IOSLauncher26Scene";
        final String SCENE_DELEGATE = LAUNCHER + "$VegSceneDelegate";

        Class<?> launcher = load(LAUNCHER);
        Class<?> sceneDelegate = load(SCENE_DELEGATE);

        Class<?> appDelegate = load("com.badlogic.gdx.backends.iosrobovm.IOSApplication$Delegate");
        Class<?> iosApp = load("com.badlogic.gdx.backends.iosrobovm.IOSApplication");
        Class<?> windowSceneDelegateAdapter = load("org.robovm.apple.uikit.UIWindowSceneDelegateAdapter");
        Class<?> uiApplication = load("org.robovm.apple.uikit.UIApplication");
        Class<?> uiWindow = load("org.robovm.apple.uikit.UIWindow");
        Class<?> uiWindowScene = load("org.robovm.apple.uikit.UIWindowScene");
        Class<?> uiScene = load("org.robovm.apple.uikit.UIScene");
        Class<?> uiSceneSession = load("org.robovm.apple.uikit.UISceneSession");
        Class<?> uiSceneConnectionOptions = load("org.robovm.apple.uikit.UISceneConnectionOptions");
        Class<?> uiSceneConfiguration = load("org.robovm.apple.uikit.UISceneConfiguration");
        Class<?> uiSceneSessionRole = load("org.robovm.apple.uikit.UISceneSessionRole");

        // ---- launcher shape -------------------------------------------------
        require(Modifier.isPublic(launcher.getModifiers()), "IOSLauncher26Scene is public");
        require(appDelegate.isAssignableFrom(launcher), "IOSLauncher26Scene extends IOSApplication.Delegate");

        Method createApplication = findMethod(launcher, "createApplication");
        require(createApplication != null, "createApplication exists");
        if (createApplication != null) {
            require(Modifier.isProtected(createApplication.getModifiers()),
                    "createApplication is protected");
            require(createApplication.getReturnType() == iosApp,
                    "createApplication returns IOSApplication");
        }

        Method main = findMethod(launcher, "main", String[].class);
        require(main != null && Modifier.isStatic(main.getModifiers()),
                "IOSLauncher26Scene has static main(String[])");

        // ---- the scene-lifecycle opt-in method ------------------------------
        // UIKit looks for exactly this selector. A wrong parameter type means the
        // selector never matches and the app silently stays on the legacy path.
        Method config = findMethod(launcher, "getConfigurationForConnectingSceneSession",
                uiApplication, uiSceneSession, uiSceneConnectionOptions);
        require(config != null,
                "getConfigurationForConnectingSceneSession(UIApplication,UISceneSession,UISceneConnectionOptions)");
        if (config != null) {
            require(config.getReturnType() == uiSceneConfiguration,
                    "getConfigurationForConnectingSceneSession returns UISceneConfiguration");
            require(Modifier.isPublic(config.getModifiers()),
                    "getConfigurationForConnectingSceneSession is public");
            // 必须是覆写而不是新方法，否则 RoboVM 不会把它注册成 ObjC 选择器。
            require(config.getDeclaringClass() == launcher,
                    "getConfigurationForConnectingSceneSession is declared on the launcher");
            Method inherited = null;
            try {
                inherited = appDelegate.getMethod("getConfigurationForConnectingSceneSession",
                        uiApplication, uiSceneSession, uiSceneConnectionOptions);
            } catch (NoSuchMethodException ignored) {
                // 下面 require 会报
            }
            require(inherited != null,
                    "UIApplicationDelegateAdapter declares the same signature (true override)");
        }

        // ---- scene delegate shape ------------------------------------------
        require(Modifier.isPublic(sceneDelegate.getModifiers()), "VegSceneDelegate is public");
        require(Modifier.isStatic(sceneDelegate.getModifiers()), "VegSceneDelegate is static (no outer instance)");
        require(windowSceneDelegateAdapter.isAssignableFrom(sceneDelegate),
                "VegSceneDelegate extends UIWindowSceneDelegateAdapter");

        Method willConnect = findMethod(sceneDelegate, "willConnect",
                uiScene, uiSceneSession, uiSceneConnectionOptions);
        require(willConnect != null,
                "VegSceneDelegate.willConnect(UIScene,UISceneSession,UISceneConnectionOptions)");
        if (willConnect != null) {
            require(willConnect.getDeclaringClass() == sceneDelegate,
                    "willConnect is declared on VegSceneDelegate");
            require(Modifier.isPublic(willConnect.getModifiers()), "willConnect is public");
        }

        // 只有 window-scene 变体有 window/setWindow
        require(findMethod(sceneDelegate, "setWindow", uiWindow) != null,
                "VegSceneDelegate inherits setWindow(UIWindow)");

        // ---- the two UIKit APIs the wiring depends on ----------------------
        Method setWindowScene = null;
        try {
            setWindowScene = uiWindow.getMethod("setWindowScene", uiWindowScene);
        } catch (NoSuchMethodException ignored) {
            // 下面 require 会报
        }
        require(setWindowScene != null, "UIWindow.setWindowScene(UIWindowScene) exists");
        if (setWindowScene != null) {
            require(Modifier.isPublic(setWindowScene.getModifiers()),
                    "UIWindow.setWindowScene is public");
        }

        Method setDelegateClass = null;
        try {
            setDelegateClass = uiSceneConfiguration.getMethod("setDelegateClass", Class.class);
        } catch (NoSuchMethodException ignored) {
            // 下面 require 会报
        }
        require(setDelegateClass != null, "UISceneConfiguration.setDelegateClass(Class) exists");
        require(findMethod(uiSceneConfiguration, "setSceneClass", Class.class) != null,
                "UISceneConfiguration.setSceneClass(Class) exists");

        // UISceneSessionRole.Application 对应 plist 里的
        // UIWindowSceneSessionRoleApplication，取错角色会匹配不到配置。
        boolean hasApplicationRole = false;
        for (java.lang.reflect.Field f : uiSceneSessionRole.getFields()) {
            if ("Application".equals(f.getName())
                    && Modifier.isStatic(f.getModifiers())
                    && f.getType() == uiSceneSessionRole) {
                hasApplicationRole = true;
            }
        }
        require(hasApplicationRole, "UISceneSessionRole.Application exists");

        // ---- libGDX side ----------------------------------------------------
        Method getUIWindow = null;
        try {
            getUIWindow = iosApp.getMethod("getUIWindow");
        } catch (NoSuchMethodException ignored) {
            // 下面 require 会报
        }
        require(getUIWindow != null, "IOSApplication.getUIWindow() exists");
        if (getUIWindow != null) {
            require(Modifier.isPublic(getUIWindow.getModifiers()),
                    "IOSApplication.getUIWindow is public");
            require(getUIWindow.getReturnType() == uiWindow,
                    "IOSApplication.getUIWindow returns UIWindow");
        }

        System.out.println("checks=" + checks + " failed=" + FAIL.size());
        if (FAIL.isEmpty()) {
            System.out.println("LAUNCH CHECK PASSED");
        } else {
            System.out.println("LAUNCH CHECK FAILED (" + FAIL.size() + ")");
            for (String f : FAIL) System.out.println("  - " + f);
            System.exit(1);
        }
    }
}
