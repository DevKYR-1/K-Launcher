package net.kdt.pojavlaunch.authenticator.microsoft;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.utils.LauncherLog;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import javax.net.ssl.SSLException;

/**
 * Allow to perform a background login on a given account.
 * The steps are the ones of the official launcher: Microsoft account -> Xbox Live -> XSTS -> Minecraft
 * services -> game profile. A failed step ends the login with a message that says what is wrong
 * (no internet, session no longer valid, server problem...) instead of a bare stack trace.
 * Tokens and codes are never written to the logs.
 */
public class MicrosoftBackgroundLogin {
    private static final String authTokenUrl = "https://login.live.com/oauth20_token.srf";
    private static final String xblAuthUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String xstsAuthUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String mcLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String mcProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private static final String mcStoreUrl = "https://api.minecraftservices.com/entitlements/mcstore";

    /** A dead network must end the login with an error, not leave it waiting forever */
    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 20000;

    private final boolean mIsRefresh;
    private final String mAuthCode;
    private static final Map<Long, Integer> XSTS_ERRORS;
    static {
        XSTS_ERRORS = new ArrayMap<>();
        XSTS_ERRORS.put(2148916233L, R.string.xerr_no_account);
        XSTS_ERRORS.put(2148916235L, R.string.xerr_not_available);
        XSTS_ERRORS.put(2148916236L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916237L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916238L ,R.string.xerr_child);
    }

    /* Fields used to fill the account  */
    public String msRefreshToken;
    public String mcName;
    public String mcToken;
    public String mcUuid;
    public boolean doesOwnGame;
    public long expiresAt;

    public MicrosoftBackgroundLogin(boolean isRefresh, String authCode){
        mIsRefresh = isRefresh;
        mAuthCode = authCode;
    }

