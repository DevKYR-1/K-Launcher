package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLException;

import net.kdt.pojavlaunch.Tools;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;

/**
 * Network download helpers used by everything in the launcher that fetches something.
 * <ul>
 *     <li>HTTP failures keep their status code (see {@link HttpStatusException}).</li>
 *     <li>Transient failures (timeouts, dropped connections, HTTP 429/5xx) are tried again a few
 *     times with a growing delay. Refusals (403, 404...) are not, that would only waste time.</li>
 *     <li>Files are written to "name.part" and moved over the real name only when the whole body
 *     has arrived, so an interrupted download (lost connection, the app being killed) can never leave a
 *     truncated file that looks installed.</li>
 * </ul>
 */
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final String TAG = "DownloadUtils";
    private static final int TIME_OUT = 10000;
    private static final String PART_SUFFIX = ".part";

    /** How many times in total a transient failure is tried before giving up (first try included) */
    static final int MAX_ATTEMPTS = 4;
    /** Seconds the first retry waits, every next retry waits twice as long. Not final: the tests shorten it. */
    static volatile long sRetryDelayMillis = 1000;
    private static final long MAX_RETRY_DELAY_MILLIS = 30000;
    /** How many times a file is downloaded when its SHA-1 does not match (the first download included) */
    static final int MAX_SHA1_ATTEMPTS = 5;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        try {
            HttpURLConnection conn = openChecked(url.toString());
            try (InputStream is = conn.getInputStream()) {
                IOUtils.copy(is, os);
            } finally {
                conn.disconnect();
            }
        } catch (HttpStatusException | HttpStatusException.NotFound e) {
            // The status is already described well, and callers want to see its type
            throw e;
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        }
    }

    public static String downloadString(String url) throws IOException {
        return withRetries(url, MAX_ATTEMPTS, () -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            download(url, bos);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        });
    }

    public static void downloadFile(String url, File out) throws IOException {
        downloadToFile(url, out, null, null, MAX_ATTEMPTS);
    }

    /** Like {@link #downloadFile(String, File)}, but a failure is not tried again. For files that nothing depends on. */
    public static void downloadFileOnce(String url, File out) throws IOException {
        downloadToFile(url, out, null, null, 1);
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        downloadToFile(urlInput, outputFile, buffer, monitor, MAX_ATTEMPTS);
    }

    /**
     * Download a file: retry transient failures, write to a ".part" file and move it into place
     * only when everything has arrived.
     */
    private static void downloadToFile(String urlInput, File outputFile, @Nullable byte[] buffer,
                                       @Nullable Tools.DownloaderFeedback monitor, int maxAttempts) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);
        final byte[] transferBuffer = buffer != null ? buffer : new byte[65535];
        final File partFile = new File(outputFile.getParentFile(), outputFile.getName() + PART_SUFFIX);
        try {
            withRetries(urlInput, maxAttempts, () -> {
                transfer(urlInput, partFile, transferBuffer, monitor);
                return null;
            });
            moveIntoPlace(partFile, outputFile);
        } catch (IOException | RuntimeException e) {
            // Never leave half a file behind
            if(partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "Unable to delete the temporary file " + partFile);
            }
            throw e;
        }
    }

    /** One try to put the body of the URL into the file */
    private static void transfer(String urlInput, File target, byte[] buffer,
                                 @Nullable Tools.DownloaderFeedback monitor) throws IOException {
        HttpURLConnection conn = openChecked(urlInput);
        try {
            int expectedLength = conn.getContentLength();
            long received = 0;
            try (InputStream readStr = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(target)) {
                int current;
                while ((current = readStr.read(buffer)) != -1) {
                    received += current;
                    fos.write(buffer, 0, current);
                    if(monitor != null) monitor.updateProgress((int) received, expectedLength);
                }
            } catch (IOException e) {
                throw new IOException("Unable to download from " + urlInput, e);
            }
            // The connection can be closed by the other side in a way that looks like a normal end.
            // A body that is shorter than announced is a broken file, not a finished download.
            if(expectedLength >= 0 && received != expectedLength) {
                throw new IncompleteDownloadException(urlInput, received, expectedLength);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void moveIntoPlace(File partFile, File outputFile) throws IOException {
        if(partFile.renameTo(outputFile)) return;
        // Not every file system lets a rename replace an existing file
        if(outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Unable to replace " + outputFile.getAbsolutePath());
        }
        if(!partFile.renameTo(outputFile)) {
            throw new IOException("Unable to move " + partFile.getName() + " to " + outputFile.getAbsolutePath());
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && sha1Matches(file, sha1);
    }

    /**
     * Check the SHA-1 of a file.
     * @param file the file to check
     * @param expectedSha1 the SHA-1 that the file must have, as a hexadecimal string
     * @return true only if the file could be read and has the expected SHA-1. A file that
     *         cannot be read cannot be verified, so it is NOT a match.
     */
    public static boolean sha1Matches(File file, @Nullable String expectedSha1) {
        if(expectedSha1 == null) return false;
        try (InputStream is = new FileInputStream(file)) {
            String actualSha1 = new String(Hex.encodeHex(DigestUtils.sha1(is)));
            return actualSha1.equalsIgnoreCase(expectedSha1.trim());
        } catch (IOException e) {
            Log.w(TAG, "Unable to read " + file + " for the SHA-1 check, it counts as not verified", e);
            return false;
        }
    }

    /**
     * Run the download function until the file has the expected SHA-1.
     * @param outputFile the file that the download function writes
     * @param sha1 the expected SHA-1. If it is null, an existing file is accepted without a check.
     * @param downloadFunction the function that downloads the file
     * @throws SHA1VerificationException if the file still does not match after several downloads. The bad
     *         file is deleted then, so it can not be mistaken for an installed one.
     */
    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < MAX_SHA1_ATTEMPTS && !fileOkay){
            attempts++;
            result = downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
            if(!fileOkay) Log.w(TAG, "SHA-1 mismatch for " + outputFile.getName() + " (download " + attempts + "/" + MAX_SHA1_ATTEMPTS + ")");
        }
        if(!fileOkay) {
            if(outputFile.exists() && !outputFile.delete()) Log.w(TAG, "Unable to delete the damaged file " + outputFile);
            throw new SHA1VerificationException("SHA-1 verification of " + outputFile.getName()
                    + " failed after " + MAX_SHA1_ATTEMPTS + " download attempts");
        }
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        try {
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            urlConnection.setConnectTimeout(TIME_OUT);
            urlConnection.setReadTimeout(TIME_OUT);
            // A HEAD answer has no body, but the connection must keep its input enabled: the Java class
            // library refuses to even read the status code of a connection that has doInput=false
            urlConnection.setRequestMethod("HEAD");
            urlConnection.connect();
            int responseCode = urlConnection.getResponseCode();
            if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
            return -1;
        } finally {
            urlConnection.disconnect();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Connection and retry helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Open a connection to the URL and make sure that the server answered with a success status.
     * @throws HttpStatusException (or its NotFound variant) if the server answered with a failure
     */
    private static HttpURLConnection openChecked(String urlInput) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            int status = conn.getResponseCode();
            if(status >= 200 && status < 300) return conn;
            throw HttpStatusException.create(status, urlInput, parseRetryAfter(conn.getHeaderField("Retry-After")));
        } catch (IOException | RuntimeException e) {
            conn.disconnect();
            throw e;
        }
    }

    /** @return the Retry-After header in milliseconds, or -1 if it is missing or is not a number of seconds */
    static long parseRetryAfter(@Nullable String headerValue) {
        if(headerValue == null) return -1;
        try {
            long seconds = Long.parseLong(headerValue.trim());
            if(seconds < 0) return -1;
            return Math.min(seconds * 1000L, MAX_RETRY_DELAY_MILLIS);
        } catch (NumberFormatException e) {
            // The HTTP date form of the header is not worth the trouble
            return -1;
        }
    }

    private interface Attempt<T> {
        T run() throws IOException;
    }

    private static <T> T withRetries(String what, int maxAttempts, Attempt<T> attempt) throws IOException {
        int tries = 0;
        while (true) {
            tries++;
            try {
                return attempt.run();
            } catch (IOException e) {
                int allowedTries = maxAttemptsFor(e, maxAttempts);
                if(tries >= allowedTries || !isRetryable(e)) throw e;
                long delay = retryDelay(e, tries);
                Log.w(TAG, "Transient failure for " + what + " (try " + tries + " of " + allowedTries
                        + "), trying again in " + delay + " ms: " + e);
                sleep(delay);
            }
        }
    }

    /** Search a throwable and its causes for an instance of the type. The depth limit protects from cyclic chains. */
    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for(int depth = 0; throwable != null && depth < 8; depth++, throwable = throwable.getCause()) {
            if(type.isInstance(throwable)) return type.cast(throwable);
        }
        return null;
    }

    private static int maxAttemptsFor(IOException e, int maxAttempts) {
        // Most of the time "unknown host" means that there is no connection at all. Another try or two
        // is enough for a short network hiccup, more only delays the error message.
        if(findCause(e, UnknownHostException.class) != null) return Math.min(2, maxAttempts);
        return maxAttempts;
    }

    private static long retryDelay(IOException e, int tries) {
        long delay = Math.min(sRetryDelayMillis << (tries - 1), MAX_RETRY_DELAY_MILLIS);
        HttpStatusException statusException = findCause(e, HttpStatusException.class);
        // A server that tells how long to wait knows better
        if(statusException != null && statusException.retryAfterMillis > delay) {
            delay = statusException.retryAfterMillis;
        }
        return delay;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // The download was cancelled
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to try again");
        }
    }

    /**
     * Decide if asking again can help. Network trouble and overloaded servers: yes. A refusal, a missing
     * file, a full disk or a cancelled download: no.
     */
    static boolean isRetryable(Throwable failure) {
        // The depth limit protects from cyclic cause chains
        for(int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if(failure instanceof HttpStatusException) {
                return HttpStatusException.isTransient(((HttpStatusException) failure).statusCode);
            }
            if(failure instanceof HttpStatusException.NotFound) return false;
            if(failure instanceof SHA1VerificationException) return false;
            if(failure instanceof SocketTimeoutException) return true; // is an InterruptedIOException too
            if(failure instanceof InterruptedIOException) return false;
            if(failure instanceof FileNotFoundException) return false;
            if(failure instanceof UnknownHostException
                    || failure instanceof SocketException
                    || failure instanceof SSLException
                    || failure instanceof EOFException
                    || failure instanceof ProtocolException
                    || failure instanceof IncompleteDownloadException) {
                return true;
            }
            if(isDroppedConnectionMessage(failure.getMessage())) return true;
        }
        return false;
    }

    /**
     * Some network stacks report a connection that ended too early with a plain IOException and only the
     * wording tells what happened (Android's HTTP client: "unexpected end of stream", the JDK: "Premature EOF").
     */
    private static boolean isDroppedConnectionMessage(@Nullable String message) {
        if(message == null) return false;
        String lowerCase = message.toLowerCase(Locale.ROOT);
        return lowerCase.contains("unexpected end of stream")
                || lowerCase.contains("premature eof")
                || lowerCase.contains("stream was reset")
                || lowerCase.contains("connection reset")
                || lowerCase.contains("connection closed");
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }

    /** The connection ended before the whole file arrived */
    public static class IncompleteDownloadException extends IOException {
        public IncompleteDownloadException(String url, long received, long expected) {
            super("Incomplete download of " + url + ": got " + received + " of " + expected + " bytes");
        }
    }
}
