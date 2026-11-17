package net.kdt.pojavlaunch.mirrors;

import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;

public class DownloadMirror {
    public static final int DOWNLOAD_CLASS_LIBRARIES = 0;
    public static final int DOWNLOAD_CLASS_METADATA = 1;
    public static final int DOWNLOAD_CLASS_ASSETS = 2;

    private static final String URL_PROTOCOL_TAIL = "://";
    private static final String[] MIRROR_BMCLAPI = {
            "https://bmclapi2.bangbang93.com/maven",
            "https://bmclapi2.bangbang93.com",
            "https://bmclapi2.bangbang93.com/assets"
    };

    /**
     * Download a file with the current mirror. If the file is missing on the mirror,
     * fall back to the official source.
     * @param downloadClass Class of the download. Can either be DOWNLOAD_CLASS_LIBRARIES,
     *                      DOWNLOAD_CLASS_METADATA or DOWNLOAD_CLASS_ASSETS
     * @param urlInput The original (Mojang) URL for the download
     * @param outputFile The output file for the download
     * @param buffer The shared buffer
     * @param monitor The download monitor.
     */
    public static void downloadFileMirrored(int downloadClass, String urlInput, File outputFile,
                                            @Nullable byte[] buffer, Tools.DownloaderFeedback monitor) throws IOException {
        String mirrorUrl = getMirrorMapping(downloadClass, urlInput);
        try {
            DownloadUtils.downloadFileMonitored(mirrorUrl, outputFile, buffer, monitor);
            return;
        }catch (IOException e) {
            // Without a mirror there is no other source to ask
            if(mirrorUrl.equals(urlInput)) throw e;
            logFallback(e);
        }
        DownloadUtils.downloadFileMonitored(urlInput, outputFile, buffer, monitor);
    }

    /** A mirror that is down, rate limited or missing a file must not stop the download of an official file */
    private static void logFallback(IOException e) {
        if(e instanceof FileNotFoundException) {
            Log.w("DownloadMirror", "Cannot find the file on the mirror", e);
        } else {
            Log.w("DownloadMirror", "The mirror failed to deliver the file", e);
        }
        Log.i("DownloadMirror", "Falling back to default source");
    }

    /**
     * Download a file with the current mirror. If the file is missing on the mirror,
     * fall back to the official source.
     * @param downloadClass Class of the download. Can either be DOWNLOAD_CLASS_LIBRARIES,
     *                      DOWNLOAD_CLASS_METADATA or DOWNLOAD_CLASS_ASSETS
     * @param urlInput The original (Mojang) URL for the download
     * @param outputFile The output file for the download
     */
    public static void downloadFileMirrored(int downloadClass, String urlInput, File outputFile) throws IOException {
        String mirrorUrl = getMirrorMapping(downloadClass, urlInput);
        try {
            DownloadUtils.downloadFile(mirrorUrl, outputFile);
            return;
        }catch (IOException e) {
            if(mirrorUrl.equals(urlInput)) throw e;
            logFallback(e);
        }
        DownloadUtils.downloadFile(urlInput, outputFile);
    }

    /**
     * Get the content length of a file on the current mirror. If the file is missing on the mirror,
     * or the mirror does not give out the length, request the length from the original source
     * @param downloadClass Class of the download. Can either be DOWNLOAD_CLASS_LIBRARIES,
     *                      DOWNLOAD_CLASS_METADATA or DOWNLOAD_CLASS_ASSETS
     * @param urlInput The original (Mojang) URL for the download
     * @return the length of the file denoted by the URL in bytes, or -1 if not available
     */
    public static long getContentLengthMirrored(int downloadClass, String urlInput) throws IOException {
        String mirrorUrl = getMirrorMapping(downloadClass, urlInput);
        long length = -1;
        try {
            length = DownloadUtils.getContentLength(mirrorUrl);
        }catch (IOException e) {
            if(mirrorUrl.equals(urlInput)) throw e;
            Log.w("DownloadMirror", "Unable to reach the mirror to get the content length", e);
        }
        // Without a mirror, the original source was already asked
        if(length < 1 && !mirrorUrl.equals(urlInput)) {
            Log.w("DownloadMirror", "Unable to get content length from mirror");
            Log.i("DownloadMirror", "Falling back to default source");
            return DownloadUtils.getContentLength(urlInput);
        }
        return length;
    }

    /**
     * Download a file as a string from the current mirror. If the file does not exist on the mirror
     * or the mirror returns an invalid string, request the file from the original source
     * @param downloadClass Class of the download. Can either be DOWNLOAD_CLASS_LIBRARIES,
     *                      DOWNLOAD_CLASS_METADATA or DOWNLOAD_CLASS_ASSETS
     * @param urlInput The original (Mojang) URL for the download
     * @return the contents of the downloaded file as a String.
     */
    public static String downloadStringMirrored(int downloadClass, String urlInput) throws IOException{
        String resultString = null;
        String mirrorUrl = getMirrorMapping(downloadClass, urlInput);
        try {
            resultString = DownloadUtils.downloadString(mirrorUrl);
        }catch (IOException e) {
            if(mirrorUrl.equals(urlInput)) throw e;
            Log.w("DownloadMirror", "Failed to download string from mirror", e);
        }
        if(Tools.isValidString(resultString)) {
            return resultString;
        }else {
            Log.w("DownloadMirror", "Downloaded string is invalid, falling back to default");
        }
        return DownloadUtils.downloadString(urlInput);
    }

    /**
     * Check if the current download source is a mirror and not an official source.
     * @return true if the source is a mirror, false otherwise
     */
    public static boolean isMirrored() {
        return !LauncherPreferences.PREF_DOWNLOAD_SOURCE.equals("default");
    }

    private static String[] getMirrorSettings() {
        switch (LauncherPreferences.PREF_DOWNLOAD_SOURCE) {
            case "bmclapi": return MIRROR_BMCLAPI;
            case "default":
            default:
                return null;
        }
    }

    private static String getMirrorMapping(int downloadClass, String mojangUrl) throws MalformedURLException{
        String[] mirrorSettings = getMirrorSettings();
        if(mirrorSettings == null) return mojangUrl;
        int urlTail = getBaseUrlTail(mojangUrl);
        String baseUrl = mojangUrl.substring(0, urlTail);
        String path = mojangUrl.substring(urlTail);
        switch(downloadClass) {
            case DOWNLOAD_CLASS_ASSETS:
            case DOWNLOAD_CLASS_METADATA:
                baseUrl = mirrorSettings[downloadClass];
                break;
            case DOWNLOAD_CLASS_LIBRARIES:
                if(!baseUrl.endsWith("libraries.minecraft.net")) break;
                baseUrl = mirrorSettings[downloadClass];
                break;
        }
        return baseUrl + path;
    }

    private static int getBaseUrlTail(String wholeUrl) throws MalformedURLException{
        int protocolNameEnd = wholeUrl.indexOf(URL_PROTOCOL_TAIL);
        if(protocolNameEnd == -1)
            throw new MalformedURLException("No protocol, or non path-based URL");
        protocolNameEnd += URL_PROTOCOL_TAIL.length();
        int hostnameEnd = wholeUrl.indexOf('/', protocolNameEnd);
        if(protocolNameEnd >= wholeUrl.length() || hostnameEnd == protocolNameEnd)
            throw new MalformedURLException("No hostname");
        if(hostnameEnd == -1) hostnameEnd = wholeUrl.length();
        return hostnameEnd;
    }
}
