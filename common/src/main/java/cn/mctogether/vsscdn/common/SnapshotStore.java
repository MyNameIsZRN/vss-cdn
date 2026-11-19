package cn.mctogether.vsscdn.common;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;

/** A durable HTTP publication view of existing compressed VSS data. No hashes or VSS DB migrations. */
public final class SnapshotStore {
    public record Published(ColumnKey key, ColumnBlob blob) {}
    private record Location(UUID world, String dimension, int x, int z) {}
    private record CachedPublication(ColumnKey key, long refreshedAt, long size) {}
    private static final class DamagedPublication extends IOException {
        DamagedPublication(IOException cause) { super("Snapshot damaged: " + cause.getMessage(), cause); }
    }
    private static final long REFRESH_INTERVAL = Duration.ofHours(1).toMillis();
    private static final long QUOTA_RECHECK_INTERVAL = Duration.ofSeconds(30).toNanos();
    private final Path root;
    private final UUID backend, epoch;
    private final Map<Path, CachedPublication> latest = new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path,CachedPublication> e){return size()>8192;}
    };
    private final LinkedHashMap<Path, ColumnBlob> hot = new LinkedHashMap<>(16, .75f, true);
    private long next, reserved, hotBytes;
    private final long hotLimit, diskLimit;
    private long diskBytes, diskVersion;
    private long lastQuotaCheck = System.nanoTime() - QUOTA_RECHECK_INTERVAL;
    private boolean checkingQuota;
    public SnapshotStore(Path root, UUID backend, long hotLimit) throws IOException {
        this(root,backend,hotLimit,64L*1024*1024*1024);
    }
    public SnapshotStore(Path root, UUID backend, long hotLimit,long diskLimit) throws IOException {
        this.root = root.toAbsolutePath(); this.backend = backend; this.hotLimit = hotLimit;this.diskLimit=diskLimit;
        if(hotLimit<0||diskLimit<1)throw new IllegalArgumentException("Invalid snapshot limits");
        Files.createDirectories(this.root);
        Path state = this.root.resolve("publication-state.bin");
        if (Files.exists(state)) {
            try (var in = new DataInputStream(Files.newInputStream(state))) {
                if (in.readInt() != Wire.MAGIC || !Wire.uuid(in).equals(backend)) throw new IOException("Snapshot store belongs to another backend");
                epoch = Wire.uuid(in); next = in.readLong(); if (next < 1) throw new IOException("Bad revision counter");
            }
        } else { epoch = UUID.randomUUID(); next = 1; reserved=1;saveCounter(); }
        reserved=next;
        diskBytes = scanPublications((path, attrs) -> attrs.size());
    }
    public UUID epoch() { return epoch; }
    private void saveCounter() throws IOException {
        AtomicFiles.write(root.resolve("publication-state.bin"), out -> { out.writeInt(Wire.MAGIC); Wire.uuid(out, backend); Wire.uuid(out, epoch); out.writeLong(reserved); });
    }
    private Path columnDir(Location l) { return root.resolve(epoch.toString()).resolve(l.world.toString()).resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(l.dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(l.x + "_" + l.z); }
    private static String fileName(ColumnKey key) { return key.revision() + ".bin"; }
    private Path file(ColumnKey key) { return columnDir(new Location(key.world(), key.dimension(), key.x(), key.z())).resolve(fileName(key)); }
    private static long latestRevision(Path directory) throws IOException {
        long revision = 0;
        if (Files.isDirectory(directory)) try (var files = Files.newDirectoryStream(directory, "*.bin")) {
            for (Path f : files) try { revision = Math.max(revision, Long.parseLong(f.getFileName().toString().replace(".bin", ""))); } catch (NumberFormatException ignored) {}
        }
        return revision;
    }
    public Published publish(UUID world, String dimension, int x, int z, ColumnBlob blob) throws IOException {
        var location = new Location(world, dimension, x, z);
        var directory = columnDir(location);
        var published = publishIfFits(location, directory, blob);
        if (published != null) return published;

        recheckQuota();
        published = publishIfFits(location, directory, blob);
        if (published != null) return published;
        throw new IOException("Publication disk limit reached; original VSS transport required");
    }
    private synchronized Published publishIfFits(Location l, Path directory, ColumnBlob blob) throws IOException {
        var cached = latest.get(directory);
        long previousRevision = cached == null ? latestRevision(directory) : cached.key.revision();
        Path previousPath = null;
        if (previousRevision > 0) try {
            var previousKey = new ColumnKey(backend, l.world, epoch, l.dimension, l.x, l.z, previousRevision);
            previousPath = directory.resolve(fileName(previousKey));
            var attributes = Files.readAttributes(previousPath, BasicFileAttributes.class);
            long modified = attributes.lastModifiedTime().toMillis();
            long size = attributes.size();
            var previous = cached != null && cached.refreshedAt == modified && cached.size == size ? hot.get(previousPath) : null;
            if (previous == null) previous = read(previousKey);
            if (previous.same(blob)) {
                long now = System.currentTimeMillis();
                if (now - modified >= REFRESH_INTERVAL) {
                    Files.setLastModifiedTime(previousPath, FileTime.fromMillis(now));
                    modified = now;
                }
                latest.put(directory, new CachedPublication(previousKey, modified, size));
                remember(previousPath, blob);
                return new Published(previousKey, blob);
            }
        } catch (DamagedPublication | NoSuchFileException ignored) {
            // VSS supplied a fresh body. Leave the old URL alone and publish a new revision.
            invalidateUnreadable(previousPath);
        }
        if (next == Long.MAX_VALUE) throw new IOException("Revision counter exhausted");
        if (diskBytes > diskLimit - blob.size() - 2048) return null;
        if(next>=reserved){long priorReservation=reserved;reserved=next+Math.min(1024,Long.MAX_VALUE-next);try{saveCounter();}catch(IOException e){reserved=priorReservation;throw e;}}
        long revision = next++; // A crash leaves gaps in reserved blocks, never reuses a version.
        var key = new ColumnKey(backend, l.world, epoch, l.dimension, l.x, l.z, revision);
        Path path = directory.resolve(fileName(key));
        AtomicFiles.write(path, out -> { out.writeInt(Wire.MAGIC); key.write(out); blob.write(out); out.writeLong(blob.crc()); });
        diskVersion++;
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        diskBytes += attributes.size();
        if (previousPath != null) invalidate(previousPath);
        latest.put(directory, new CachedPublication(key, attributes.lastModifiedTime().toMillis(), attributes.size()));
        remember(path, blob);
        return new Published(key, blob);
    }
    private void recheckQuota() throws IOException {
        long version;
        synchronized (this) {
            if (checkingQuota || System.nanoTime() - lastQuotaCheck < QUOTA_RECHECK_INTERVAL) return;
            checkingQuota = true;
            version = diskVersion;
        }
        try {
            long actual = scanPublications((path, attrs) -> attrs.size());
            synchronized (this) {
                // A concurrent write or deletion makes this scan unsuitable for accounting.
                if (version == diskVersion) {
                    diskBytes = actual;
                    lastQuotaCheck = System.nanoTime();
                }
            }
        } finally {
            synchronized (this) {
                checkingQuota = false;
            }
        }
    }
    @FunctionalInterface
    private interface PublicationMeasure {
        long measure(Path path, BasicFileAttributes attrs) throws IOException;
    }
    private long scanPublications(PublicationMeasure measure) throws IOException {
        var visitor = new SimpleFileVisitor<Path>() {
            long total;
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && path.getFileName().toString().endsWith(".bin")) {
                    total += measure.measure(path, attrs);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                return error == null ? FileVisitResult.CONTINUE : visitFileFailed(path, error);
            }
        };
        Files.walkFileTree(root.resolve(epoch.toString()), visitor);
        return visitor.total;
    }
    /** Expiration is allowed to remove an object, never to change the bytes at its URL. */
    public int expireOlderThan(long cutoffMillis) throws IOException {
        return Math.toIntExact(scanPublications((path, attrs) ->
                attrs.lastModifiedTime().toMillis() < cutoffMillis && deleteExpired(path, cutoffMillis) ? 1 : 0));
    }
    private synchronized boolean deleteExpired(Path path, long cutoffMillis) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile() || attributes.lastModifiedTime().toMillis() >= cutoffMillis
                    || !Files.deleteIfExists(path)) return false;
            diskBytes = Math.max(0, diskBytes - attributes.size());
            diskVersion++;
            invalidate(path);
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        }
    }
    private void remember(Path path, ColumnBlob blob) {
        var old = hot.put(path, blob); if (old != null) hotBytes -= old.size(); hotBytes += blob.size();
        var i = hot.entrySet().iterator(); while (hotBytes > hotLimit && i.hasNext()) { var e = i.next(); hotBytes -= e.getValue().size(); i.remove(); }
    }
    private synchronized boolean invalidate(Path path) {
        var directory = path.getParent();
        var cached = latest.get(directory);
        boolean current = cached != null && path.getFileName().toString().equals(fileName(cached.key));
        if (current) latest.remove(directory);
        var removed = hot.remove(path);
        if (removed != null) hotBytes -= removed.size();
        return current || removed != null;
    }
    private synchronized void invalidateUnreadable(Path path) {
        // A newly discovered loss can free space since the last successful scan.
        if (invalidate(path)) {
            diskVersion++;
            lastQuotaCheck = System.nanoTime() - QUOTA_RECHECK_INTERVAL;
        }
    }
    public ColumnBlob read(ColumnKey key) throws IOException {
        if (!backend.equals(key.backend()) || !epoch.equals(key.epoch())) throw new FileNotFoundException("Unknown publication scope");
        Path path = file(key);
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            try {
                if (in.readInt() != Wire.MAGIC || !ColumnKey.read(in).equals(key)) throw new IOException("Snapshot identity mismatch");
                var blob = ColumnBlob.read(in);
                if (in.readLong() != blob.crc() || in.read() != -1) throw new IOException("Snapshot checksum or length mismatch");
                return blob;
            } catch (IOException e) {
                invalidateUnreadable(path);
                throw new DamagedPublication(e);
            }
        } catch (NoSuchFileException e) {
            invalidateUnreadable(path);
            throw e;
        }
    }
}
