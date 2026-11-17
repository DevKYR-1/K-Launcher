package net.kdt.pojavlaunch.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.kdt.pojavlaunch.utils.TestHttpServer.Reply;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The download engine against a real (local) HTTP server: HTTP status handling, retries,
 * half downloaded files and SHA-1 verification.
 */
public class DownloadUtilsTest {
    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private long mOriginalRetryDelay;

    @Before
    public void shortenRetryDelay() {
        mOriginalRetryDelay = DownloadUtils.sRetryDelayMillis;
        DownloadUtils.sRetryDelayMillis = 1; // the tests must not wait for real back-off times
    }

    @After
    public void restoreRetryDelay() {
        DownloadUtils.sRetryDelayMillis = mOriginalRetryDelay;
        Thread.interrupted(); // a test that interrupts itself must not leak the flag
    }

    private static byte[] content(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i * 31 + 7);
        return data;
    }

    private File target(String name) {
        return new File(mTemp.getRoot(), name);
    }

    private static File partFileOf(File target) {
        return new File(target.getParentFile(), target.getName() + ".part");
    }

    private static void assertNothingLeftBehind(File target) {
        assertFalse("a half downloaded file must not stay", partFileOf(target).exists());
    }

    // ---------------------------------------------------------------- successful downloads

    @Test
    public void downloadFile_writesTheBody_andCreatesMissingFolders() throws Exception {
        byte[] body = content(200_000);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("a/b/c/file.bin");
            DownloadUtils.downloadFile(server.url("/file.bin"), target);

            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertNothingLeftBehind(target);
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    public void downloadFile_replacesAnExistingFile() throws Exception {
        byte[] body = content(1000);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("file.bin");
            Files.write(target.toPath(), "old and damaged".getBytes(StandardCharsets.UTF_8));
            DownloadUtils.downloadFile(server.url("/file.bin"), target);
            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void downloadFileMonitored_reportsProgressUntilTheEnd() throws Exception {
        byte[] body = content(300_000);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("file.bin");
            List<int[]> reports = new ArrayList<>();
            DownloadUtils.downloadFileMonitored(server.url("/file.bin"), target, new byte[8192],
                    (curr, max) -> reports.add(new int[]{curr, max}));

            assertFalse(reports.isEmpty());
            int[] last = reports.get(reports.size() - 1);
            assertEquals(body.length, last[0]);
            assertEquals(body.length, last[1]);
            for (int i = 1; i < reports.size(); i++) {
                assertTrue("progress never goes back", reports.get(i)[0] >= reports.get(i - 1)[0]);
            }
        }
    }

    @Test
    public void downloadString_returnsTheBody() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                Reply.ok("{\"id\":\"1.20.1\"}".getBytes(StandardCharsets.UTF_8)))) {
            assertEquals("{\"id\":\"1.20.1\"}", DownloadUtils.downloadString(server.url("/v.json")));
        }
    }

    @Test
    public void getContentLength_usesHead_andIsMinusOneForAFailure() throws Exception {
        byte[] body = content(4321);
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                path.equals("/there") ? Reply.ok(body) : Reply.status(404))) {
            assertEquals(4321, DownloadUtils.getContentLength(server.url("/there")));
            assertEquals(-1, DownloadUtils.getContentLength(server.url("/missing")));
            assertEquals("HEAD", server.methods().get(0));
        }
    }

    // ---------------------------------------------------------------- HTTP statuses

    @Test
    public void notFound_isAFileNotFoundException_andIsNotRetried() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(404))) {
            File target = target("file.bin");
            try {
                DownloadUtils.downloadFile(server.url("/file.bin"), target);
                fail("a missing file is an error");
            } catch (FileNotFoundException e) {
                // callers (mirror fallback, Forge download...) rely on this type
                assertTrue(e instanceof HttpStatusException.NotFound);
                assertEquals(404, HttpStatusException.statusOf(e));
            }
            assertEquals("asking again cannot help", 1, server.requestCount());
            assertFalse(target.exists());
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void notFound_ofAString_isAFileNotFoundException_too() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(404))) {
            try {
                DownloadUtils.downloadString(server.url("/version.json"));
                fail();
            } catch (FileNotFoundException expected) {
                assertEquals(1, server.requestCount());
            }
        }
    }

    @Test
    public void forbidden_isNotRetried() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(403))) {
            File target = target("file.bin");
            try {
                DownloadUtils.downloadFile(server.url("/file.bin"), target);
                fail();
            } catch (HttpStatusException e) {
                assertEquals(403, e.statusCode);
            }
            assertEquals(1, server.requestCount());
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void serverError_isRetried_andTheDownloadSucceedsWhenTheServerRecovers() throws Exception {
        byte[] body = content(5000);
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                n < 3 ? Reply.status(503) : Reply.ok(body))) {
            File target = target("file.bin");
            DownloadUtils.downloadFile(server.url("/file.bin"), target);
            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertEquals(3, server.requestCount());
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void serverError_thatNeverGoesAway_endsAfterTheMaximumNumberOfTries() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(500))) {
            File target = target("file.bin");
            try {
                DownloadUtils.downloadFile(server.url("/file.bin"), target);
                fail();
            } catch (HttpStatusException e) {
                assertEquals(500, e.statusCode);
            }
            assertEquals(DownloadUtils.MAX_ATTEMPTS, server.requestCount());
            assertFalse(target.exists());
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void tooManyRequests_waitsAsLongAsTheServerAsks() throws Exception {
        byte[] body = content(100);
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                n == 1 ? Reply.status(429).header("Retry-After", "1") : Reply.ok(body))) {
            File target = target("file.bin");
            long start = System.nanoTime();
            DownloadUtils.downloadFile(server.url("/file.bin"), target);
            long waitedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertEquals(2, server.requestCount());
            assertTrue("Retry-After: 1 means at least a second, waited " + waitedMillis + " ms", waitedMillis >= 900);
        }
    }

    @Test
    public void downloadFileOnce_doesNotRetry() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(503))) {
            try {
                DownloadUtils.downloadFileOnce(server.url("/head.png"), target("head.png"));
                fail();
            } catch (HttpStatusException e) {
                assertEquals(503, e.statusCode);
            }
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    public void downloadString_retriesATransientFailure() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                n == 1 ? Reply.status(502) : Reply.ok("ok".getBytes(StandardCharsets.UTF_8)))) {
            assertEquals("ok", DownloadUtils.downloadString(server.url("/manifest.json")));
            assertEquals(2, server.requestCount());
        }
    }

    // ---------------------------------------------------------------- broken connections

    @Test
    public void aBodyThatEndsTooEarly_isRetried() throws Exception {
        byte[] body = content(50_000);
        try (TestHttpServer server = new TestHttpServer((path, n) ->
                n == 1 ? Reply.ok(body).cutAfter(12_345) : Reply.ok(body))) {
            File target = target("file.bin");
            DownloadUtils.downloadFile(server.url("/file.bin"), target);
            assertArrayEquals("the complete file, not the first half", body, Files.readAllBytes(target.toPath()));
            assertEquals(2, server.requestCount());
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void aBodyThatAlwaysEndsTooEarly_neverLeavesAHalfFile_andKeepsTheOldOne() throws Exception {
        byte[] body = content(50_000);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body).cutAfter(100))) {
            File target = target("file.bin");
            Files.write(target.toPath(), "the previous complete file".getBytes(StandardCharsets.UTF_8));
            try {
                DownloadUtils.downloadFile(server.url("/file.bin"), target);
                fail("an incomplete body is not a finished download");
            } catch (IOException e) {
                assertNotNull(e.getMessage());
            }
            assertEquals(DownloadUtils.MAX_ATTEMPTS, server.requestCount());
            assertEquals("a failed download must not destroy what was there",
                    "the previous complete file", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            assertNothingLeftBehind(target);
        }
    }

    @Test
    public void aCancelledDownload_stopsInsteadOfWaitingForTheNextTry() throws Exception {
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.status(503))) {
            File target = target("file.bin");
            Thread.currentThread().interrupt();
            try {
                DownloadUtils.downloadFile(server.url("/file.bin"), target);
                fail();
            } catch (InterruptedIOException expected) {
                assertTrue("the cancellation stays visible to the caller", Thread.currentThread().isInterrupted());
            }
            assertEquals(1, server.requestCount());
            assertNothingLeftBehind(target);
        }
    }

    // ---------------------------------------------------------------- SHA-1

    private static String sha1(byte[] data) {
        return DigestUtils.sha1Hex(data);
    }

    @Test
    public void ensureSha1_aGoodFileIsNotDownloadedAgain() throws Exception {
        byte[] body = content(2048);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("lib.jar");
            Files.write(target.toPath(), body);
            AtomicInteger calls = new AtomicInteger();

            DownloadUtils.ensureSha1(target, sha1(body), () -> {
                calls.incrementAndGet();
                DownloadUtils.downloadFile(server.url("/lib.jar"), target);
                return null;
            });

            assertEquals(0, calls.get());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    public void ensureSha1_aDamagedFileIsDownloadedAgain() throws Exception {
        byte[] body = content(4096);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("lib.jar");
            Files.write(target.toPath(), "corrupted".getBytes(StandardCharsets.UTF_8));

            DownloadUtils.ensureSha1(target, sha1(body), () -> {
                DownloadUtils.downloadFile(server.url("/lib.jar"), target);
                return null;
            });

            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    public void ensureSha1_aMissingFileIsDownloaded() throws Exception {
        byte[] body = content(4096);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File target = target("new/lib.jar");
            DownloadUtils.ensureSha1(target, sha1(body), () -> {
                DownloadUtils.downloadFile(server.url("/lib.jar"), target);
                return null;
            });
            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
        }
    }

    @Test
    public void ensureSha1_aServerThatSendsTheWrongFile_endsWithAVerificationError_andTheBadFileIsGone() throws Exception {
        byte[] expected = content(4096);
        byte[] wrong = content(4097);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(wrong))) {
            File target = target("lib.jar");
            try {
                DownloadUtils.ensureSha1(target, sha1(expected), () -> {
                    DownloadUtils.downloadFile(server.url("/lib.jar"), target);
                    return null;
                });
                fail("a file with the wrong SHA-1 must never be accepted");
            } catch (DownloadUtils.SHA1VerificationException e) {
                assertTrue(e.getMessage().contains("lib.jar"));
            }
            assertEquals(DownloadUtils.MAX_SHA1_ATTEMPTS, server.requestCount());
            assertFalse("the damaged file must not be mistaken for an installed one", target.exists());
        }
    }

    @Test
    public void ensureSha1_aServerThatRecovers_givesTheRightFileOnTheSecondDownload() throws Exception {
        byte[] right = content(4096);
        byte[] wrong = content(10);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(n == 1 ? wrong : right))) {
            File target = target("lib.jar");
            DownloadUtils.ensureSha1(target, sha1(right), () -> {
                DownloadUtils.downloadFile(server.url("/lib.jar"), target);
                return null;
            });
            assertArrayEquals(right, Files.readAllBytes(target.toPath()));
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    public void ensureSha1_withoutAHash_keepsAnExistingFile_andDownloadsAMissingOne() throws Exception {
        byte[] body = content(64);
        try (TestHttpServer server = new TestHttpServer((path, n) -> Reply.ok(body))) {
            File existing = target("existing.jar");
            Files.write(existing.toPath(), "whatever".getBytes(StandardCharsets.UTF_8));
            DownloadUtils.ensureSha1(existing, null, () -> {
                DownloadUtils.downloadFile(server.url("/x"), existing);
                return null;
            });
            assertEquals(0, server.requestCount());

            File missing = target("missing.jar");
            DownloadUtils.ensureSha1(missing, null, () -> {
                DownloadUtils.downloadFile(server.url("/x"), missing);
                return null;
            });
            assertArrayEquals(body, Files.readAllBytes(missing.toPath()));
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    public void sha1Matches_ignoresCase_andSurroundingSpaces() throws Exception {
        byte[] body = content(100);
        File file = target("f.bin");
        Files.write(file.toPath(), body);
        String hash = sha1(body);
        assertTrue(DownloadUtils.sha1Matches(file, hash));
        assertTrue(DownloadUtils.sha1Matches(file, hash.toUpperCase()));
        assertTrue(DownloadUtils.sha1Matches(file, " " + hash + "\n"));
        assertFalse(DownloadUtils.sha1Matches(file, sha1(content(101))));
    }

    @Test
    public void sha1Matches_aFileThatCannotBeReadIsNotVerified() throws Exception {
        // A check that could not be done must never count as a pass
        assertFalse(DownloadUtils.sha1Matches(target("does-not-exist.bin"), sha1(content(1))));
        assertFalse(DownloadUtils.sha1Matches(mTemp.newFolder("a-folder"), sha1(content(1))));
        File file = target("f.bin");
        Files.write(file.toPath(), content(10));
        assertFalse("no expected hash, nothing to compare with", DownloadUtils.sha1Matches(file, null));
    }

    // ---------------------------------------------------------------- helpers

    @Test
    public void parseRetryAfter_understandsSeconds_andIgnoresTheRest() {
        assertEquals(-1, DownloadUtils.parseRetryAfter(null));
        assertEquals(5000, DownloadUtils.parseRetryAfter("5"));
        assertEquals(7000, DownloadUtils.parseRetryAfter(" 7 "));
        assertEquals(0, DownloadUtils.parseRetryAfter("0"));
        assertEquals(-1, DownloadUtils.parseRetryAfter("-3"));
        assertEquals(-1, DownloadUtils.parseRetryAfter("soon"));
        assertEquals(-1, DownloadUtils.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals("a server must not be able to park the launcher for an hour",
                30_000, DownloadUtils.parseRetryAfter("3600"));
    }

    @Test
    public void isRetryable_networkTroubleAndOverloadedServers_yes() {
        assertTrue(DownloadUtils.isRetryable(new java.net.SocketTimeoutException("timeout")));
        assertTrue(DownloadUtils.isRetryable(new java.net.UnknownHostException("resources.download.minecraft.net")));
        assertTrue(DownloadUtils.isRetryable(new java.net.ConnectException("refused")));
        assertTrue(DownloadUtils.isRetryable(new java.net.SocketException("Connection reset")));
        assertTrue(DownloadUtils.isRetryable(new javax.net.ssl.SSLException("handshake")));
        assertTrue(DownloadUtils.isRetryable(new java.io.EOFException()));
        assertTrue(DownloadUtils.isRetryable(new java.net.ProtocolException("unexpected end of stream")));
        assertTrue(DownloadUtils.isRetryable(new IOException("unexpected end of stream on https://x")));
        assertTrue(DownloadUtils.isRetryable(new IOException("Premature EOF")));
        assertTrue(DownloadUtils.isRetryable(new DownloadUtils.IncompleteDownloadException("u", 1, 2)));
        assertTrue("a wrapped cause counts",
                DownloadUtils.isRetryable(new IOException("Unable to download from x", new java.net.SocketTimeoutException())));
        for (int status : new int[]{408, 425, 429, 500, 502, 503, 504, 599}) {
            assertTrue("HTTP " + status, DownloadUtils.isRetryable(HttpStatusException.create(status, "u", -1)));
        }
    }

    @Test
    public void isRetryable_refusalsAndLocalProblems_no() {
        for (int status : new int[]{400, 401, 403, 404, 410, 451, 501, 505}) {
            assertFalse("HTTP " + status, DownloadUtils.isRetryable(HttpStatusException.create(status, "u", -1)));
        }
        assertFalse(DownloadUtils.isRetryable(new IOException("Unable to download from x", HttpStatusException.create(404, "u", -1))));
        assertFalse(DownloadUtils.isRetryable(new DownloadUtils.SHA1VerificationException("bad")));
        assertFalse(DownloadUtils.isRetryable(new FileNotFoundException("/data/x (Permission denied)")));
        assertFalse(DownloadUtils.isRetryable(new IOException("No space left on device")));
        assertFalse(DownloadUtils.isRetryable(new InterruptedIOException("cancelled")));
        assertFalse(DownloadUtils.isRetryable(new IOException("boom")));
    }

    @Test
    public void isRetryable_aCyclicCauseChainDoesNotHang() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertFalse(DownloadUtils.isRetryable(a));
    }
}
