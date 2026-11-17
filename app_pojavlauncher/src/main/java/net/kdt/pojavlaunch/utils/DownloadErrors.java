package net.kdt.pojavlaunch.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.tasks.DownloadPreconditionException;
import net.kdt.pojavlaunch.tasks.LocalAccountDownloadException;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Turns the exception of a failed download into a message that a user can understand, and into
 * a one line technical summary. The stack trace is not part of either: it goes to the launcher log
 * ({@link LauncherLog}) and stays available behind the "Show more" button of the error dialog.
 */
public final class DownloadErrors {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_SUMMARY_LENGTH = 400;

    private DownloadErrors() {}

    /** What went wrong, in terms that matter to the user */
    public enum Kind {
        LOCAL_ACCOUNT,
        JAVA_RUNTIME,
        UNSUPPORTED_VERSION,
        BUSY,
        VERIFICATION,
        FILE_NOT_FOUND,
        ACCESS_DENIED,
        RATE_LIMITED,
        SERVER_ERROR,
        HTTP_OTHER,
        NO_INTERNET,
        NO_SPACE,
        STORAGE,
        UNKNOWN
    }

    /** Find out what kind of failure this is, looking at the exception and its causes */
    @NonNull
    public static Kind classify(Throwable failure) {
        for(int depth = 0; failure != null && depth < MAX_DEPTH; depth++, failure = failure.getCause()) {
            if(failure instanceof LocalAccountDownloadException) return Kind.LOCAL_ACCOUNT;
            if(failure instanceof DownloadPreconditionException) {
                switch (((DownloadPreconditionException) failure).reason) {
                    case JAVA_RUNTIME: return Kind.JAVA_RUNTIME;
                    case UNSUPPORTED_VERSION: return Kind.UNSUPPORTED_VERSION;
                    case BUSY: return Kind.BUSY;
                }
            }
            if(failure instanceof DownloadUtils.SHA1VerificationException) return Kind.VERIFICATION;
            if(failure instanceof HttpStatusException.NotFound) return Kind.FILE_NOT_FOUND;
            if(failure instanceof HttpStatusException) {
                return classifyStatus(((HttpStatusException) failure).statusCode);
            }
            if(failure instanceof UnknownHostException
                    || failure instanceof SocketTimeoutException
                    || failure instanceof SocketException
                    || failure instanceof SSLException
                    || failure instanceof EOFException
                    || failure instanceof ProtocolException
                    || failure instanceof DownloadUtils.IncompleteDownloadException) {
                return Kind.NO_INTERNET;
            }
            String message = failure.getMessage();
            if(message != null) {
                String lowerCase = message.toLowerCase(Locale.ROOT);
                if(lowerCase.contains("enospc") || lowerCase.contains("no space left")) return Kind.NO_SPACE;
                if(lowerCase.contains("eacces") || lowerCase.contains("erofs")
                        || lowerCase.contains("permission denied") || lowerCase.contains("read-only file system")) {
                    return Kind.STORAGE;
                }
            }
            // A file that cannot be opened here is a local file, the HTTP "not found" was handled above
            if(failure instanceof FileNotFoundException) return Kind.STORAGE;
        }
        return Kind.UNKNOWN;
    }

    private static Kind classifyStatus(int statusCode) {
        if(statusCode == 401 || statusCode == 403) return Kind.ACCESS_DENIED;
        if(statusCode == 429) return Kind.RATE_LIMITED;
        if(statusCode >= 500) return Kind.SERVER_ERROR;
        return Kind.HTTP_OTHER;
    }

    /** @return the string resource with the message for this kind of failure */
    @StringRes
    public static int messageResource(Kind kind) {
        switch (kind) {
            case LOCAL_ACCOUNT: return R.string.dl_err_local_short;
            case JAVA_RUNTIME: return R.string.dl_err_java_runtime;
            case UNSUPPORTED_VERSION: return R.string.dl_err_unsupported_version;
            case BUSY: return R.string.tasks_ongoing;
            case VERIFICATION: return R.string.dl_err_verification;
            case FILE_NOT_FOUND: return R.string.dl_err_file_unavailable;
            case ACCESS_DENIED: return R.string.dl_err_access_denied;
            case RATE_LIMITED: return R.string.dl_err_rate_limited;
            case SERVER_ERROR: return R.string.dl_err_server;
            case HTTP_OTHER: return R.string.dl_err_http_other;
            case NO_INTERNET: return R.string.dl_err_no_internet;
            case NO_SPACE: return R.string.dl_err_no_space;
            case STORAGE: return R.string.dl_err_storage;
            case UNKNOWN:
            default: return R.string.dl_err_generic;
        }
    }

    /**
     * Describe a failed download for the user: what happened and what to do, and one line of technical details.
     * It never contains a stack trace.
     */
    @NonNull
    public static String describe(Context context, Throwable failure) {
        Kind kind = classify(failure);
        int statusCode = HttpStatusException.statusOf(failure);
        String message;
        if(kind == Kind.SERVER_ERROR || kind == Kind.HTTP_OTHER) {
            message = context.getString(messageResource(kind), statusCode);
        } else {
            message = context.getString(messageResource(kind));
        }
        return message + "\n\n" + context.getString(R.string.dl_err_details, technicalSummary(failure));
    }

    /** A single line with the exception types and messages, without a stack trace */
    @NonNull
    public static String technicalSummary(Throwable failure) {
        if(failure == null) return "";
        Throwable root = failure;
        for(int depth = 0; root.getCause() != null && depth < MAX_DEPTH; depth++) root = root.getCause();
        StringBuilder summary = new StringBuilder(oneLine(failure));
        if(root != failure) summary.append(" (cause: ").append(oneLine(root)).append(')');
        if(summary.length() > MAX_SUMMARY_LENGTH) {
            summary.setLength(MAX_SUMMARY_LENGTH);
            summary.append('…');
        }
        return summary.toString();
    }

    private static String oneLine(Throwable throwable) {
        String message = throwable.getMessage();
        String name = throwable.getClass().getSimpleName();
        return message == null ? name : name + ": " + message.replace('\n', ' ');
    }
}
