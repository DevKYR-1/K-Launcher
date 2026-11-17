package net.kdt.pojavlaunch.utils;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A download server answered with an HTTP status that is not a success.
 * The status code is kept so that the launcher can decide whether trying again makes sense
 * (HTTP 429/5xx) or not (HTTP 403/404), and can tell the user what really happened.
 */
public class HttpStatusException extends IOException {
    private static final long serialVersionUID = 4160343233712011064L;

    /** The HTTP status code of the answer, for example 403 */
    public final int statusCode;
    /** The URL that was requested */
    public final String url;
    /** The value of the Retry-After header converted to milliseconds, or -1 if the server did not send it */
    public final long retryAfterMillis;

    public HttpStatusException(int statusCode, String url, long retryAfterMillis) {
        super("Server returned HTTP " + statusCode + " for " + url);
        this.statusCode = statusCode;
        this.url = url;
        this.retryAfterMillis = retryAfterMillis;
    }

    /**
     * "Not found" answers (HTTP 404 and 410). This is a FileNotFoundException on purpose: that is
     * what HttpURLConnection has always thrown for them, and the mirror fallback as well as
     * the Forge installer download rely on it.
     */
    public static class NotFound extends FileNotFoundException {
        private static final long serialVersionUID = -8120143064329418871L;
        public final int statusCode;
        public final String url;

        public NotFound(int statusCode, String url) {
            super("Server returned HTTP " + statusCode + " for " + url);
            this.statusCode = statusCode;
            this.url = url;
        }
    }

    /**
     * Create the exception that describes a failure status.
     * @param statusCode the HTTP status code
     * @param url the requested URL
     * @param retryAfterMillis the Retry-After value in milliseconds or -1
     * @return NotFound for 404/410, HttpStatusException for everything else
     */
    public static IOException create(int statusCode, String url, long retryAfterMillis) {
        if(statusCode == 404 || statusCode == 410) return new NotFound(statusCode, url);
        return new HttpStatusException(statusCode, url, retryAfterMillis);
    }

    /**
     * Whether it makes sense to ask the server again after this status: the server is overloaded
     * or having a problem right now (timeout, too many requests, 5xx), it did not refuse
     * the request itself.
     */
    public static boolean isTransient(int statusCode) {
        if(statusCode == 408 || statusCode == 425 || statusCode == 429) return true;
        // 501 and 505 will not get better by waiting
        return statusCode >= 500 && statusCode != 501 && statusCode != 505;
    }

    /**
     * Search a throwable and its causes for an HTTP status.
     * @return the status code or -1 if the failure did not come from an HTTP answer.
     */
    public static int statusOf(Throwable throwable) {
        // The depth limit protects from (malicious or buggy) cyclic cause chains
        for(int depth = 0; throwable != null && depth < 8; depth++) {
            if(throwable instanceof HttpStatusException) return ((HttpStatusException) throwable).statusCode;
            if(throwable instanceof NotFound) return ((NotFound) throwable).statusCode;
            throwable = throwable.getCause();
        }
        return -1;
    }
}
