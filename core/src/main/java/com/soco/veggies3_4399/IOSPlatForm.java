package com.soco.veggies3_4399;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import com.soco.GameEngine.GameSave;
import com.soco.support.pay.DialogListener;
import com.soco.support.pay.PaymentListener;
import com.soco.support.reward.RewardListener;
import com.soco.technology.Config;
import com.soco.technology.SynTime;
import com.soco.util.platform.Platform;

/**
 * iOS (RoboVM) implementation of the game's single platform abstraction layer.
 *
 * <p>This replaces {@code AndroidPlatForm}. It returns {@link #PLATFORM_IOS} so that
 * every {@code getPaltForm() == 3} branch that the original developers had already
 * written for their iOS build becomes live: {@code SocoMain.render()} switches module
 * handling, {@code V3Main} picks the logo screen, and the iOS price/quantity tables in
 * {@code UI_chongzhi} are selected through {@code GameData.isIOS}.
 *
 * <p>Two behaviours are deliberately kept from the cracked Android build:
 * <ul>
 *   <li>{@link #pay} reports success immediately, so in-app purchases grant content
 *       without contacting a store.</li>
 *   <li>Save data is left to {@code SaveData}, which already implements a portable
 *       {@code Gdx.files} + Java-serialization path whenever {@link #loadGame} and
 *       {@link #saveGame} return false.</li>
 * </ul>
 */
public class IOSPlatForm extends Platform {

    /** Reward tables consumed by UI_tequanjiangli through getPrizeList("1".."16"). */
    int[][] reword;
    int[][] reword1;
    int[][] reword2;
    int[][] reword3;
    int[][] reword4;
    int[][] reword5;
    int[][] reword6;
    int[][] reword7;
    int[][] reword8;
    int[][] reword9;
    int[][] reword10;
    int[][] reword11;
    int[][] reword12;
    int[][] reword13;
    int[][] reword14;
    int[][] reword15;
    int[][] reword16;

    private static final String PREF_NAME = "RegistCode";
    private static final String PREF_REGIST = "Regist";
    private static final String PREF_CODE_USED = "CodeUsed";

