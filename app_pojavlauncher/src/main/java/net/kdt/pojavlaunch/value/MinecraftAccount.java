package net.kdt.pojavlaunch.value;


import android.graphics.BitmapFactory;
import android.util.Log;

import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.*;
import com.google.gson.*;
import android.graphics.Bitmap;
import android.util.Base64;

import androidx.annotation.Keep;

import org.apache.commons.io.IOUtils;

@SuppressWarnings("IOStreamConstructor")
@Keep
public class MinecraftAccount {
    public String accessToken = "0"; // access token
    public String clientToken = "0"; // clientID: refresh and invalidate
    public String profileId = "00000000-0000-0000-0000-000000000000"; // profile UUID, for obtaining skin
    public String username = "Steve";
    public String selectedVersion = "1.7.10";
    public boolean isMicrosoft = false;
    public String msaRefreshToken = "0";
    public String xuid;
    public long expiresAt;
    public String skinFaceBase64;
    private Bitmap mFaceCache;
    
    void updateSkinFace(String uuid) {
        try {
            File skinFile = getSkinFaceFile(username);
            Tools.downloadFile("https://mc-heads.net/head/" + uuid + "/100", skinFile.getAbsolutePath());
            
            Log.i("SkinLoader", "Update skin face success");
        } catch (IOException e) {
            // Skin refresh limit, no internet connection, etc...
            // Simply ignore updating skin face
            Log.w("SkinLoader", "Could not update skin face", e);
        }
    }

    /** How long a Minecraft services token is valid when the server does not say */
    public static final long DEFAULT_SESSION_LIFETIME_SECONDS = 86400L;
    /** The session is renewed a bit early, so a game never starts with a token that is about to stop working */
    private static final long SESSION_EXPIRY_MARGIN_MILLIS = 5 * 60 * 1000L;

    /**
     * @param now the time the token was received, in milliseconds
     * @param lifetimeSeconds how long the server says that the token is valid. Zero or less means "unknown".
     * @return the time after which the session counts as expired and has to be renewed
     */
    public static long computeSessionExpiry(long now, long lifetimeSeconds) {
        if(lifetimeSeconds <= 0) lifetimeSeconds = DEFAULT_SESSION_LIFETIME_SECONDS;
        long lifetimeMillis = lifetimeSeconds * 1000L;
        // A very short lifetime must not be eaten up by the margin
        return now + Math.max(lifetimeMillis - SESSION_EXPIRY_MARGIN_MILLIS, lifetimeMillis / 2);
    }

    /**
     * @param now the current time in milliseconds
     * @return whether this is a Microsoft account whose Minecraft token has to be renewed before it is used.
     *         A Local account has no session, so its session never expires.
     */
    public boolean isSessionExpired(long now) {
        return isMicrosoft && now > expiresAt;
    }

    public boolean isLocal(){
        return accessToken.equals("0") && !username.startsWith("Demo.");
    }

    public boolean isDemo(){
        return username.startsWith("Demo.");
    }
    
    public void updateSkinFace() {
        updateSkinFace(profileId);
    }
    
    public String save(String outPath) throws IOException {
        Tools.write(outPath, Tools.GLOBAL_GSON.toJson(this));
        return username;
    }
    
    public String save() throws IOException {
        return save(Tools.DIR_ACCOUNT_NEW + "/" + username + ".json");
    }
    
    public static MinecraftAccount parse(String content) throws JsonSyntaxException {
        return Tools.GLOBAL_GSON.fromJson(content, MinecraftAccount.class);
    }

    public static MinecraftAccount load(String name) {
        if(!accountExists(name)) return null;
        try {
            MinecraftAccount acc = parse(Tools.read(Tools.DIR_ACCOUNT_NEW + "/" + name + ".json"));
            if (acc.accessToken == null) {
                acc.accessToken = "0";
            }
            if (acc.clientToken == null) {
                acc.clientToken = "0";
            }
            if (acc.profileId == null) {
                acc.profileId = "00000000-0000-0000-0000-000000000000";
            }
            if (acc.username == null) {
                acc.username = "0";
            }
            if (acc.selectedVersion == null) {
                acc.selectedVersion = "1.7.10";
            }
            if (acc.msaRefreshToken == null) {
                acc.msaRefreshToken = "0";
            }
            return acc;
        } catch(IOException | JsonSyntaxException e) {
            Log.e(MinecraftAccount.class.getName(), "Caught an exception while loading the profile",e);
            return null;
        }
    }

    public Bitmap getSkinFace(){
        if(isLocal()) return null;

        File skinFaceFile = getSkinFaceFile(username);
        if (!skinFaceFile.exists()) {
            // Legacy version, storing the head inside the json as base 64
            if(skinFaceBase64 == null) return null;
            byte[] faceIconBytes = Base64.decode(skinFaceBase64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(faceIconBytes, 0, faceIconBytes.length);
        } else {
            if(mFaceCache == null) {
                mFaceCache = BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
            }
        }

        return mFaceCache;
    }

    public static Bitmap getSkinFace(String username) {
        return BitmapFactory.decodeFile(getSkinFaceFile(username).getAbsolutePath());
    }

    private static File getSkinFaceFile(String username) {
        return new File(Tools.DIR_CACHE, username + ".png");
    }

    private static boolean accountExists(String username){
        return new File(Tools.DIR_ACCOUNT_NEW + "/" + username + ".json").exists();
    }
}
