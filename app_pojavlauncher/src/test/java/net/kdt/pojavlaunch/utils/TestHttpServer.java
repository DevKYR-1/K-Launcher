package net.kdt.pojavlaunch.utils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP server on the loopback interface that answers every request the way the test says.
 * Plain sockets are used on purpose: unlike a real server, this one can send a body that is shorter than
 * its Content-Length and drop the connection, which is exactly the failure the download code has to survive.
 */
final class TestHttpServer implements Closeable {

    /** What the server answers to one request */
    static final class Reply {
        final int status;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();
        /** Send only this many bytes of the body and then drop the connection. -1 sends everything. */
        int cutBodyAfter = -1;

        private Reply(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        static Reply ok(byte[] body) {
            return new Reply(200, body);
        }

        static Reply status(int status) {
            return new Reply(status, ("status " + status).getBytes(StandardCharsets.UTF_8));
        }

        Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Reply cutAfter(int bytes) {
            cutBodyAfter = bytes;
            return this;
        }
    }

    interface Handler {
        /**
         * @param path the requested path, like "/file.bin"
         * @param requestNumber 1 for the first request that reached the server, 2 for the second...
         */
        Reply handle(String path, int requestNumber);
    }

    private final ServerSocket mServerSocket;
    private final Handler mHandler;
    private final AtomicInteger mRequestCount = new AtomicInteger();
    private final List<String> mMethods = Collections.synchronizedList(new ArrayList<String>());

    TestHttpServer(Handler handler) throws IOException {
        mHandler = handler;
        mServerSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::acceptLoop, "TestHttpServer");
        thread.setDaemon(true);
        thread.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + mServerSocket.getLocalPort() + path;
    }

    /** How many requests reached the server so far */
    int requestCount() {
        return mRequestCount.get();
    }

    /** The HTTP methods of the requests, in the order they arrived */
    List<String> methods() {
        synchronized (mMethods) {
            return new ArrayList<>(mMethods);
        }
    }

    private void acceptLoop() {
        while (!mServerSocket.isClosed()) {
            try {
                // The tests make one request after another, so one connection at a time is enough
                serve(mServerSocket.accept());
            } catch (IOException e) {
                if (mServerSocket.isClosed()) return;
            }
        }
    }

    private void serve(Socket accepted) {
        try (Socket socket = accepted) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String header;
            do {
                header = reader.readLine(); // the headers do not matter for the tests
            } while (header != null && !header.isEmpty());

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            int number = mRequestCount.incrementAndGet();
            mMethods.add(method);

            Reply reply = mHandler.handle(path, number);
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(reply.status).append(" Test\r\n");
            head.append("Content-Length: ").append(reply.body.length).append("\r\n");
            head.append("Connection: close\r\n");
            for (Map.Entry<String, String> entry : reply.headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            head.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!"HEAD".equals(method)) {
                int length = reply.cutBodyAfter >= 0
                        ? Math.min(reply.cutBodyAfter, reply.body.length) : reply.body.length;
                out.write(reply.body, 0, length);
            }
            out.flush();
        } catch (IOException ignored) {
            // The client may hang up at any time
        }
    }

    @Override
    public void close() {
        try {
            mServerSocket.close();
        } catch (IOException ignored) {
        }
    }
}
