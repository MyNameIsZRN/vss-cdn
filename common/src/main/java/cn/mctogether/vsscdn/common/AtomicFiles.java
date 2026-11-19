package cn.mctogether.vsscdn.common;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class AtomicFiles {
    private AtomicFiles() {}
    @FunctionalInterface public interface Writer { void write(DataOutputStream out) throws IOException; }
    public static void write(Path target, Writer writer) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), ".vsscdn-", ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) { writer.write(out); }
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
