package com.soco.technology;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.soco.sdk.servertime.ConnectListener;
import com.soco.sdk.servertime.SocoServerTime;

public class SynTime {
    private static boolean DEBUG_MODE = false;
    private static boolean bConnect;
    private static long serverTime;
    private static Preferences sp;

    static {
        serverTime = 0L;
        sp = null;
    }

    public static long getTime() {
        if (!bConnect) return -1L;
        if (sp == null) {
            sp = Gdx.app.getPreferences("synTime");
        }
        return System.currentTimeMillis() + sp.getLong("checkTime", 0L);
    }

    public static void refresh() {
        if (sp == null) {
            sp = Gdx.app.getPreferences("synTime");
        }
        final Preferences preferences = sp;
        SocoServerTime.getTime(new ConnectListener() {
            @Override
            public void notify(boolean bl2, String string2) {
                if (bl2) {
                    try {
                        serverTime = Long.parseLong(string2);
                        bConnect = true;
                    } catch (Exception exception) {
                        bConnect = false;
                        return;
                    }
                    preferences.putLong("checkTime", serverTime - System.currentTimeMillis());
                    preferences.flush();
                    return;
                }
                bConnect = false;
            }
        }, DEBUG_MODE);
    }
}
