package com.soco.sdk.servertime;

public class SocoServerTime {
    private static String test_url;
    private static String url;

    static {
        url = "http://115.28.162.6:8080/gamecharge/services/soco/selecttime";
        test_url = "http://192.168.2.87:8080/gamecharge/services/soco/selecttime";
    }

    public static void getTime(ConnectListener connectListener, boolean bl2) {
        if (bl2) {
            HttpConnect.connect(test_url, connectListener);
            return;
        }
        HttpConnect.connect(url, connectListener);
    }
}
