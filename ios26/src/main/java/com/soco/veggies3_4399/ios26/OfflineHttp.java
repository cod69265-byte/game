package com.soco.veggies3_4399.ios26;

/**
 * Concrete {@link com.net.Danji} so the game's own offline transport can be installed.
 *
 * <p>{@code com.net.Danji} is the game's offline {@code Http} subclass: it overrides
 * {@code sendMessage()} to return {@code true} without ever touching
 * {@code Http.blocks}, and its {@code run()} is empty. That is exactly the behaviour
 * this port needs, because the game's servers
 * ({@code veggies5.socoveggies.com:28080}, {@code scol.socoveggies.com:8082},
 * {@code 115.28.162.6:8080}) are long gone.
 *
 * <h2>Why a subclass instead of {@code new Danji()}</h2>
 * {@code Danji}'s constructor is {@code protected} and lives in {@code com.net}, so
 * the launcher package cannot call it directly. A subclass in any package may invoke a
 * {@code protected} superclass constructor, which is the cheapest way to get an
 * instance without reflection ({@code setAccessible} is unreliable under RoboVM's AOT
 * runtime).
 *
 * <h2>Why {@code Danji.setInstance(...)} is mandatory</h2>
 * {@code com.main.Start.getHttp(...)} starts with
 * <pre>
 * if (netCallBack.isDanji()) {
 *     Danji danji = Danji.getInstance();
 *     danji.init(url, port, responseHandler, netCallBack);   // NPE if getInstance() is null
 *     http = danji;
 *     return http;
 * }
 * </pre>
 * {@code Danji.getInstance()} just returns the static {@code http} field, and
 * <b>nothing in the game ever calls {@code Danji.setInstance(...)}</b> (verified by
 * grepping every decompiled source). So flipping {@code GameConfig.danJi} alone would
 * replace a frozen waiting spinner with a {@code NullPointerException} on the first
 * request. Installing the instance first makes the offline path actually work.
 */
public final class OfflineHttp extends com.net.Danji {

    public OfflineHttp() {
        super();
    }
}
