package cn.mctogether.vsscdn.common;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;

public final class TransportIntegrationTest {
    private static int checks;
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception{
        testBridgeConfiguration();
        testConnectionPolicy();
        testNativeReceipts();
        var temp=Files.createTempDirectory("vss-cdn-integration-");
        testHotPublications(temp.resolve("retention"));
        testDamagedPublications(temp.resolve("damaged"));
        testCleanupRechecksRetention(temp.resolve("retention-race"));
        testQuotaRecount(temp.resolve("quota"));
        testSelectiveInvalidation(temp.resolve("selective-cleanup"));
        testCleanupDuringPublication(temp.resolve("concurrent-scan"));
        testLargeBatch();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),wa=UUID.randomUUID(),wb=UUID.randomUUID();
        String secret="integration-secret-with-at-least-32-characters";
        var sa=new SnapshotStore(temp.resolve("a"),a,1024);
        var sb=new SnapshotStore(temp.resolve("b"),b,1024);
        var old=sa.publish(wa,"minecraft:overworld",100,200,new ColumnBlob((byte)0,3,new byte[]{1,2,3}));
        var same=sa.publish(wa,"minecraft:overworld",100,200,new ColumnBlob((byte)0,3,new byte[]{1,2,3}));
        check(old.key().equals(same.key()),"unchanged bytes must reuse URL");
        var newer=sa.publish(wa,"minecraft:overworld",100,200,new ColumnBlob((byte)0,3,new byte[]{4,5,6}));
        check(newer.key().revision()>old.key().revision(),"new body gets a durable revision");
        check(Arrays.equals(sa.read(old.key()).bytes(),new byte[]{1,2,3}),"old URL remains immutable");
        sa=new SnapshotStore(temp.resolve("a"),a,1024);
        check(sa.publish(wa,"minecraft:overworld",100,200,newer.blob()).key().equals(newer.key()),"restart retains epoch/revision/content");
        check(ColumnKey.parse(newer.key().path()).equals(newer.key()),"canonical URL round trip");
        try{ColumnKey.parse(newer.key().path()+"/../x");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        var other=sb.publish(wb,"minecraft:overworld",100,200,new ColumnBlob((byte)0,3,new byte[]{9,9,9}));
        byte[] frame=new byte[65536];new Random(42).nextBytes(frame);
        var framed=sb.publish(wb,"minecraft:overworld",-500,-17,new ColumnBlob((byte)1,1048576,frame));
        var damaged=sa.publish(wa,"minecraft:overworld",101,200,newer.blob()).key();
        Files.write(publicationFile(temp.resolve("a"),damaged),new byte[0]);
        try(var ta=new BackendTcpServer(new InetSocketAddress("127.0.0.1",0),sa,a,secret,8);
            var tb=new BackendTcpServer(new InetSocketAddress("127.0.0.1",0),sb,b,secret,8);
            var pa=new BackendPool(a,new InetSocketAddress("127.0.0.1",ta.port()),secret,1);
            var pb=new BackendPool(b,new InetSocketAddress("127.0.0.1",tb.port()),secret,2);
            var http=new BridgeHttpServer(new InetSocketAddress("127.0.0.1",0),Map.of(a,pa,b,pb),8)){
            try(var client=HttpClient.newHttpClient()){
                String base="http://127.0.0.1:"+http.port();
                var response=client.send(HttpRequest.newBuilder(URI.create(base+newer.key().path())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(response.statusCode()==200,"bridge must connect to backend and return HTTP body");
                check(Envelope.decode(response.body(),newer.key()).blob().same(newer.blob()),"existing codec/body preserved through TCP+HTTP");
                check(response.headers().firstValue("Cache-Control").orElse("").contains("max-age=31536000"),"success is CDN cacheable");
                var rb=client.send(HttpRequest.newBuilder(URI.create(base+other.key().path())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(Envelope.decode(rb.body(),other.key()).blob().same(other.blob()),"same coordinates on backend B stay isolated");
                var rf=client.send(HttpRequest.newBuilder(URI.create(base+framed.key().path())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(Envelope.decode(rf.body(),framed.key()).blob().same(framed.blob()),"codec-1 frame bytes and raw size survive large TCP/HTTP transfer without recompression");
                try{Envelope.decode(rb.body(),newer.key());throw new AssertionError();}catch(IOException expected){checks++;}
                var missing=new ColumnKey(a,wa,sa.epoch(),"minecraft:overworld",100,200,Long.MAX_VALUE);
                var gone=client.send(HttpRequest.newBuilder(URI.create(base+missing.path())).GET().build(),HttpResponse.BodyHandlers.discarding());
                check(gone.statusCode()==410&&gone.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"missing version must never be negatively cached");
                var bad=client.send(HttpRequest.newBuilder(URI.create(base+damaged.path())).GET().build(),HttpResponse.BodyHandlers.discarding());
                check(bad.statusCode()==503&&bad.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"damaged object returns an uncached read error");
                check(pa.connected()==1,"a complete backend read error keeps the sole authenticated connection");
                var healthy=client.send(HttpRequest.newBuilder(URI.create(base+newer.key().path())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(healthy.statusCode()==200&&Envelope.decode(healthy.body(),newer.key()).blob().same(newer.blob()),"next origin request succeeds on the same one-link pool");
                var repair=sa.publish(wa,"minecraft:overworld",101,200,newer.blob()).key();
                var repaired=client.send(HttpRequest.newBuilder(URI.create(base+repair.path())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(repair.revision()>damaged.revision()&&repaired.statusCode()==200
                        &&Envelope.decode(repaired.body(),repair).blob().same(newer.blob()),"origin serves the repaired object at a new immutable URL");
                // A real cache proxy: two players, one origin fetch. This tests cacheability rather than just header spelling.
                Map<String,byte[]> cache=new ConcurrentHashMap<>(); var cdn=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
                cdn.createContext("/",e->{try(e){String path=e.getRequestURI().getPath(); byte[] body=cache.get(path);
                    if(body==null){try{var r=client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                        if(r.statusCode()!=200)throw new IOException();body=r.body();cache.put(path,body);}catch(Exception x){e.sendResponseHeaders(502,-1);return;}}
                    e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}});cdn.start();
                try{long before=http.requests.sum(); var url=URI.create("http://127.0.0.1:"+cdn.getAddress().getPort()+old.key().path());
                    for(int i=0;i<2;i++){var r=client.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofByteArray());check(Envelope.decode(r.body(),old.key()).blob().same(old.blob()),"cached response remains old immutable version");}
                    check(http.requests.sum()-before==1,"second player does not consume origin bandwidth");
                }finally{cdn.stop(0);}
            }
            try(var wrong=new BackendPool(a,new InetSocketAddress("127.0.0.1",ta.port()),"wrong-secret-with-at-least-32-characters",1)){
                try{wrong.fetch(newer.key());throw new AssertionError();}catch(IOException expected){checks++;}
            }
        }
        var ledger=new DownloadLedger(2,10);
        var first=ledger.reserve(old.key(),3,10,(byte)0,(byte)0,3);check(first!=null,"reserve first download");
        var replacement=ledger.reserve(newer.key(),3,11,(byte)0,(byte)0,3);check(!ledger.current(first)&&ledger.current(replacement),"older HTTP version cannot overwrite newer version");
        var dirty=ledger.find("minecraft:overworld",((long)100<<32)|200L);
        check(dirty==replacement,"dirty lookup uses VSS x-high/z-low coordinate packing");
        ledger.release(dirty);
        replacement=ledger.reserve(newer.key(),3,11,(byte)0,(byte)0,3);
        ledger.reset();check(!ledger.current(replacement)&&ledger.rawBytes()==0,"switch clears budget and rejects late callback");
        var limited=new SnapshotStore(temp.resolve("limited"),UUID.randomUUID(),1024,2050);
        try{limited.publish(wa,"minecraft:overworld",-3,-7,new ColumnBlob((byte)0,3,new byte[]{1,2,3}));throw new AssertionError();}catch(IOException expected){checks++;}
        check(sa.expireOlderThan(System.currentTimeMillis()+1000)>=2,"expired immutable publications are reclaimed");
        try{sa.read(newer.key());throw new AssertionError();}catch(IOException expected){checks++;}
        check(sa.publish(wa,"minecraft:overworld",100,200,newer.blob()).key().revision()>newer.key().revision(),"republishing after cleanup never reuses a URL");
        var valid=new Envelope(old.key(),old.blob()).encode();valid[valid.length-1]^=1;
        try{Envelope.decode(valid,old.key());throw new AssertionError();}catch(IOException expected){checks++;}
        var bounded=new LimitedBodySubscriber(2);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        bounded.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
        bounded.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[3])));
        check(cancelled.get()&&bounded.getBody().toCompletableFuture().isCompletedExceptionally(),"oversized HTTP bodies cancel before unbounded buffering");
        System.out.println("PASS "+checks+" integration assertions: durable versions, two backends, authenticated TCP, HTTP, CDN reuse, fallback errors, session isolation and LAN routing");
    }
    private static void testNativeReceipts()throws Exception{
        check(ReceiptChecks.reusable(100,100,Long.MIN_VALUE,false),"validated native receipt suppresses a same-version CDN body, and vice versa");
        check(!ReceiptChecks.reusable(100,101,Long.MIN_VALUE,false),"changed terrain still requires delivery");
        check(!ReceiptChecks.reusable(100,100,100,false),"dirty or unvalidated stamp alone cannot suppress delivery");
        check(!ReceiptChecks.reusable(100,100,Long.MIN_VALUE,true),"pending receipt load cannot be treated as completed data");
        check(!ReceiptChecks.reusable(-1,100,Long.MIN_VALUE,false),"failed or missing receipt must retry");
        check(!ReceiptChecks.reusable(0,0,Long.MIN_VALUE,false),"authoritative empty/reset stamps retain original VSS processing");
        check(ReceiptChecks.older(101,100),"late body/reference cannot replace a newer shared receipt");
        check(!ReceiptChecks.older(100,101)&&!ReceiptChecks.older(100,100),"current or newer content is not rejected as stale");
        check(!ReceiptChecks.older(100,0)&&!ReceiptChecks.older(-1,100),"nonpositive native control/clear stamps are not ordered as terrain versions");
        UUID b=UUID.randomUUID(),w=UUID.randomUUID(),e=UUID.randomUUID();
        var key=new ColumnKey(b,w,e,"minecraft:overworld",10,20,1);
        var ledger=new DownloadLedger(2,1024);
        var first=ledger.reserve(key,100,100,(byte)1,(byte)0,100);
        var repeated=ledger.reserve(key,100,101,(byte)2,(byte)0,100);
        check(repeated==first&&ledger.columns()==1&&ledger.rawBytes()==100,"duplicate URL keeps one transfer and one budget charge");
        check(first.timestamp()==101&&first.source()==2,"the original HTTP ticket delivers the latest timestamp and source");
        ledger.reserve(key,100,100,(byte)1,(byte)0,100);
        check(first.timestamp()==101&&first.source()==2,"older repeat cannot overwrite delivery metadata");
        try {
            ledger.reserve(key,100,102,(byte)1,(byte)1,99);
            throw new AssertionError("one URL cannot describe two bodies");
        } catch (IllegalArgumentException expected) { checks++; }
        var next=ledger.reserve(new ColumnKey(b,w,e,"minecraft:overworld",10,20,2),100,102,(byte)2,(byte)0,100);
        check(!ledger.current(first)&&ledger.current(next)&&ledger.find("minecraft:overworld",DownloadLedger.pack(10,20)).timestamp()==102,"newer pending version invalidates old HTTP completion and exposes its stamp to native transport");
        ledger.release(first);
        check(ledger.current(next)&&ledger.rawBytes()==100,"a late completion cannot release a replacement ticket");
        ledger.release(next);
        check(!ledger.current(next)&&ledger.columns()==0,"native delivery can cancel HTTP without leaving a second active owner");
        ledger.reset();
        check(!ledger.current(first)&&!ledger.current(next),"session switch rejects both channels' retired HTTP tickets");
    }
    private static void testHotPublications(Path root) throws IOException {
        UUID backend=UUID.randomUUID(), world=UUID.randomUUID();
        String dimension="minecraft:overworld";
        var blob=new ColumnBlob((byte)0,3,new byte[]{1,2,3});
        var store=new SnapshotStore(root,backend,1024);
        var hot=store.publish(world,dimension,10,20,blob).key();
        var cold=store.publish(world,dimension,11,20,blob).key();
        long now=System.currentTimeMillis();
        try(var paths=Files.walk(root.resolve(store.epoch().toString()))){
            for(var path:paths.filter(Files::isRegularFile).toList()){
                Files.setLastModifiedTime(path,FileTime.fromMillis(now-Duration.ofDays(31).toMillis()));
            }
        }
        store=new SnapshotStore(root,backend,1024);
        check(store.publish(world,dimension,10,20,blob).key().equals(hot),"reusing an aged object refreshes retention without changing its URL");
        check(store.expireOlderThan(now-Duration.ofDays(30).toMillis())==1,"cleanup removes only the inactive object");
        store=new SnapshotStore(root,backend,1024);
        check(store.read(hot).same(blob)&&store.publish(world,dimension,10,20,blob).key().equals(hot),"hot URL and immutable content survive cleanup and restart");
        try {
            store.read(cold);
            throw new AssertionError("inactive publication should expire");
        } catch (NoSuchFileException expected) { checks++; }
    }
    private static Path publicationFile(Path root,ColumnKey key) throws IOException {
        try(var files=Files.walk(root.resolve(key.epoch().toString()))){
            return files.filter(path->path.getFileName().toString().equals(key.revision()+".bin")).findFirst().orElseThrow();
        }
    }
    private static void testDamagedPublications(Path root) throws IOException {
        UUID backend=UUID.randomUUID(), world=UUID.randomUUID();
        String dimension="minecraft:overworld";
        var original=new ColumnBlob((byte)0,3,new byte[]{1,2,3});
        var changed=new ColumnBlob((byte)0,3,new byte[]{4,5,6});
        var store=new SnapshotStore(root,backend,1024);
        var hot=store.publish(world,dimension,10,20,original).key();
        var cold=store.publish(world,dimension,11,20,original).key();
        var oldHotFile=publicationFile(root,hot);
        Files.write(oldHotFile,new byte[0]);
        var repaired=store.publish(world,dimension,10,20,original).key();
        check(repaired.revision()>hot.revision()&&store.read(repaired).same(original),"warm publication recovers from truncation with a fresh revision");
        check(Files.size(oldHotFile)==0,"repair leaves the old URL's file untouched");

        var repairedFile=publicationFile(root,repaired);
        var mtime=Files.getLastModifiedTime(repairedFile);
        var corrupted=Files.readAllBytes(repairedFile);
        corrupted[corrupted.length-9]^=1;
        Files.write(repairedFile,corrupted);
        Files.setLastModifiedTime(repairedFile,mtime);
        try {
            store.read(repaired);
            throw new AssertionError("corrupted bytes should fail origin validation");
        } catch (IOException expected) { checks++; }
        var recovered=store.publish(world,dimension,10,20,original).key();
        check(recovered.revision()>repaired.revision()&&store.read(recovered).same(original),"origin validation invalidates hot metadata even when file size and timestamp match");

        Files.write(publicationFile(root,cold),new byte[0]);
        store=new SnapshotStore(root,backend,1024);
        var replaced=store.publish(world,dimension,11,20,changed).key();
        check(replaced.revision()>cold.revision()&&replaced.epoch().equals(cold.epoch())&&store.read(replaced).same(changed),"restart recovers a corrupt latest revision without resetting the publication epoch");
    }
    private static void testCleanupRechecksRetention(Path root) throws Exception {
        UUID backend=UUID.randomUUID(), world=UUID.randomUUID();
        String dimension="minecraft:overworld";
        var blob=new ColumnBlob((byte)0,3,new byte[]{1,2,3});
        var store=new SnapshotStore(root,backend,1024);
        var hot=store.publish(world,dimension,10,20,blob).key();
        long now=System.currentTimeMillis();
        Files.setLastModifiedTime(publicationFile(root,hot),FileTime.fromMillis(now-Duration.ofDays(31).toMillis()));
        var cleanup=new FutureTask<>(()->store.expireOlderThan(now-Duration.ofDays(30).toMillis()));
        var worker=new Thread(cleanup);
        synchronized(store){
            worker.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(worker.getState()!=Thread.State.BLOCKED&&!cleanup.isDone()&&System.nanoTime()<deadline)Thread.sleep(1);
            check(worker.getState()==Thread.State.BLOCKED&&Arrays.stream(worker.getStackTrace())
                    .anyMatch(frame->frame.getMethodName().equals("deleteExpired")),"the directory scan reaches deletion without holding the publication lock");
            check(store.publish(world,dimension,10,20,blob).key().equals(hot),"publication can refresh while the scanned candidate waits for deletion");
        }
        check(cleanup.get(5,TimeUnit.SECONDS)==0&&store.read(hot).same(blob),"cleaner rechecks retention and keeps a concurrently refreshed object");
    }
    private static void testQuotaRecount(Path root) throws IOException {
        var blob = new ColumnBlob((byte)0, 4096, new byte[4096]);
        var world = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        for (boolean truncate : new boolean[]{false,true}) {
            var directory = root.resolve(truncate ? "truncated" : "missing");
            var store = new SnapshotStore(directory, UUID.randomUUID(), 8192, 9000);
            var original = store.publish(world, dimension, 1, 2, blob).key();
            try {
                store.publish(world, dimension, 2, 2, blob);
                throw new AssertionError("the data already on disk should count against the quota");
            } catch (IOException e) {
                check(e.getMessage().contains("disk limit"),"recount enforces the real quota");
            }
            var file = publicationFile(directory, original);
            if (truncate) Files.write(file, new byte[0]);
            else Files.delete(file);
            var repair = store.publish(world, dimension, 1, 2, blob).key();
            check(repair.revision() > original.revision() && repair.epoch().equals(original.epoch()) && store.read(repair).same(blob),
                    "quota is corrected without restart even after a recent check (" + directory.getFileName() + ")");
        }
    }
    private static void testSelectiveInvalidation(Path root) throws Exception {
        var store = new SnapshotStore(root, UUID.randomUUID(), 1024);
        var world = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        var first = new ColumnBlob((byte)0, 1, new byte[]{1});
        var second = new ColumnBlob((byte)0, 1, new byte[]{2});
        var superseded = store.publish(world, dimension, 1, 1, first).key();
        var latest = store.publish(world, dimension, 1, 1, second).key();
        var inactive = store.publish(world, dimension, 2, 2, first).key();
        var latestFile = publicationFile(root, latest);
        var inactiveFile = publicationFile(root, inactive);
        long cutoff = System.currentTimeMillis() - Duration.ofDays(30).toMillis();
        for (var old : List.of(publicationFile(root, superseded), inactiveFile)) {
            Files.setLastModifiedTime(old, FileTime.fromMillis(cutoff - 1000));
        }
        check(store.expireOlderThan(cutoff) == 2,"cleanup deletes both a superseded and an inactive revision");
        var field = SnapshotStore.class.getDeclaredField("hot");
        field.setAccessible(true);
        var cache = (Map<?,?>) field.get(store);
        check(cache.containsKey(latestFile) && !cache.containsKey(inactiveFile),"cleanup preserves the hot body for the active revision");
        check(store.publish(world, dimension, 1, 1, second).key().equals(latest),"cleanup preserves the URL for the active revision");
    }
    private static void testCleanupDuringPublication(Path root) throws Exception {
        var store = new SnapshotStore(root, UUID.randomUUID(), 1024);
        var world = UUID.randomUUID();
        var writes = new FutureTask<Void>(() -> {
            for (int i = 0; i < 160; i++) {
                var blob = new ColumnBlob((byte)0, 1, new byte[]{(byte)i});
                store.publish(world, "minecraft:overworld", 1, 1, blob);
            }
            return null;
        });
        var worker = new Thread(writes);
        worker.start();
        int removed = 0;
        do {
            removed += store.expireOlderThan(0);
        } while (!writes.isDone());
        writes.get();
        var body = new ColumnBlob((byte)0, 1, new byte[]{(byte)159});
        var latest = store.publish(world, "minecraft:overworld", 1, 1, body).key();
        check(removed == 0 && latest.revision() == 160 && store.read(latest).same(body),
                "cleanup completes during repeated atomic publication and preserves fresh objects");
    }
    private static void testLargeBatch() {
        var ledger=new DownloadLedger(4096,4096);
        UUID id=UUID.randomUUID();
        String dimension="minecraft:overworld";
        for(int i=0;i<4096;i++){
            ledger.reserve(new ColumnKey(id,id,id,dimension,-i,i,1),1,100,(byte)1,(byte)0,1);
        }
        check(ledger.columns()==4096&&ledger.rawBytes()==4096,"full batch fits the normal queue limit");
        int matched=0;
        for(int i=0;i<4096;i++){
            if(ledger.find(dimension,DownloadLedger.pack(-i,i%2==0?i:-i))!=null)matched++;
        }
        check(matched==2048,"full-size batch distinguishes pending and native positions including negative coordinates");
        for(int i=0;i<4096;i+=2)ledger.release(ledger.find(dimension,DownloadLedger.pack(-i,i)));
        check(ledger.columns()==2048&&ledger.rawBytes()==2048,"bulk cancellation releases exactly half of the queued budget");
        for(int i=0;i<4096;i++){
            if((ledger.find(dimension,DownloadLedger.pack(-i,i))!=null)!=(i%2==1))throw new AssertionError("incorrect batch membership");
        }
        check(ledger.find("minecraft:the_nether",DownloadLedger.pack(-1,1))==null&&ledger.reset().size()==2048,"batch index preserves dimension and resets on session change");
    }
    private static void testConnectionPolicy()throws Exception{
        for(String host:List.of("192.168.0.0","192.168.0.1","192.168.42.51","192.168.255.255"))
            check(ConnectionPolicy.nativeVss(new InetSocketAddress(InetAddress.getByName(host),25565)),"192.168/16 player source selects original VSS: "+host);
        for(String host:List.of("192.167.255.255","192.169.0.1","10.0.0.1","172.16.0.1","127.0.0.1","203.0.113.1","2001:db8::1"))
            check(!ConnectionPolicy.nativeVss(new InetSocketAddress(InetAddress.getByName(host),25565)),"other player sources keep CDN: "+host);
        byte[] mapped=new byte[16];mapped[10]=(byte)255;mapped[11]=(byte)255;mapped[12]=(byte)192;mapped[13]=(byte)168;mapped[15]=1;
        check(ConnectionPolicy.nativeVss(new InetSocketAddress(Inet6Address.getByAddress(null,mapped,-1),25565)),"mapped IPv4 LAN source selects original VSS");
        var named=InetAddress.getByAddress("public-looking.example.net",new byte[]{(byte)192,(byte)168,1,2});
        check(ConnectionPolicy.nativeVss(new InetSocketAddress(named,25565)),"player source is classified by numeric address, independent of hostname");
        named=InetAddress.getByAddress("192.168.fake.example.net",new byte[]{(byte)203,0,113,1});
        check(!ConnectionPolicy.nativeVss(new InetSocketAddress(named,25565)),"hostname prefix cannot make a public player source bypass CDN");
        check(!ConnectionPolicy.nativeVss(InetSocketAddress.createUnresolved("192.168.1.1",25565)),"unresolved endpoint does not trigger DNS or guess LAN routing");
        check(!ConnectionPolicy.nativeVss(null),"missing endpoint keeps existing transport choice");
    }
    private static void testBridgeConfiguration()throws Exception{
        UUID challenge=UUID.randomUUID(),old=UUID.randomUUID();
        URI root=ControlProtocol.cdnRoot("https://lod.example.net");
        byte[] hello=ControlProtocol.nativeFrame(ControlProtocol.body(ControlProtocol.HELLO,out->Wire.uuid(out,challenge)));
        check(hello.length==22&&hello[0]==21&&hello[4]==2&&hello[5]==1,"Velocity frames match NeoForge VarInt byte-array and v2 control header");
        check(ControlProtocol.helloChallenge(hello).equals(challenge),"bridge reads current world challenge from client hello");
        try(var in=ControlProtocol.reader(ControlProtocol.nativeBody(ControlProtocol.configuration(challenge,root)))){
            check(in.readUnsignedByte()==ControlProtocol.CONFIG&&Wire.uuid(in).equals(challenge)&&in.readUTF().equals(root.toString())&&in.read()==-1,"bridge CONFIG carries its configured CDN URL and echoed challenge");
        }
        byte[] truncated=Arrays.copyOf(hello,hello.length-1);
        try{ControlProtocol.helloChallenge(truncated);throw new AssertionError();}catch(IOException expected){checks++;}
        byte[] trailing=Arrays.copyOf(hello,hello.length+1);
        try{ControlProtocol.helloChallenge(trailing);throw new AssertionError();}catch(IOException expected){checks++;}
        for(String bad:List.of("file:///tmp/lod","https://user:secret@example.net","https://lod.example.net/path","https://lod.example.net?token=1")){
            try{ControlProtocol.cdnRoot(bad);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        }
        var n=new SessionNegotiation();
        var identity=new SessionNegotiation.Identity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",42);
        n.begin(challenge);n.identity(challenge,identity);
        check(n.agreed()==null,"backend identity alone cannot enable CDN transport");
        n.configuration(old,root);check(n.agreed()==null,"previous world configuration is ignored");
        n.configuration(challenge,root);check(n.agreed().identity().equals(identity)&&n.agreed().root().equals(root),"identity then bridge configuration enables correct endpoint");
        n.begin(challenge);n.configuration(challenge,root);check(n.agreed()==null,"bridge config alone cannot enable backend interception");
        n.identity(challenge,identity);check(n.agreed()!=null,"configuration then identity is also accepted");
        n.clear();n.configuration(challenge,root);n.identity(challenge,identity);check(n.agreed()==null,"timeout/fallback cannot be revived by late config or identity");
        n.begin(old);n.identity(old,identity);n.configuration(challenge,root);check(n.agreed()==null,"switch starts without inheriting previous endpoint");
        check(Arrays.equals(ControlProtocol.configuration(challenge,root,false),ControlProtocol.configuration(challenge,root)),"public configuration remains byte-compatible with alpha.2 clients");
        try(var in=ControlProtocol.reader(ControlProtocol.nativeBody(ControlProtocol.configuration(challenge,root,true)))){
            check(in.readUnsignedByte()==ControlProtocol.DIRECT_CONFIG&&Wire.uuid(in).equals(challenge)&&in.readUTF().equals(root.toString())&&in.read()==-1,"bridge direct configuration binds route and root to the current challenge");
        }
        n.begin(challenge);n.configuration(challenge,root,true);
        check(n.agreed()==null,"LAN route waits for backend identity before unpausing ingestion");
        n.identity(challenge,identity);
        check(n.agreed().nativeVss()&&n.agreed().identity().equals(identity),"LAN configuration then identity retains scoped native transport");
        n.begin(challenge);n.identity(challenge,identity);
        check(n.agreed()==null,"identity alone cannot choose a route from the client join hostname");
        n.configuration(challenge,root,true);
        check(n.agreed().nativeVss(),"identity then LAN configuration also selects original VSS");
        n.configuration(old,root,false);
        check(n.agreed().nativeVss(),"stale public configuration cannot override current LAN route");
        n.begin(old);n.configuration(old,root);n.identity(old,identity);
        check(!n.agreed().nativeVss(),"next public session clears the previous LAN route");
        n.clear();n.configuration(old,root,true);n.identity(old,identity);
        check(n.agreed()==null,"late LAN messages cannot revive a cleared session");
        n.begin(challenge);n.identity(challenge,identity);n.configuration(challenge,root,true);
        check(n.agreed().nativeVss(),"rejoining LAN after CDN starts with the newly declared route");
    }
}
