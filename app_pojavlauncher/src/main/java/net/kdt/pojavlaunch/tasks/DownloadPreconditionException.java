package net.kdt.pojavlaunch.tasks;

import java.io.IOException;

/**
 * The launcher could not even start downloading, because something that the download needs is not
 * available: the Java runtime for the version, or the version itself.
 * {@link net.kdt.pojavlaunch.utils.DownloadErrors} turns it into a message that the user understands.
 */
public class DownloadPreconditionException extends IOException {
    private static final long serialVersionUID = 6240591786139105731L;

    public enum Reason {
        /** No Java runtime that can run the version could be found or unpacked */
        JAVA_RUNTIME,
        /** The version is unknown, or its version file is missing, damaged and cannot be downloaded */
        UNSUPPORTED_VERSION,
        /** Another game download is already running, two of them must not write the same files */
        BUSY
    }

    public final Reason reason;

    public DownloadPreconditionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DownloadPreconditionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
