package net.kdt.pojavlaunch.utils;

import static net.kdt.pojavlaunch.utils.DownloadErrors.Kind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.kdt.pojavlaunch.tasks.DownloadPreconditionException;
import net.kdt.pojavlaunch.tasks.LocalAccountDownloadException;

import org.junit.Test;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

/** Every failure of a download has to end up as a message that tells the user what is wrong */
public class DownloadErrorsTest {

    private static IOException http(int status) {
        return HttpStatusException.create(status, "https://example.invalid/file", -1);
    }

    @Test
    public void localAccount_hasItsOwnKind_evenWhenWrapped() {
        LocalAccountDownloadException local = new LocalAccountDownloadException(3, "client.jar", false);
        assertEquals(Kind.LOCAL_ACCOUNT, DownloadErrors.classify(local));
        assertEquals(Kind.LOCAL_ACCOUNT, DownloadErrors.classify(new RuntimeException("task failed", local)));
        assertTrue("the technical message names the file for the log", local.getMessage().contains("client.jar"));
        assertTrue(local.getMessage().contains("3"));
    }

    @Test
    public void preconditions_areTold_whatTheyAre() {
        assertEquals(Kind.JAVA_RUNTIME, DownloadErrors.classify(
                new DownloadPreconditionException(DownloadPreconditionException.Reason.JAVA_RUNTIME, "no jre")));
        assertEquals(Kind.UNSUPPORTED_VERSION, DownloadErrors.classify(
                new DownloadPreconditionException(DownloadPreconditionException.Reason.UNSUPPORTED_VERSION, "no json")));
        assertEquals(Kind.BUSY, DownloadErrors.classify(
                new DownloadPreconditionException(DownloadPreconditionException.Reason.BUSY, "running")));
    }

    @Test
    public void httpStatuses_areToldApart() {
        assertEquals(Kind.FILE_NOT_FOUND, DownloadErrors.classify(http(404)));
        assertEquals(Kind.FILE_NOT_FOUND, DownloadErrors.classify(http(410)));
        assertEquals(Kind.ACCESS_DENIED, DownloadErrors.classify(http(401)));
        assertEquals(Kind.ACCESS_DENIED, DownloadErrors.classify(http(403)));
        assertEquals(Kind.RATE_LIMITED, DownloadErrors.classify(http(429)));
        assertEquals(Kind.SERVER_ERROR, DownloadErrors.classify(http(500)));
        assertEquals(Kind.SERVER_ERROR, DownloadErrors.classify(http(503)));
        assertEquals(Kind.HTTP_OTHER, DownloadErrors.classify(http(418)));
    }

    @Test
    public void httpStatus_insideAWrapper_isStillFound() {
        IOException wrapped = new IOException("Unable to download from x", http(403));
        assertEquals(Kind.ACCESS_DENIED, DownloadErrors.classify(wrapped));
        assertEquals(403, HttpStatusException.statusOf(new RuntimeException(wrapped)));
    }

    @Test
    public void networkTrouble_isNoInternet() {
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new UnknownHostException("resources.download.minecraft.net")));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new SocketTimeoutException("timeout")));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new ConnectException("refused")));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new SSLHandshakeException("handshake")));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new EOFException()));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(new DownloadUtils.IncompleteDownloadException("u", 5, 10)));
        assertEquals(Kind.NO_INTERNET, DownloadErrors.classify(
                new IOException("Unable to download from u", new UnknownHostException("u"))));
    }

    @Test
    public void failedVerification_isNamedAsSuch() {
        assertEquals(Kind.VERIFICATION, DownloadErrors.classify(new DownloadUtils.SHA1VerificationException("bad")));
        assertEquals(Kind.VERIFICATION, DownloadErrors.classify(new RuntimeException(new DownloadUtils.SHA1VerificationException("bad"))));
    }

    @Test
    public void storageProblems_areNotBlamedOnTheNetwork() {
        assertEquals(Kind.NO_SPACE, DownloadErrors.classify(new IOException("write failed: ENOSPC (No space left on device)")));
        assertEquals(Kind.STORAGE, DownloadErrors.classify(new IOException("open failed: EACCES (Permission denied)")));
        assertEquals(Kind.STORAGE, DownloadErrors.classify(new IOException("open failed: EROFS (Read-only file system)")));
        // A local file that cannot be opened is a storage problem, a missing file on the server is not
        assertEquals(Kind.STORAGE, DownloadErrors.classify(new FileNotFoundException("/storage/x/y.jar (No such file or directory)")));
    }

    @Test
    public void anythingElse_isUnknown_andNeverCrashesTheClassifier() {
        assertEquals(Kind.UNKNOWN, DownloadErrors.classify(null));
        assertEquals(Kind.UNKNOWN, DownloadErrors.classify(new IllegalStateException("boom")));
        // A cyclic chain of causes must end
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertEquals(Kind.UNKNOWN, DownloadErrors.classify(a));
    }

    @Test
    public void technicalSummary_isOneShortLine_withoutAStackTrace() {
        Exception root = new IOException("root cause\nwith a second line");
        Exception failure = new RuntimeException("top level", new IllegalStateException("middle", root));
        String summary = DownloadErrors.technicalSummary(failure);

        assertFalse("one line", summary.contains("\n"));
        assertFalse("no stack trace", summary.contains("\tat ") || summary.contains(" at net.kdt"));
        assertTrue(summary.contains("RuntimeException: top level"));
        assertTrue("the root cause is the interesting part", summary.contains("IOException: root cause with a second line"));
    }

    @Test
    public void technicalSummary_isShort_evenForAHugeMessage() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('x');
        String summary = DownloadErrors.technicalSummary(new IOException(huge.toString()));
        assertTrue(summary.length() <= 401);
    }

    @Test
    public void technicalSummary_handlesNoMessageAndNoFailure() {
        assertEquals("", DownloadErrors.technicalSummary(null));
        assertEquals("IOException", DownloadErrors.technicalSummary(new IOException()));
    }
}
