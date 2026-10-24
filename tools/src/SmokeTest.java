import com.soco.util.platform.Platform;
import com.soco.veggies3_4399.IOSPlatForm;

import java.lang.reflect.Method;

/**
 * Linkage smoke test for the iOS platform layer.
 *
 * <p>Runs on a desktop JVM (no Gdx context needed) and exercises the parts of
 * {@link IOSPlatForm} that are pure: identity, the 16 reward tables and the
 * cracked-payment callback. Anything that touches {@code Gdx} is skipped, so
 * this catches missing classes/signatures without an iOS toolchain.
 *
 * <p>Usage: {@code java SmokeTest}
 */
public final class SmokeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        IOSPlatForm form = new IOSPlatForm();

        check("getPaltForm", Platform.PLATFORM_IOS, form.getPaltForm());
        check("getPlatform", 3, form.getPlatform());
        check("getSystemLang", "zh", form.getSystemLang());
        check("getOs", "ios", form.getOs());
        check("getDevice", "ios", form.getDevice());
        check("getClientVersion", "1.5.2", form.getClientVersion());
        check("getChannelCode", -1, form.getChannelCode());
        check("getJarExtend", null, form.getJarExtend());
        check("getSaveDataName", "FireVeg201406", form.getSaveDataName());
        check("useWavRepaceOgg", Boolean.TRUE, form.useWavRepaceOgg());
        check("isPriceEnable", Boolean.TRUE, form.isPriceEnable("6元"));
        check("isDanji", Boolean.TRUE, form.isDanji());
        check("isChannelExit", Boolean.FALSE, form.isChannelExit());
        check("isKaiwangyeEnable", Boolean.FALSE, form.isKaiwangyeEnable());
        check("isMoreGame", Boolean.FALSE, form.isMoreGame());
        check("updateFromNet", Boolean.FALSE, form.updateFromNet());

        // loadGame/saveGame must return false so SaveData's portable Gdx.files
        // path is used instead of the Android openFileInput/openFileOutput path.
        Class<?> gameSave = Class.forName("com.soco.GameEngine.GameSave");
        Method loadGame = IOSPlatForm.class.getMethod("loadGame", gameSave);
        Method saveGame = IOSPlatForm.class.getMethod("saveGame", gameSave);
        check("declares loadGame", Boolean.TRUE,
                loadGame.getDeclaringClass() == IOSPlatForm.class);
        check("declares saveGame", Boolean.TRUE,
                saveGame.getDeclaringClass() == IOSPlatForm.class);

        // Reward tables: 16 groups, each a non-empty int[][] of {itemId, count}.
        int[][] total = null;
        for (int i = 1; i <= 16; i++) {
            int[][] table = form.getPrizeList(String.valueOf(i));
            if (table == null || table.length == 0) {
                fail("getPrizeList(" + i + ") empty");
                continue;
            }
            for (int[] row : table) {
                if (row == null || row.length != 2) {
                    fail("getPrizeList(" + i + ") malformed row");
                    break;
                }
            }
            total = table;
        }
        check("getPrizeList(16) is reword16", 1, total == null ? -1 : total.length);

        // The cracked payment path must call back synchronously with success.
        final boolean[] called = new boolean[1];
        final boolean[] granted = new boolean[1];
        final String[] order = new String[1];
        form.pay(24, "ORDER-42", 0, new com.soco.support.pay.PaymentListener() {
            @Override
            public void payNotify(boolean success, String orderId) {
                called[0] = true;
                granted[0] = success;
                order[0] = orderId;
            }
        });
        check("pay callback fired", Boolean.TRUE, called[0]);
        check("pay granted", Boolean.TRUE, granted[0]);
        check("pay orderId echoed", "ORDER-42", order[0]);

        // iOS product ids defined by iapConfig must all be routable.
        int[] iosIds = {19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                31, 32, 33, 34, 42, 43, 44, 45, 46, 47, 48};
        for (int id : iosIds) {
            final boolean[] hit = new boolean[1];
            form.pay(id, "ORD-" + id, 0, new com.soco.support.pay.PaymentListener() {
                @Override
                public void payNotify(boolean success, String orderId) {
                    hit[0] = success;
                }
            });
            if (!hit[0]) {
                fail("pay(" + id + ") did not grant");
            }
        }
        System.out.println("ios product ids routed: " + iosIds.length);

        System.out.println(failures == 0
                ? "SMOKE TEST PASSED"
                : "SMOKE TEST FAILED: " + failures + " check(s)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(String label, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            System.out.println("ok   " + label + " = " + actual);
        } else {
            fail(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        failures++;
    }

    private SmokeTest() {
    }
}
