package cn.mctogether.vsscdn.common;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** HTTP CDN origin hosted by the Velocity plugin, separate from Velocity's Minecraft listener. */
public final class BridgeHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<UUID, BackendPool> routes;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    public final LongAdder requests = new LongAdder(), bytes = new LongAdder(), failures = new LongAdder();
    public BridgeHttpServer(InetSocketAddress bind, Map<UUID, BackendPool> routes, int concurrency) throws IOException {
        this.routes = Map.copyOf(routes); this.permits = new Semaphore(concurrency);
        server = HttpServer.create(bind, 64); server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        requests.increment();
        if (!permits.tryAcquire()) { error(exchange, 503); return; }
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) { error(exchange,405); return; }
            if (exchange.getRequestURI().getRawQuery()!=null) { error(exchange,400); return; }
            ColumnKey key;
            try { key = ColumnKey.parse(exchange.getRequestURI().getRawPath()); }
            catch (IllegalArgumentException e) { error(exchange,400); return; }
            BackendPool pool = routes.get(key.backend()); if (pool == null) { error(exchange,404); return; }
            byte[] body;
            try { body = new Envelope(key, pool.fetch(key)).encode(); }
            catch (BackendPool.Gone e) { error(exchange,410); return; }
            catch (IOException e) { error(exchange,503); return; }
            var h = exchange.getResponseHeaders();
            h.set("Content-Type", "application/octet-stream"); h.set("Cache-Control", "public, max-age=31536000, immutable, no-transform");
            h.set("ETag", "\"" + key.epoch() + "-" + key.revision() + "\"");
            h.set("X-Content-Type-Options", "nosniff");
            if (exchange.getRequestMethod().equals("HEAD")) { h.set("Content-Length",Integer.toString(body.length)); exchange.sendResponseHeaders(200,-1); }
            else { exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); bytes.add(body.length); }
        } finally { permits.release(); }
    }
    private void error(HttpExchange exchange, int status) throws IOException {
        failures.increment(); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status,-1); exchange.close();
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
