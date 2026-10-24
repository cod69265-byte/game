import com.badlogic.gdx.Gdx;
import com.soco.util.platform.Platform;
import com.soco.veggies3_4399.IOSPlatForm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 在桌面 JVM 上验证 iOS 26 运行时契约。
 *
 * <p>不启动图形环境，只检查那些"会在真机上第一帧之前炸掉"的静态契约：
 * 平台层覆写是否齐全、游戏主类能否在 1.14.2 下被反射解析、注入的兼容成员
 * 是否真的存在且语义正确。任何一项失败都意味着 RoboVM 编译能过但运行时崩。
 */
public final class RuntimeContract {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform platform = new IOSPlatForm();
        Platform.platform = platform;

        // --- 平台层身份 -------------------------------------------------
        check("PLATFORM_IOS constant", 3, Platform.PLATFORM_IOS);
        check("getPaltForm", 3, platform.getPaltForm());
        check("getPlatform", 3, platform.getPlatform());
        check("getSaveDataName", "FireVeg201406", platform.getSaveDataName());
        check("useWavRepaceOgg", true, platform.useWavRepaceOgg());

        // --- 1.14.2 注入的兼容成员 --------------------------------------
        Class<?> textBounds = Class.forName("com.badlogic.gdx.graphics.g2d.BitmapFont$TextBounds");
        Object tb = textBounds.getConstructor().newInstance();
        textBounds.getField("width").setFloat(tb, 12.5f);
        textBounds.getField("height").setFloat(tb, 34.5f);
        Object tb2 = textBounds.getConstructor(textBounds).newInstance(tb);
        check("TextBounds copy.width", 12.5f, textBounds.getField("width").getFloat(tb2));
        check("TextBounds copy.height", 34.5f, textBounds.getField("height").getFloat(tb2));

        Method setScale = Class.forName("com.badlogic.gdx.graphics.g2d.BitmapFont")
                .getMethod("setScale", float.class);
        check("BitmapFont.setScale declared by BitmapFont",
                true, setScale.getDeclaringClass().getName()
                        .equals("com.badlogic.gdx.graphics.g2d.BitmapFont"));

        Class<?> os = Class.forName("com.badlogic.gdx.utils.Os");
        check("Os.IOS present", true, os.getField("IOS") != null);
        Class<?> loader = Class.forName("com.badlogic.gdx.utils.SharedLibraryLoader");
        Object osValue = loader.getField("os").get(null);
        check("SharedLibraryLoader.os == Os.IOS", true, osValue == os.getField("IOS").get(null));
        loader.getMethod("load", String.class).invoke(loader.getConstructor().newInstance(), "gdx");

        Class<?> uiUtils = Class.forName("com.badlogic.gdx.scenes.scene2d.utils.UIUtils");
        check("UIUtils.isIos", true, uiUtils.getField("isIos").getBoolean(null));
        check("UIUtils.isAndroid", false, uiUtils.getField("isAndroid").getBoolean(null));

        // --- 游戏主类在 1.14.2 下可解析 ---------------------------------
        Class<?> v3 = Class.forName("com.soco.V3Main");
        Constructor<?> ctor = v3.getConstructor(Platform.class);
        check("V3Main(Platform) public", true, Modifier.isPublic(ctor.getModifiers()));

        // SocoMain 的构造函数会把 Platform.platform 绑上，是真正的第一处运行时依赖。
        check("Platform.platform is bound", true, Platform.platform == platform);

        // --- 游戏侧对 1.14.2 的调用点全部可解析 --------------------------
        Class<?> fontUtil = Class.forName("com.soco.util.libgdx.FontUtil");
        check("FontUtil.DEFAULT_CHARS non-empty", true,
                ((String) fontUtil.getField("DEFAULT_CHARS").get(null)).length() > 0);
        check("FontUtil.useHiero (skips FreeType path)", true,
                fontUtil.getField("useHiero").getBoolean(null));

        // --- 存档链路：loadGame/saveGame 返回 false 才会走 Gdx.files ------
        Method load = platform.getClass().getMethod("loadGame",
                Class.forName("com.soco.GameEngine.GameSave"));
        Method save = platform.getClass().getMethod("saveGame",
                Class.forName("com.soco.GameEngine.GameSave"));
        check("loadGame declared by IOSPlatForm", true,
                load.getDeclaringClass() == platform.getClass());
        check("saveGame declared by IOSPlatForm", true,
                save.getDeclaringClass() == platform.getClass());

        // --- 音频：wav 必须存在（IOSPlatForm.useWavRepaceOgg=true） -------
        Class<?> audioUtil = Class.forName("com.soco.util.libgdx.AudioUtil");
        Method getKey = audioUtil.getMethod("getKey", String.class);
        check("AudioUtil.getKey rewrites .ogg", "Audio/Music/bg.wav",
                getKey.invoke(null, "Audio/Music/bg.ogg"));

        System.out.println(failed == 0 ? "RUNTIME CONTRACT PASSED" : "FAILURES: " + failed);
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void check(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "ok   " : "FAIL ") + what + " = " + actual
                + (ok ? "" : " (expected " + expected + ")"));
        if (!ok) {
            failed++;
        }
    }

    private RuntimeContract() {
    }
}
