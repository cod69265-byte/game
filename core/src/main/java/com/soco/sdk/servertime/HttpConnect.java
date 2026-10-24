package com.soco.sdk.servertime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;

/**
 * iOS port of the server-time HTTP helper.
 *
 * <p>The Android original took an {@code Activity} so it could ask
 * {@code ConnectivityManager} whether a network existed before firing the
 * request. iOS has no such API on the libGDX side, so reachability is now
 * inferred from the request outcome — which is what every caller actually
 * branches on anyway.
 */
public class HttpConnect {
    private static final String CONNECT_ERROR = "网络连接失败，请检查网络";
    private static final String CONNECT_ERROR_503 = "服务器正在维护，请稍后再试";
    private static boolean DEBUG_CONNECT_MESSAGE = true;

    protected static void connect(final String url, final ConnectListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                BufferedReader reader = null;
                try {
                    connection = (HttpURLConnection) ((URL) new URL(url)).openConnection();
                    ((URLConnection) connection).setReadTimeout(20000);
                    ((URLConnection) connection).setConnectTimeout(20000);
                    ((URLConnection) connection).setDoInput(true);
                    ((URLConnection) connection).setDoOutput(true);
                    ((URLConnection) connection).setUseCaches(true);
                    connection.setRequestMethod("POST");

                    int code = connection.getResponseCode();
                    if (code == 200) {
                        reader = new BufferedReader(
                                (Reader) new InputStreamReader(((URLConnection) connection).getInputStream()));
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }
                        listener.notify(true, body.toString());
                        return;
                    }
                    if (code == 503) {
                        listener.notify(false, CONNECT_ERROR_503);
                        return;
                    }
                    if (code == -1) {
                        // Transient socket failure: the original retried once after a short pause.
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            listener.notify(false, CONNECT_ERROR);
                            return;
                        }
                        HttpConnect.connect(url, listener);
                        return;
                    }
                    listener.notify(false, DEBUG_CONNECT_MESSAGE
                            ? CONNECT_ERROR + ": response: " + code
                            : CONNECT_ERROR);
                } catch (UnknownHostException unknownHost) {
                    listener.notify(false, CONNECT_ERROR_503);
                } catch (Exception exception) {
                    listener.notify(false, DEBUG_CONNECT_MESSAGE
                            ? CONNECT_ERROR + ": " + exception
                            : CONNECT_ERROR);
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Exception ignored) {
                            // nothing useful to do while unwinding
                        }
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    public static void setDebug(boolean debug) {
        DEBUG_CONNECT_MESSAGE = debug;
    }
}
