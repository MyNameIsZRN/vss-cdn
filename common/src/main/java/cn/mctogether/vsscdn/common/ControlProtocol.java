package cn.mctogether.vsscdn.common;

import java.io.*;
import java.net.URI;
import java.util.UUID;

/** Game-channel protocol, separate from the immutable HTTP/TCP object format. */
public final class ControlProtocol {
    private ControlProtocol(){}
    public static final int VERSION=2, MAX_BODY=8192;
    public static final int HELLO=1, SESSION=2, REFERENCE=3, DISABLE=4, CONFIG=5, READY=6, DIRECT_CONFIG=7;
    public static final String CONTROL_CHANNEL="vsscdn:control", DELIVERY_CHANNEL="vsscdn:delivery";
    @FunctionalInterface public interface Writer{void write(DataOutputStream out)throws IOException;}
    public static byte[] body(int kind,Writer writer){
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(VERSION);out.writeByte(kind);writer.write(out);}
            if(bytes.size()>MAX_BODY)throw new IOException("Control body too large");return bytes.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static DataInputStream reader(byte[] body)throws IOException{
        if(body.length>MAX_BODY)throw new IOException("Control body too large");
        var in=new DataInputStream(new ByteArrayInputStream(body));if(in.readInt()!=VERSION)throw new IOException("Unknown CDN control protocol");return in;
    }
    /** Match FriendlyByteBuf.writeByteArray: VarInt length, followed by our control body. */
    public static byte[] nativeFrame(byte[] body){
        if(body.length>MAX_BODY)throw new IllegalArgumentException("Control body too large");
        var out=new ByteArrayOutputStream();int n=body.length;
        while((n&~0x7f)!=0){out.write((n&0x7f)|0x80);n>>>=7;}out.write(n);out.writeBytes(body);return out.toByteArray();
    }
    public static byte[] nativeBody(byte[] frame)throws IOException{
        if(frame.length>MAX_BODY+5)throw new IOException("Control frame too large");
        var in=new DataInputStream(new ByteArrayInputStream(frame));int n=0;
        for(int i=0;i<5;i++){int b=in.readUnsignedByte();n|=(b&0x7f)<<(7*i);
            if((b&0x80)==0){if(n<0||n>MAX_BODY||in.available()!=n)throw new IOException("Invalid control frame length");return in.readNBytes(n);}}
        throw new IOException("Invalid control VarInt");
    }
    public static UUID helloChallenge(byte[] frame)throws IOException{
        try(var in=reader(nativeBody(frame))){if(in.readUnsignedByte()!=HELLO)return null;UUID challenge=Wire.uuid(in);if(in.read()!=-1)throw new IOException("Trailing hello bytes");return challenge;}
    }
    public static byte[] configuration(UUID challenge,URI root){return nativeFrame(body(CONFIG,out->{Wire.uuid(out,challenge);out.writeUTF(root.toString());}));}
    /** Public CONFIG stays byte-identical for old clients; LAN uses an optional new message kind. */
    public static byte[] configuration(UUID challenge,URI root,boolean nativeVss){
        return nativeVss?nativeFrame(body(DIRECT_CONFIG,out->{Wire.uuid(out,challenge);out.writeUTF(root.toString());})):configuration(challenge,root);
    }
    public static URI cdnRoot(String value){
        URI uri=URI.create(value);
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null
                ||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
                ||!(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))throw new IllegalArgumentException("CDN address must be an HTTP(S) root URL");
        return uri;
    }
}
