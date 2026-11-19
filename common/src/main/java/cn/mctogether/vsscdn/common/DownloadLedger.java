package cn.mctogether.vsscdn.common;

import java.util.*;

/** The client resets pending downloads when its transport session changes. */
public final class DownloadLedger {
    private record Coordinate(String dimension, long position) {
        @Override public int hashCode() {
            // Mix the packed coordinates before folding them to avoid collisions along diagonals.
            return 31 * dimension.hashCode() + Long.hashCode(position * 0x9e3779b97f4a7c15L);
        }
    }

    // Identity stays stable while a repeated reference updates the delivery metadata.
    public static final class Ticket {
        private final Coordinate coordinate;
        private final ColumnKey key;
        private final int rawSize, length;
        private final byte codec;
        private long timestamp;
        private byte source;

        private Ticket(Coordinate coordinate, ColumnKey key, int rawSize, long timestamp, byte source, byte codec, int length) {
            this.coordinate = coordinate;
            this.key = key;
            this.rawSize = rawSize;
            this.timestamp = timestamp;
            this.source = source;
            this.codec = codec;
            this.length = length;
        }

        public ColumnKey key() { return key; }
        public int rawSize() { return rawSize; }
        public long timestamp() { return timestamp; }
        public byte source() { return source; }
        public byte codec() { return codec; }
        public int length() { return length; }
    }

    private final int maxColumns;
    private final long maxRawBytes;
    private final Map<Coordinate, Ticket> pending = new HashMap<>();
    private long rawBytes;

    public DownloadLedger(int maxColumns, long maxRawBytes) {
        this.maxColumns = maxColumns;
        this.maxRawBytes = maxRawBytes;
    }

    public static long pack(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }

    public synchronized Ticket reserve(ColumnKey key, int rawSize, long timestamp, byte source, byte codec, int length) {
        var coordinate = new Coordinate(key.dimension(), pack(key.x(), key.z()));
        var old = pending.get(coordinate);
        if (old != null && old.key.equals(key)) {
            if (old.rawSize != rawSize || old.codec != codec || old.length != length) {
                throw new IllegalArgumentException("Conflicting metadata for an immutable URL");
            }
            if (timestamp > old.timestamp) {
                old.timestamp = timestamp;
                old.source = source;
            }
            return old;
        }
        long remaining = rawBytes - (old == null ? 0 : old.rawSize);
        if (rawSize < 1 || rawSize > ColumnBlob.MAX_BYTES || (old == null && pending.size() >= maxColumns)
                || remaining + rawSize > maxRawBytes) return null;
        var ticket = new Ticket(coordinate, key, rawSize, timestamp, source, codec, length);
        pending.put(coordinate, ticket);
        rawBytes = remaining + rawSize;
        return ticket;
    }

    public synchronized boolean current(Ticket ticket) { return pending.get(ticket.coordinate) == ticket; }

    public synchronized void release(Ticket ticket) {
        if (pending.remove(ticket.coordinate, ticket)) rawBytes -= ticket.rawSize;
    }

    public synchronized List<Ticket> reset() {
        var result = List.copyOf(pending.values());
        pending.clear();
        rawBytes = 0;
        return result;
    }

    public synchronized int columns() { return pending.size(); }
    public synchronized long rawBytes() { return rawBytes; }
    public synchronized Ticket find(String dimension, long position) { return pending.get(new Coordinate(dimension, position)); }
}
