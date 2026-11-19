package cn.mctogether.vsscdn.common;

import java.io.*;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Existing VSS section bytes, without another compression or terrain encoding. */
public record ColumnBlob(byte codec, int rawSize, byte[] bytes) {
    public static final int MAX_BYTES = 2_097_152;
    public ColumnBlob {
        if ((codec != 0 && codec != 1) || rawSize < 1 || rawSize > MAX_BYTES
                || bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES
                || (codec == 0 && rawSize != bytes.length)) throw new IllegalArgumentException("Invalid column body");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    public long crc() { CRC32C c = new CRC32C(); c.update(bytes, 0, bytes.length); return c.getValue(); }
    public boolean same(ColumnBlob b) { return codec == b.codec && rawSize == b.rawSize && Arrays.equals(bytes, b.bytes); }
    public void write(DataOutput out) throws IOException { out.writeByte(codec); out.writeInt(rawSize); out.writeInt(bytes.length); out.write(bytes); }
    public static ColumnBlob read(DataInput in) throws IOException {
        byte codec = in.readByte(); int raw = in.readInt(); int n = in.readInt();
        if (raw < 1 || raw > MAX_BYTES || n < 1 || n > MAX_BYTES) throw new IOException("Oversized body");
        byte[] b = new byte[n]; in.readFully(b);
        try { return new ColumnBlob(codec, raw, b); } catch (IllegalArgumentException e) { throw new IOException("Invalid body", e); }
    }
}
