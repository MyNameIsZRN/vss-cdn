package cn.mctogether.vsscdn.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Persistent publication identity. A player/session identifier must never enter this key. */
public record ColumnKey(UUID backend, UUID world, UUID epoch, String dimension, int x, int z, long revision) {
    public ColumnKey {
        if (backend == null || world == null || epoch == null || dimension == null
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || dimension.length() > 256 || revision <= 0)
            throw new IllegalArgumentException("Invalid column identity");
    }
    public String scope() { return backend + "/" + world + "/" + epoch; }
    public String path() {
        String dim = Base64.getUrlEncoder().withoutPadding().encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
        return "/lod/v1/" + scope() + "/" + dim + "/" + x + "/" + z + "/" + revision + ".bin";
    }
    public static ColumnKey parse(String path) {
        if (path == null || path.length() > 1024 || path.contains("%") || path.contains("..")) throw new IllegalArgumentException("Bad path");
        String[] p = path.split("/", -1);
        if (p.length != 10 || !p[1].equals("lod") || !p[2].equals("v1") || !p[9].endsWith(".bin")) throw new IllegalArgumentException("Bad path");
        var key = new ColumnKey(UUID.fromString(p[3]), UUID.fromString(p[4]), UUID.fromString(p[5]),
                new String(Base64.getUrlDecoder().decode(p[6]), StandardCharsets.UTF_8),
                Integer.parseInt(p[7]), Integer.parseInt(p[8]), Long.parseLong(p[9].substring(0, p[9].length()-4)));
        if (!key.path().equals(path)) throw new IllegalArgumentException("Noncanonical path");
        return key;
    }
    public void write(DataOutput out) throws IOException {
        Wire.uuid(out, backend); Wire.uuid(out, world); Wire.uuid(out, epoch);
        out.writeUTF(dimension); out.writeInt(x); out.writeInt(z); out.writeLong(revision);
    }
    public static ColumnKey read(DataInput in) throws IOException {
        try { return new ColumnKey(Wire.uuid(in), Wire.uuid(in), Wire.uuid(in), in.readUTF(), in.readInt(), in.readInt(), in.readLong()); }
        catch (IllegalArgumentException e) { throw new IOException("Bad key", e); }
    }
}
