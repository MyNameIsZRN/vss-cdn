package cn.mctogether.vsscdn.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PropertiesConfig {
    private PropertiesConfig() {}
    public static Properties load(Path file, Properties defaults, String description) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent()); Properties p = new Properties(); p.putAll(defaults);
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { p.load(in); }
        else save(file,p,description);
        return p;
    }
    public static synchronized void save(Path file, Properties p, String description) throws IOException {
        var b = new ByteArrayOutputStream(); p.store(b,description); byte[] bytes=b.toByteArray();
        AtomicFiles.write(file, out -> out.write(bytes));
    }
    public static int integer(Properties p,String key,int lo,int hi) {
        int v = Integer.parseInt(p.getProperty(key)); if (v<lo||v>hi) throw new IllegalArgumentException("Out of range: "+key); return v;
    }
}