    /** Performs a full login, calling back listeners appropriately  */
    public void performLogin(@Nullable final ProgressListener progressListener,
                             @Nullable final DoneListener doneListener,
                             @Nullable final ErrorListener errorListener){
        sExecutorService.execute(() -> {
            try {
                notifyProgress(progressListener, 1);
                String accessToken = acquireAccessToken(mIsRefresh, mAuthCode);
                notifyProgress(progressListener, 2);
                String xboxLiveToken = acquireXBLToken(accessToken);
                notifyProgress(progressListener, 3);
                String[] xsts = acquireXsts(xboxLiveToken);
                notifyProgress(progressListener, 4);
                String mcToken = acquireMinecraftToken(xsts[0], xsts[1]);
                notifyProgress(progressListener, 5);
                fetchOwnedItems(mcToken);
                checkMcProfile(mcToken);

                MinecraftAccount acc = MinecraftAccount.load(mcName);
                if(acc == null) acc = new MinecraftAccount();
                acc.xuid = xsts[0];
                acc.clientToken = "0"; /* FIXME */
                acc.accessToken = mcToken;
                acc.username = mcName;
                acc.profileId = mcUuid;
                acc.isMicrosoft = true;
                acc.msaRefreshToken = msRefreshToken;
                acc.expiresAt = expiresAt;
                acc.updateSkinFace();
                acc.save();

                if(doneListener != null) {
                    MinecraftAccount finalAcc = acc;
                    Tools.runOnUiThread(() -> doneListener.onLoginDone(finalAcc));
                }

            }catch (Exception e){
                final Exception failure = presentFailure(withoutSecrets(e));
                LauncherLog.error("MicrosoftLogin", "The login failed (" + (mIsRefresh ? "session renewal" : "new login") + ")", failure);
                if(errorListener != null)
                    Tools.runOnUiThread(() -> errorListener.onLoginError(failure));
            }
            ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE_MICROSOFT);
        });
    }

    /** Step 1: Microsoft account token. For a refresh, the refresh token takes the place of the authorization code. */
    public String acquireAccessToken(boolean isRefresh, String authcode) throws IOException, JSONException {
        // The code, the refresh token and the form built from them give access to the account: never log them
        Log.i("MicrosoftLogin", "Requesting the Microsoft access token, isRefresh=" + isRefresh);

        String formData = convertToFormData(
                "client_id", "00000000402b5328",
                isRefresh ? "refresh_token" : "code", authcode,
                "grant_type", isRefresh ? "refresh_token" : "authorization_code",
                "redirect_url", "https://login.live.com/oauth20_desktop.srf",
                "scope", "service::user.auth.xboxlive.com::MBI_SSL"
        );
        byte[] formBytes = formData.getBytes(StandardCharsets.UTF_8);

        //да пошла yf[eq1 она ваша джава 11
        HttpURLConnection conn = openConnection(authTokenUrl);
        try {
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("charset", "utf-8");
            conn.setRequestProperty("Content-Length", Integer.toString(formBytes.length));
            conn.setRequestMethod("POST");
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.connect();
            try(OutputStream wr = conn.getOutputStream()) {
                wr.write(formBytes);
            }
            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) {
                JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
                msRefreshToken = jo.getString("refresh_token");
                return jo.getString("access_token");
            }
            if(status == 400) {
                // "invalid_grant": the code or the refresh token is expired, revoked or was already used.
                // Asking again does not help, the user has to log in again.
                throw new PresentedException(describeStatus(conn), R.string.microsoft_session_invalid);
            }
            throw getResponseThrowable(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** Step 2: Xbox Live user token */
    private String acquireXBLToken(String accessToken) throws IOException, JSONException {
        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", accessToken);
        data.put("Properties",properties);
        data.put("RelyingParty", "http://auth.xboxlive.com");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = openConnection(xblAuthUrl);
        try {
            setCommonProperties(conn, req);
            conn.connect();

            try(OutputStream wr = conn.getOutputStream()) {
                wr.write(req.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) {
                JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
                return jo.getString("Token");
            }
            throw getResponseThrowable(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** Step 3: XSTS token for the Minecraft services
     * @return [uhs, token]*/
    private @NonNull String[] acquireXsts(String xblToken) throws IOException, JSONException {
        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));
        data.put("Properties", properties);
        data.put("RelyingParty", "rp://api.minecraftservices.com/");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = openConnection(xstsAuthUrl);
        try {
            setCommonProperties(conn, req);
            conn.connect();

            try(OutputStream wr = conn.getOutputStream()) {
                wr.write(req.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) {
                JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
                String uhs = jo.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
                String token = jo.getString("Token");
                return new String[]{uhs, token};
            }else if(status == 401) {
                // The body of this error only tells what is wrong with the Xbox account, it holds no token
                String responseContents = Tools.read(conn.getErrorStream());
                JSONObject jo = new JSONObject(responseContents);
                long xerr = jo.optLong("XErr", -1);
                Integer locale_id = XSTS_ERRORS.get(xerr);
                if(locale_id != null) {
                    throw new PresentedException(new RuntimeException(responseContents), locale_id);
                }
                throw new PresentedException(new RuntimeException(responseContents), R.string.xerr_unknown, xerr);
            }else{
                throw getResponseThrowable(conn);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Step 4: Minecraft services token. This token is what the game uses to prove who the player is. */
    private String acquireMinecraftToken(String xblUhs, String xblXsts) throws IOException, JSONException {
        JSONObject data = new JSONObject();
        data.put("identityToken", "XBL3.0 x=" + xblUhs + ";" + xblXsts);

        String req = data.toString();
        HttpURLConnection conn = openConnection(mcLoginUrl);
        try {
            setCommonProperties(conn, req);
            conn.connect();

            try(OutputStream wr = conn.getOutputStream()) {
                wr.write(req.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) {
                long now = System.currentTimeMillis();
                JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
                mcToken = jo.getString("access_token");
                // The server tells how long the token is valid. When it does not, it is 24 hours.
                expiresAt = MinecraftAccount.computeSessionExpiry(now,
                        jo.optLong("expires_in", MinecraftAccount.DEFAULT_SESSION_LIFETIME_SECONDS));
                return mcToken;
            }
            throw getResponseThrowable(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** Step 5: the game store entitlements */
    private void fetchOwnedItems(String mcAccessToken) throws IOException {
        HttpURLConnection conn = openConnection(mcStoreUrl);
        try {
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setUseCaches(false);
            conn.connect();
            int status = conn.getResponseCode();
            if(status < 200 || status >= 300) {
                throw getResponseThrowable(conn);
            }
            // We don't need any data from this request, it just needs to happen in order for
            // the MS servers to work properly. The data from this is practically useless
            // as it does not indicate whether the user owns the game through Game Pass.
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Step 6: the game profile. The profile exists only if the account owns Minecraft: Java Edition.
     * The services answer 404 when there is no profile, and only then the account is a Demo account.
     * Any other failure (expired token, rate limit, server error) is NOT proof that the game is not
     * owned: it ends the login with an error, so a temporary problem never turns a real account into a Demo one.
     */
    private void checkMcProfile(String mcAccessToken) throws IOException, JSONException {
        HttpURLConnection conn = openConnection(mcProfileUrl);
        try {
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setUseCaches(false);
            conn.connect();

            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) {
                String s = Tools.read(conn.getInputStream());
                JSONObject jsonObject = new JSONObject(s);
                String name = (String) jsonObject.get("name");
                String uuid = (String) jsonObject.get("id");
                String uuidDashes = uuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
                );
                doesOwnGame = true;
                Log.i("MicrosoftLogin","UserName = " + name);
                mcName=name;
                mcUuid=uuidDashes;
            }else if(status == 404){
                Log.i("MicrosoftLogin","It seems that this Microsoft Account does not own the game.");
                doesOwnGame = false;
                mcName = "Demo.Player";
                mcUuid = "00000000-0000-0000-0000-000000000000";
            }else{
                throw getResponseThrowable(conn);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Wrapper to ease notifying the listener */
    private void notifyProgress(@Nullable ProgressListener listener, int step){
        if(listener != null){
            Tools.runOnUiThread(() -> listener.onLoginProgress(step));
        }
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MICROSOFT, step*20);
    }

    /** Open a connection that gives up after a while instead of waiting for a dead network forever */
    private static HttpURLConnection openConnection(String address) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        return conn;
    }

    /** Set common properties for the connection. Given that all requests are POST, interactivity is always enabled */
    private static void setCommonProperties(HttpURLConnection conn, String formData) {
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("charset", "utf-8");
        try {
            conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
            conn.setRequestMethod("POST");
        }catch (ProtocolException e) {
            Log.e("MicrosoftAuth", e.toString());
        }
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
    }

    /**
     * @param data A series a strings: key1, value1, key2, value2...
     * @return the data converted as a form string for a POST request
     */
    private static String convertToFormData(String... data) throws UnsupportedEncodingException {
        StringBuilder builder = new StringBuilder();
        for(int i=0; i<data.length; i+=2){
            if (builder.length() > 0) builder.append("&");
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(data[i+1], "UTF-8"));
        }
        return builder.toString();
    }

    /** A technical description of a failed request: which server and which status. It holds no secret. */
    private static RuntimeException describeStatus(HttpURLConnection conn) throws IOException {
        URL url = conn.getURL();
        return new RuntimeException("HTTP " + conn.getResponseCode() + " " + conn.getResponseMessage()
                + " from " + url.getHost() + url.getPath());
    }

    /** Turn the status of a failed request into an exception with a message for the user */
    private RuntimeException getResponseThrowable(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        RuntimeException cause = describeStatus(conn);
        Log.i("MicrosoftLogin", "Error code: " + status + ": " + conn.getResponseMessage());
        if(status == 429) {
            return new PresentedException(cause, R.string.microsoft_login_retry_later);
        }
        if(status == 401) {
            // The token was refused: it is expired or was revoked
            return new PresentedException(cause, R.string.microsoft_session_invalid);
        }
        return new PresentedException(cause, R.string.microsoft_server_error, status);
    }

    /** Network trouble is not a bug of the launcher: say what it is instead of showing a stack trace */
    private static Exception presentFailure(Exception failure) {
        if(failure instanceof PresentedException) return failure;
        // The depth limit protects from a cyclic chain of causes
        Throwable cause = failure;
        for(int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if(cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException
                    || cause instanceof SSLException
                    || cause instanceof EOFException) {
                return new PresentedException(failure, R.string.microsoft_no_internet);
            }
        }
        return failure;
    }

    /**
     * The message of a JSON parsing failure can hold the text that was being parsed, and the text of a
     * successful answer holds tokens. Such a message must not reach a log file or a dialog.
     */
    private static Exception withoutSecrets(Exception failure) {
        if(!(failure instanceof JSONException)) return failure;
        JSONException safeCopy = new JSONException("The answer of the server could not be understood");
        safeCopy.setStackTrace(failure.getStackTrace());
        return safeCopy;
    }
}
