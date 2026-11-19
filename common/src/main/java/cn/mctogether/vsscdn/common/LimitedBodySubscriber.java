package cn.mctogether.vsscdn.common;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public LimitedBodySubscriber(int limit){this.limit=limit;}
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){
        for(ByteBuffer b:buffers){if(b.remaining()>limit-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("HTTP body exceeds limit"));return;}
            byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}
        subscription.request(1);
    }
    @Override public void onError(Throwable t){result.completeExceptionally(t);}
    @Override public void onComplete(){result.complete(bytes.toByteArray());}
}
