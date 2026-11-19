package cn.mctogether.vsscdn.common;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** External smoke probe for an already-running real backend and Velocity plugin. */
public final class PlatformSmokeProbe {
    public static void main(String[] args)throws Exception{
        if(args.length<3||args.length>4)throw new IllegalArgumentException("serverConfig stateFile httpBase [tcpHost]");
        var config=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){config.load(in);}
        UUID backend=UUID.fromString(config.getProperty("backend.id"));
        UUID epoch;try(var in=new DataInputStream(Files.newInputStream(Path.of(args[1])))){
            if(in.readInt()!=Wire.MAGIC||!Wire.uuid(in).equals(backend))throw new AssertionError("Store scope");epoch=Wire.uuid(in);
        }
        var missing=new ColumnKey(backend,UUID.randomUUID(),epoch,"minecraft:overworld",17,-31,Long.MAX_VALUE);
        String host=args.length==4?args[3]:"127.0.0.1";
        try(var pool=new BackendPool(backend,new InetSocketAddress(host,Integer.parseInt(config.getProperty("tcp.port"))),config.getProperty("bridge.secret"),1)){
            try{pool.fetch(missing);throw new AssertionError("Expected unavailable publication");}catch(BackendPool.Gone expected){}
            if(pool.connected()!=1)throw new AssertionError("Backend connection not established");
        }
        try(var client=HttpClient.newHttpClient()){
            var response=client.send(HttpRequest.newBuilder(URI.create(args[2]+missing.path())).GET().build(),HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()!=410||!response.headers().firstValue("Cache-Control").orElse("").equals("no-store"))throw new AssertionError("Bridge route: HTTP "+response.statusCode());
        }
        System.out.println("PASS real platform smoke: authenticated backend connection and Velocity HTTP-to-backend route (410/no-store)");
    }
}
