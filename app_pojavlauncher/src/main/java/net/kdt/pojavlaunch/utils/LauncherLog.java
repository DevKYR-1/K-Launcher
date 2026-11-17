package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the technical details of launcher side problems (failed downloads, failed logins) in a text file,
 * so that the user sees a short understandable message while the stack trace is still available
 * for a bug report. The file is "launcher-log.txt" in the game folder, next to the game's "latestlog.txt".
 * It never grows over about 1 MB: when it is too big, it is moved to "launcher-log.old.txt".
 */
public final class LauncherLog {
    private static final String TAG = "LauncherLog";
    private static final String FILE_NAME = "launcher-log.txt";
    private static final String OLD_FILE_NAME = "launcher-log.old.txt";
    private static final long MAX_SIZE = 512 * 1024;
    private static final Object sLock = new Object();

    private LauncherLog() {}

    /** The file that the log is written to */
    public static File getLogFile() {
        return new File(Tools.DIR_GAME_HOME, FILE_NAME);
    }

    /**
     * Write a problem to logcat and to the launcher log file. Never throws.
     * Never pass secrets (tokens, passwords) here.
     * @param tag the area that has the problem, like "Download"
     * @param message what happened
     * @param throwable the cause with its stack trace, if there is one
     */
    public static void error(String tag, String message, @Nullable Throwable throwable) {
        Log.e(tag, message, throwable);
        write(getLogFile(), tag, message, throwable);
    }

    /** Write a note to logcat and to the launcher log file. Never throws. */
    public static void info(String tag, String message) {
        Log.i(tag, message);
        write(getLogFile(), tag, message, null);
    }

    static void write(File file, String tag, String message, @Nullable Throwable throwable) {
        synchronized (sLock) {
            try {
                File parent = file.getParentFile();
                if(parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
                if(file.length() > MAX_SIZE) {
                    File oldFile = new File(parent, OLD_FILE_NAME);
                    if(oldFile.exists() && !oldFile.delete()) return;
                    if(!file.renameTo(oldFile)) return;
                }
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                    writer.write(time + " [" + tag + "] " + message + "\n");
                    if(throwable != null) {
                        StringWriter stackTrace = new StringWriter();
                        try (PrintWriter printWriter = new PrintWriter(stackTrace)) {
                            throwable.printStackTrace(printWriter);
                        }
                        writer.write(stackTrace.toString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Logging must never break the thing that is being logged
                Log.w(TAG, "Unable to write the launcher log", e);
            }
        }
    }
}
