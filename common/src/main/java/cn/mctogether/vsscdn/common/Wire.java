package cn.mctogether.vsscdn.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.UUID;

public final class Wire {
    private Wire() {}
    public static final int MAGIC = 0x56534344, VERSION = 1;
    public static void uuid(DataOutput out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    public static UUID uuid(DataInput in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    public static byte[] proof(String secret, byte[] nonce, UUID backend) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(nonce); mac.update(backend.toString().getBytes(StandardCharsets.US_ASCII)); return mac.doFinal();
        } catch (GeneralSecurityException e) { throw new IOException(e); }
    }
    public static void serverAuth(DataInputStream in, DataOutputStream out, String secret, UUID backend) throws IOException {
        out.writeInt(MAGIC); out.writeInt(VERSION); uuid(out, backend);
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce); out.write(nonce); out.flush();
        byte[] received = new byte[32]; in.readFully(received);
        boolean ok = MessageDigest.isEqual(received, proof(secret, nonce, backend));
        out.writeBoolean(ok); out.flush(); if (!ok) throw new IOException("Bridge authentication rejected");
    }
    public static void clientAuth(DataInputStream in, DataOutputStream out, String secret, UUID backend) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION || !uuid(in).equals(backend)) throw new IOException("Wrong backend/protocol");
        byte[] nonce = new byte[32]; in.readFully(nonce); out.write(proof(secret, nonce, backend)); out.flush();
        if (!in.readBoolean()) throw new IOException("Bridge authentication rejected");
    }
}
