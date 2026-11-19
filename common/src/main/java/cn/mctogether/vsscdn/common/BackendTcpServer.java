package cn.mctogether.vsscdn.common;

import java.io.*;
import java.net.*;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.*;

/** Independent authenticated binary port; every connection is initiated by the bridge. */
public final class BackendTcpServer implements AutoCloseable {
    private final ServerSocket listener;
    private final SnapshotStore store;
    private final UUID backend;
    private final String secret;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public BackendTcpServer(InetSocketAddress bind, SnapshotStore store, UUID backend, String secret, int maxConnections) throws IOException {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Bridge secret must contain at least 32 characters");
        this.store = store; this.backend = backend; this.secret = secret; permits = new Semaphore(maxConnections);
        listener = new ServerSocket(); listener.bind(bind);
        workers.submit(() -> { while (!closed) try {
            Socket s = listener.accept();
            if (!permits.tryAcquire()) { s.close(); continue; }
            sockets.add(s); workers.submit(() -> serve(s));
        } catch (IOException e) { if (!closed) close(); } });
    }
    public int port() { return listener.getLocalPort(); }
    private void serve(Socket socket) {
        try (socket; var in = new DataInputStream(new BufferedInputStream(socket.getInputStream())); var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setSoTimeout(30_000); socket.setTcpNoDelay(true);
            Wire.serverAuth(in, out, secret, backend);
            while (!closed) {
                int command = in.readUnsignedByte();
                if (command == 0) { out.writeByte(0); out.flush(); continue; }
                if (command != 1) throw new IOException("Unknown command");
                ColumnKey key = ColumnKey.read(in);
                ColumnBlob blob;
                try {
                    blob = store.read(key);
                } catch (IOException e) {
                    out.writeByte(e instanceof FileNotFoundException || e instanceof NoSuchFileException ? 1 : 2);
                    out.flush();
                    continue;
                }
                out.writeByte(0);
                blob.write(out);
                out.flush();
            }
        } catch (IOException ignored) { /* A disconnected or unauthenticated bridge has no effect on VSS. */ }
        finally { sockets.remove(socket); permits.release(); }
    }
    @Override public void close() {
        closed = true; try { listener.close(); } catch (IOException ignored) {}
        for (Socket s : sockets) try { s.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
