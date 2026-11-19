package cn.mctogether.vsscdn.common;

import java.io.*;

public record Envelope(ColumnKey key, ColumnBlob blob) {
    public byte[] encode() throws IOException {
        var buffer = new ByteArrayOutputStream(blob.size() + 512);
        try (var out = new DataOutputStream(buffer)) { out.writeInt(Wire.MAGIC); out.writeInt(Wire.VERSION); key.write(out); blob.write(out); out.writeLong(blob.crc()); }
        return buffer.toByteArray();
    }
    public static Envelope decode(byte[] encoded, ColumnKey expected) throws IOException {
        if (encoded.length > ColumnBlob.MAX_BYTES + 2048) throw new IOException("Oversized HTTP body");
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != Wire.MAGIC || in.readInt() != Wire.VERSION) throw new IOException("Unsupported HTTP envelope");
            var key = ColumnKey.read(in); var blob = ColumnBlob.read(in);
            if (!key.equals(expected) || in.readLong() != blob.crc() || in.read() != -1) throw new IOException("HTTP identity/checksum mismatch");
            return new Envelope(key, blob);
        }
    }
}
