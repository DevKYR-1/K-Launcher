package net.kdt.pojavlaunch.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LauncherLogTest {
    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void write_keepsTagMessageTimeAndStackTrace() throws Exception {
        File log = new File(mTemp.getRoot(), "game/launcher-log.txt");
        LauncherLog.write(log, "Download", "Preparing 1.20.1 failed", new IOException("Unable to download from x"));

        String text = read(log);
        assertTrue(text.contains("[Download] Preparing 1.20.1 failed"));
        assertTrue("the stack trace is what a bug report needs", text.contains("java.io.IOException: Unable to download from x"));
        assertTrue(text.contains("\tat "));
        assertTrue("starts with a time stamp", text.matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\[Download].*"));
    }

    @Test
    public void write_appendsInsteadOfReplacing() throws Exception {
        File log = new File(mTemp.getRoot(), "launcher-log.txt");
        LauncherLog.write(log, "A", "first", null);
        LauncherLog.write(log, "B", "second", null);

        String text = read(log);
        assertTrue(text.indexOf("first") >= 0 && text.indexOf("second") > text.indexOf("first"));
    }

    @Test
    public void write_neverGrowsWithoutLimit() throws Exception {
        File log = new File(mTemp.getRoot(), "launcher-log.txt");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100_000; i++) big.append('x');
        for (int i = 0; i < 20; i++) LauncherLog.write(log, "T", big.toString(), null); // 2 MB in total

        File old = new File(mTemp.getRoot(), "launcher-log.old.txt");
        assertTrue("the full file is moved aside", old.exists());
        assertTrue("the current file stays small: " + log.length(), log.length() < 1024 * 1024);
        assertTrue(old.length() < 1024 * 1024);
    }

    @Test
    public void write_neverThrows_whenTheLogCannotBeWritten() throws Exception {
        // The parent of the log is a regular file, so the log can not exist there
        File notAFolder = mTemp.newFile("not-a-folder");
        File log = new File(notAFolder, "launcher-log.txt");
        LauncherLog.write(log, "T", "message", new IllegalStateException("x"));
        assertFalse(log.exists());
        assertEquals(0, notAFolder.length());
    }
}
