package cn.mctogether.vsscdn.common;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Velocity selects transport from the player's source address, independent of the join hostname. */
public final class ConnectionPolicy {
    private ConnectionPolicy(){}

    public static boolean nativeVss(SocketAddress remote){
        if(!(remote instanceof InetSocketAddress socket)||socket.getAddress()==null)return false;
        byte[] bytes=socket.getAddress().getAddress();
        if(bytes.length==16){
            // Also accept an explicitly represented IPv4-mapped IPv6 address.
            for(int i=0;i<10;i++)if(bytes[i]!=0)return false;
            if(bytes[10]!=(byte)255||bytes[11]!=(byte)255)return false;
        }else if(bytes.length!=4)return false;
        int offset=bytes.length-4;
        return (bytes[offset]&255)==192&&(bytes[offset+1]&255)==168;
    }
}
