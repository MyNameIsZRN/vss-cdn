package cn.mctogether.vsscdn.common;

import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded persistent connections to ONE backend. Never routes by player address. */
public final class BackendPool implements AutoCloseable {
    public static final class Gone extends IOException { public Gone() { super("Publication unavailable"); } }
    private record Link(Socket socket, DataInputStream in, DataOutputStream out) implements AutoCloseable {
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
    private final UUID backend;
    private final InetSocketAddress address;
    private final String secret;
    private final int capacity;
    private final BlockingQueue<Link> idle;
    private final AtomicInteger count = new AtomicInteger();
    private final ScheduledExecutorService upkeep = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"vss-cdn-backend-upkeep"); t.setDaemon(true); return t; });
    private volatile boolean closed;
    public BackendPool(UUID backend, InetSocketAddress address, String secret, int capacity) {
        if (capacity < 1 || capacity > 32 || secret == null || secret.length() < 32) throw new IllegalArgumentException("Bad backend pool configuration");
        this.backend = backend; this.address = address; this.secret = secret; this.capacity = capacity;
        idle = new ArrayBlockingQueue<>(capacity);
        upkeep.scheduleWithFixedDelay(this::maintain, 0, 10, TimeUnit.SECONDS);
    }
    private Link connect() throws IOException {
        var socket = new Socket();
        try {
            socket.connect(address, 2000); socket.setSoTimeout(5000); socket.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Wire.clientAuth(in, out, secret, backend); return new Link(socket, in, out);
        } catch (IOException e) { socket.close(); throw e; }
    }
    private void discard(Link link) { link.close(); count.decrementAndGet(); }
    private synchronized void maintain() {
        if (closed) return;
        int n = idle.size();
        for (int i=0; i<n; i++) {
            Link l = idle.poll(); if (l == null) break;
            try { l.out.writeByte(0); l.out.flush(); if (l.in.readUnsignedByte()!=0) throw new IOException(); if (!idle.offer(l)) discard(l); }
            catch (IOException e) { discard(l); }
        }
        while (!closed && count.get() < capacity) {
            count.incrementAndGet();
            try { Link l = connect(); if (closed || !idle.offer(l)) discard(l); }
            catch (IOException e) { count.decrementAndGet(); break; }
        }
    }
    public ColumnBlob fetch(ColumnKey key) throws IOException {
        if (!backend.equals(key.backend()) || closed) throw new IOException("Wrong backend or closed pool");
        Link link;
        try { link = idle.poll(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        if (link == null) throw new IOException("Backend connection pool unavailable");
        boolean healthy = false;
        try {
            link.out.writeByte(1); key.write(link.out); link.out.flush();
            int status = link.in.readUnsignedByte();
            if (status == 1) { healthy = true; throw new Gone(); }
            if (status == 2) { healthy = true; throw new IOException("Backend read failed"); }
            if (status != 0) throw new IOException("Unknown backend status: " + status);
            ColumnBlob blob = ColumnBlob.read(link.in); healthy = true; return blob;
        } finally {
            if (!healthy || closed || !idle.offer(link)) discard(link);
        }
    }
    public int connected() { return count.get(); }
    @Override public void close() {
        closed = true; upkeep.shutdownNow(); Link l; while ((l=idle.poll())!=null) discard(l);
    }
}