    public IOSPlatForm() {
        this.reword1 = new int[][]{{6, 1}, {0, 5}, {3, 5}, {4, 10}};
        this.reword2 = new int[][]{{5, 5}, {1, 10}, {3, 5}, {4, 15}};
        this.reword3 = new int[][]{{5, 3}, {2, 1}, {4, 10}};
        this.reword4 = new int[][]{{5, 1}, {3, 5}, {4, 10}};
        this.reword5 = new int[][]{{6, 1}};
        this.reword6 = new int[][]{{6, 1}, {0, 5}, {2, 1}, {4, 15}};
        this.reword7 = new int[][]{{5, 2}, {2, 1}, {1, 5}, {4, 10}};
        this.reword8 = new int[][]{{3, 5}, {5, 2}, {4, 10}, {0, 2}};
        this.reword9 = new int[][]{{1001, 100}, {5, 5}, {2, 1}, {3, 5}};
        this.reword10 = new int[][]{{1001, 200}, {6, 1}, {2, 3}};
        this.reword11 = new int[][]{{1001, 200}};
        this.reword12 = new int[][]{{1001, 1280}, {2, 5}, {0, 10}, {6, 1}};
        this.reword13 = new int[][]{{1001, 2888}, {2, 10}, {0, 20}, {6, 1}, {1, 20}};
        this.reword14 = new int[][]{{1001, 300}, {2, 10}, {0, 20}, {6, 1}, {1, 20}};
        this.reword15 = new int[][]{{8, 1}};
        this.reword16 = new int[][]{{9, 1}};
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public int getPaltForm() {
        return Platform.PLATFORM_IOS;
    }

    /** Reported to FontUtil/InputLabel so their iOS text-baseline branches are used. */
    @Override
    public int getPlatform() {
        return 3;
    }

    @Override
    public String getSystemLang() {
        return "zh";
    }

    @Override
    public String getOs() {
        return "ios";
    }

    @Override
    public String getDevice() {
        return "ios";
    }

    @Override
    public String getClientVersion() {
        return "1.5.2";
    }

    @Override
    public int getChannelCode() {
        return -1;
    }

    @Override
    public String getJarExtend() {
        return null;
    }

    // ------------------------------------------------------------------
    // Save data: returning false delegates to SaveData's portable path,
    // which uses Gdx.files.local + Java serialization + SocoCRC.
    // ------------------------------------------------------------------

    @Override
    public String getSaveDataName() {
        return "FireVeg201406";
    }

    @Override
    public boolean loadGame(GameSave gameSave) {
        return false;
    }

    @Override
    public boolean saveGame(GameSave gameSave) {
        return false;
    }

    // ------------------------------------------------------------------
    // Audio: the iOS backend goes through ObjectAL/AVFoundation, which
    // cannot decode Ogg Vorbis, so AudioUtil rewrites .ogg to .wav.
    // ------------------------------------------------------------------

    @Override
    public boolean useWavRepaceOgg() {
        return true;
    }

    // ------------------------------------------------------------------
    // In-app purchase: always succeeds, mirroring the cracked build.
    // Product ids come from UI_chongzhi's *_IOS tables (19..34, 42..48).
    // ------------------------------------------------------------------

    @Override
    public void pay(int n2, String string2, int n3, PaymentListener paymentListener) {
        if (paymentListener != null) {
            paymentListener.payNotify(true, string2);
        }
    }

    @Override
    public void pay(int n2, String string2, PaymentListener paymentListener) {
        this.pay(n2, string2, 0, paymentListener);
    }

    @Override
    public void pay(int n2, String string2, PaymentListener paymentListener, String string3) {
        if (paymentListener != null) {
            paymentListener.payNotify(true, string2);
        }
    }

    @Override
    public void pay(int n2, String string2, String string3, int n3, PaymentListener paymentListener) {
        if (paymentListener != null) {
            paymentListener.payNotify(true, string2);
        }
    }

    @Override
    public boolean isPriceEnable(String string2) {
        return true;
    }

    // ------------------------------------------------------------------
    // Reward / privilege codes: same code table and same one-shot bitmap
    // as RegistCode, but stored through libGDX Preferences.
    // ------------------------------------------------------------------

    @Override
    public boolean isRewardEnable() {
        return Config.bLibao;
    }

    @Override
    public int[][] getPrizeList(String string2) {
        if ("1".equals(string2)) {
            this.reword = this.reword1;
        } else if ("2".equals(string2)) {
            this.reword = this.reword2;
        } else if ("3".equals(string2)) {
            this.reword = this.reword3;
        } else if ("4".equals(string2)) {
            this.reword = this.reword4;
        } else if ("5".equals(string2)) {
            this.reword = this.reword5;
        } else if ("6".equals(string2)) {
            this.reword = this.reword6;
        } else if ("7".equals(string2)) {
            this.reword = this.reword7;
        } else if ("8".equals(string2)) {
            this.reword = this.reword8;
        } else if ("9".equals(string2)) {
            this.reword = this.reword9;
        } else if ("10".equals(string2)) {
            this.reword = this.reword10;
        } else if ("11".equals(string2)) {
            this.reword = this.reword11;
        } else if ("12".equals(string2)) {
            this.reword = this.reword12;
        } else if ("13".equals(string2)) {
            this.reword = this.reword13;
        } else if ("14".equals(string2)) {
            this.reword = this.reword14;
        } else if ("15".equals(string2)) {
            this.reword = this.reword15;
        } else if ("16".equals(string2)) {
            this.reword = this.reword16;
        }
        return this.reword;
    }

    @Override
    public void inputRewardKey(final RewardListener rewardListener) {
        if (rewardListener == null) {
            return;
        }
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override
            public void input(String text) {
                String code = text == null ? "" : text.trim();
                int group = codeGroup(code);
                if (group < 0) {
                    showToast("特权码无效");
                    return;
                }
                if (isCodeUsed(group)) {
                    showToast("该码已经领取过奖励");
                    return;
                }
                markCodeUsed(group);
                rewardListener.notify(true, String.valueOf(group), 1);
            }

            @Override
            public void canceled() {
                // user dismissed the dialog: no callback, same as Android build
            }
        }, "请输入特权码", "");
    }

    /** Index of the code inside Config.TeQuanMa, or -1 when unknown. */
    private int codeGroup(String code) {
        String[][] codeList = Config.TeQuanMa;
        if (codeList == null || code.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < codeList.length; ++i) {
            if (codeList[i] == null) {
                continue;
            }
            for (int j = 0; j < codeList[i].length; ++j) {
                if (code.equals(codeList[i][j])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Preferences registPrefs() {
        return Gdx.app.getPreferences(PREF_NAME);
    }

    private boolean isCodeUsed(int group) {
        String used = registPrefs().getString(PREF_CODE_USED, "");
        if (used.isEmpty() || group < 0 || group >= used.length()) {
            return false;
        }
        return used.charAt(group) != '0';
    }

    /** Records the group in the "CodeUsed" bitmap, mirroring RegistCode.save(int). */
    private void markCodeUsed(int group) {
        String[][] codeList = Config.TeQuanMa;
        int length = codeList == null ? 0 : codeList.length;
        String used = registPrefs().getString(PREF_CODE_USED, "");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; ++i) {
            if (i == group) {
                builder.append('1');
            } else if (i < used.length()) {
                builder.append(used.charAt(i));
            } else {
                builder.append('0');
            }
        }
        Preferences preferences = registPrefs();
        preferences.putString(PREF_CODE_USED, builder.toString());
        preferences.putBoolean(PREF_REGIST, true);
        preferences.flush();
    }

    // ------------------------------------------------------------------
    // Misc platform surface
    // ------------------------------------------------------------------

    @Override
    public long getServerTime() {
        return SynTime.getTime();
    }

    @Override
    public String displayAvailMemory() {
        return String.valueOf(Runtime.getRuntime().freeMemory());
    }

    @Override
    public void exit() {
        Gdx.app.exit();
    }

    @Override
    public void channelExit(DialogListener dialogListener) {
        if (dialogListener != null) {
            dialogListener.confirm();
        }
    }

    @Override
    public void showToast(String string2) {
        Gdx.app.log("IOSPlatForm", string2 == null ? "" : string2);
    }

    @Override
    public void printLog(String string2) {
        Gdx.app.log("IOSPlatForm", string2 == null ? "" : string2);
    }

    @Override
    public void collectUserData(String string2, String[] stringArray) {
        // analytics disabled: Config.bTongji is false and TalkingData is Android-only
    }

    @Override
    public boolean isChannelExit() {
        return false;
    }

    @Override
    public boolean isKaiwangyeEnable() {
        return false;
    }

    @Override
    public boolean isMoreGame() {
        return false;
    }

    @Override
    public boolean isDanji() {
        return true;
    }

    @Override
    public boolean updateFromNet() {
        return false;
    }
}
