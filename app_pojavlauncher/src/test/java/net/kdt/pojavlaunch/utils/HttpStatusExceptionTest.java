package net.kdt.pojavlaunch.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;

public class HttpStatusExceptionTest {

    @Test
    public void create_keepsNotFoundAsAFileNotFoundException() {
        // HttpURLConnection has always thrown FileNotFoundException for 404/410 and code relies on it
        for (int status : new int[]{404, 410}) {
            IOException exception = HttpStatusException.create(status, "https://x/y", -1);
            assertTrue("HTTP " + status, exception instanceof FileNotFoundException);
            assertTrue(exception instanceof HttpStatusException.NotFound);
            assertEquals(status, HttpStatusException.statusOf(exception));
        }
    }

    @Test
    public void create_keepsTheStatusAndTheRetryAfterOfEverythingElse() {
        IOException exception = HttpStatusException.create(429, "https://x/y", 4000);
        assertTrue(exception instanceof HttpStatusException);
        HttpStatusException status = (HttpStatusException) exception;
        assertEquals(429, status.statusCode);
        assertEquals("https://x/y", status.url);
        assertEquals(4000, status.retryAfterMillis);
        assertTrue(status.getMessage().contains("429"));
        assertTrue(status.getMessage().contains("https://x/y"));
    }

    @Test
    public void isTransient_separatesOverloadFromRefusal() {
        for (int status : new int[]{408, 425, 429, 500, 502, 503, 504, 520, 599}) {
            assertTrue("HTTP " + status, HttpStatusException.isTransient(status));
        }
        for (int status : new int[]{200, 301, 400, 401, 403, 404, 410, 418, 451, 501, 505}) {
            assertFalse("HTTP " + status, HttpStatusException.isTransient(status));
        }
    }

    @Test
    public void statusOf_looksThroughWrappers_andGivesUpOnEverythingElse() {
        IOException cause = HttpStatusException.create(503, "u", -1);
        assertEquals(503, HttpStatusException.statusOf(new RuntimeException(new IOException("wrapped", cause))));
        assertEquals(-1, HttpStatusException.statusOf(new IOException("no status here")));
        assertEquals(-1, HttpStatusException.statusOf(null));

        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertEquals("a cyclic chain must end", -1, HttpStatusException.statusOf(a));
    }
}
