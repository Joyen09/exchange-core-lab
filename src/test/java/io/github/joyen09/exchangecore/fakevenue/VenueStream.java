package io.github.joyen09.exchangecore.fakevenue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The user data stream: a WebSocket server small enough to be read in one sitting.
 *
 * <h2>Why this is hand-written rather than a library</h2>
 *
 * The JDK ships a WebSocket client and no server, so something had to be chosen. A library would have
 * made the easy part easier and the important part harder: what this double exists to do is
 * <b>drop the connection at a chosen moment</b>, deliver an event <b>before</b> an HTTP response
 * returns, and redeliver a frame already sent. Those are not features libraries expose, because they
 * are not things a correct server does.
 *
 * <p>So: RFC 6455's handshake, text frames, and a {@link #drop()} that closes the socket without a
 * close frame — which is what a network failure looks like, as opposed to a polite shutdown. A client
 * that only ever sees polite shutdowns never learns to reconnect from the impolite kind, and the
 * impolite kind is the one that happens.
 *
 * <p>Incoming frames are read and discarded. Nothing in this project sends anything up the user data
 * stream, and a double that implemented more than the protocol requires would be a worse model of the
 * venue, not a better one.
 */
public final class VenueStream implements AutoCloseable {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final AtomicReference<Socket> connection = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> sent = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> connected = new CopyOnWriteArrayList<>();
    private volatile String listenKey;
    private volatile boolean running = true;

    public VenueStream() {
        try {
            serverSocket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("could not open the stream socket", e);
        }
        acceptor = new Thread(this::acceptLoop, "fake-venue-stream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String url(String listenKey) {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort() + "/ws/" + listenKey;
    }

    /** The key the stream currently expects. A reconnect after expiry must present the new one. */
    public void expect(String listenKey) {
        this.listenKey = listenKey;
    }

    /** Every frame this stream has sent, including ones the client never received because of a drop. */
    public List<String> sent() {
        return List.copyOf(sent);
    }

    public boolean isConnected() {
        Socket socket = connection.get();
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /** Blocks until a client has completed the handshake, so a test need not poll. */
    public boolean awaitConnection(java.time.Duration timeout) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        connected.add(latch);
        if (isConnected()) {
            return true;
        }
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Pushes one event. Recorded even if nobody is listening — see {@link #drop()}. */
    public synchronized void push(String payload) {
        sent.add(payload);
        Socket socket = connection.get();
        if (socket == null || socket.isClosed()) {
            // Deliberately not an error. An event that occurs while the client is away is the whole
            // subject of ADR-0011: it happened, the venue knows, and the only way to learn of it is to
            // reconnect and ask. A stream that refused to emit while disconnected would make the
            // recovery path untestable by leaving nothing to recover.
            return;
        }
        try {
            writeTextFrame(socket.getOutputStream(), payload);
        } catch (IOException e) {
            closeQuietly(socket);
            connection.set(null);
        }
    }

    /**
     * Kills the connection the way a network does: no close frame, no warning.
     *
     * <p>A polite {@code close()} is a different event, and a client that handles only the polite one
     * is a client that has never been tested against what actually happens.
     */
    public synchronized void drop() {
        Socket socket = connection.getAndSet(null);
        if (socket != null) {
            try {
                socket.setSoLinger(true, 0); // RST rather than FIN — an abrupt death, not a goodbye
            } catch (Exception ignored) {
                // best effort; closing is what matters
            }
            closeQuietly(socket);
        }
    }

    @Override
    public void close() {
        running = false;
        drop();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
        acceptor.interrupt();
    }

    // ---------------------------------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (handshake(socket)) {
                    connection.set(socket);
                    connected.forEach(CountDownLatch::countDown);
                    startReader(socket);
                } else {
                    closeQuietly(socket);
                }
            } catch (IOException e) {
                if (running) {
                    continue;
                }
                return;
            }
        }
    }

    /** RFC 6455 §4.2.2, and a path check so a stale listenKey is refused rather than quietly served. */
    private boolean handshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder request = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            request.append((char) current);
            if (previous == '\n' && current == '\n') {
                break;
            }
            if (current != '\r') {
                previous = current;
            }
        }

        String text = request.toString();
        String key = headerValue(text, "Sec-WebSocket-Key");
        if (key == null) {
            return false;
        }

        String path = text.lines().findFirst().orElse("").split(" ").length > 1
                ? text.lines().findFirst().orElse("").split(" ")[1]
                : "";
        String expected = listenKey;
        if (expected != null && !path.equals("/ws/" + expected)) {
            // The venue closes a stream whose listenKey has expired. A double that served any path
            // would let a renewal bug through unnoticed.
            socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return false;
        }

        String accept = Base64.getEncoder()
                .encodeToString(sha1(key + WEBSOCKET_GUID));
        OutputStream out = socket.getOutputStream();
        out.write(("""
                HTTP/1.1 101 Switching Protocols\r
                Upgrade: websocket\r
                Connection: Upgrade\r
                Sec-WebSocket-Accept: %s\r
                \r
                """).formatted(accept).getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    /** Reads and discards client frames, so a close or a pong does not wedge the connection. */
    private void startReader(Socket socket) {
        Thread reader = new Thread(
                () -> {
                    try (InputStream in = socket.getInputStream()) {
                        byte[] scratch = new byte[1024];
                        while (!socket.isClosed() && in.read(scratch) != -1) {
                            // Discarded on purpose: nothing is sent up this stream.
                        }
                    } catch (IOException ignored) {
                        // the connection died, which drop() and push() already handle
                    }
                },
                "fake-venue-stream-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Server-to-client frames are never masked (RFC 6455 §5.1). */
    private static void writeTextFrame(OutputStream out, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        List<Byte> header = new ArrayList<>();
        header.add((byte) 0x81); // FIN + text
        if (bytes.length < 126) {
            header.add((byte) bytes.length);
        } else if (bytes.length < 65_536) {
            header.add((byte) 126);
            header.add((byte) (bytes.length >> 8));
            header.add((byte) bytes.length);
        } else {
            header.add((byte) 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                header.add((byte) (bytes.length >> shift));
            }
        }
        byte[] framed = new byte[header.size() + bytes.length];
        for (int i = 0; i < header.size(); i++) {
            framed[i] = header.get(i);
        }
        System.arraycopy(bytes, 0, framed, header.size(), bytes.length);
        out.write(framed);
        out.flush();
    }

    private static String headerValue(String request, String name) {
        return request.lines()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith(name.toLowerCase(java.util.Locale.ROOT) + ":"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .findFirst()
                .orElse(null);
    }

    private static byte[] sha1(String value) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 is required by the JDK and must be present", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
