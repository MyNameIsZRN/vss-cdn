package cn.mctogether.vsscdn.neo;

import cn.mctogether.vsscdn.common.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.io.*;
import java.util.UUID;

/** One optional channel in each direction. Typed envelopes do not modify VSS's frozen payloads. */
public record CdnPayload(boolean serverbound, byte[] body) implements CustomPacketPayload {
    public static final int HELLO=ControlProtocol.HELLO, SESSION=ControlProtocol.SESSION, REFERENCE=ControlProtocol.REFERENCE,
            DISABLE=ControlProtocol.DISABLE, CONFIG=ControlProtocol.CONFIG, READY=ControlProtocol.READY, DIRECT_CONFIG=ControlProtocol.DIRECT_CONFIG;
    public static final Type<CdnPayload> C2S=new Type<>(ResourceLocation.fromNamespaceAndPath("vsscdn","control"));
    public static final Type<CdnPayload> S2C=new Type<>(ResourceLocation.fromNamespaceAndPath("vsscdn","delivery"));
    public static final StreamCodec<FriendlyByteBuf,CdnPayload> C2S_CODEC=StreamCodec.of((b,p)->b.writeByteArray(p.body),b->new CdnPayload(true,b.readByteArray(8192)));
    public static final StreamCodec<FriendlyByteBuf,CdnPayload> S2C_CODEC=StreamCodec.of((b,p)->b.writeByteArray(p.body),b->new CdnPayload(false,b.readByteArray(8192)));
    @Override public Type<? extends CustomPacketPayload> type(){return serverbound?C2S:S2C;}
    public static CdnPayload message(boolean serverbound,int kind,ControlProtocol.Writer writer){return new CdnPayload(serverbound,ControlProtocol.body(kind,writer));}
    public DataInputStream reader()throws IOException{return ControlProtocol.reader(body);}
    public static CdnPayload hello(UUID challenge){return message(true,HELLO,out->Wire.uuid(out,challenge));}
    public static CdnPayload disable(){return message(true,DISABLE,out->{});}
    public static CdnPayload ready(UUID challenge,long nonce){return message(true,READY,out->{Wire.uuid(out,challenge);out.writeLong(nonce);});}
}
