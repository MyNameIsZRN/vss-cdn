package cn.mctogether.vsscdn.common;

import java.net.URI;
import java.util.UUID;

/** Join bridge configuration and backend identity in either arrival order, per world session. */
public final class SessionNegotiation {
    public record Identity(UUID backend,UUID world,UUID epoch,String dimension,long nonce){}
    public record Agreed(Identity identity,URI root,boolean nativeVss){}
    private UUID challenge;
    private Identity identity;
    private URI root;
    private boolean nativeVss;
    public void begin(UUID value){clear();challenge=value;}
    public void clear(){challenge=null;identity=null;root=null;nativeVss=false;}
    public boolean accepts(UUID echo){return challenge!=null&&challenge.equals(echo);}
    public void identity(UUID echo,Identity value){if(accepts(echo))identity=value;}
    public void configuration(UUID echo,URI value){configuration(echo,value,false);}
    public void configuration(UUID echo,URI value,boolean direct){if(accepts(echo)){root=value;nativeVss=direct;}}
    public Agreed agreed(){return challenge!=null&&identity!=null&&root!=null?new Agreed(identity,root,nativeVss):null;}
}
