package cn.mctogether.vsscdn.client;

import cn.mctogether.vsscdn.common.*;
import cn.mctogether.vsscdn.common.DownloadLedger.Ticket;
import cn.mctogether.vsscdn.neo.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static cn.mctogether.vsscdn.common.DownloadLedger.pack;

public final class CdnClient {
    private CdnClient(){}
    private static final Logger LOG=LoggerFactory.getLogger("VSS-CDN-Client");
    private record Session(UUID backend,UUID world,UUID epoch,String dimension,long nonce,URI base,Object level,Object manager){}
    private static final DownloadLedger LEDGER=new DownloadLedger(4096,1024L*1024*1024);
    private static final LinkedHashSet<Ticket> waiting=new LinkedHashSet<>();
    private static final Map<Ticket,CompletableFuture<?>> active=new HashMap<>();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static volatile Session session;
    private static final SessionNegotiation NEGOTIATION=new SessionNegotiation();
    private static UUID challenge;
    private static Object levelIdentity;
    private static int ticks;
    private static long downloadedColumns,downloadedBytes;
    private static boolean negotiating;
    private static boolean enabled,injecting,filtering;
    public static void begin(){clear();challenge=UUID.randomUUID();NEGOTIATION.begin(challenge);negotiating=true;levelIdentity=Minecraft.getInstance().level;ticks=0;}
    public static void levelChanged(Object level){
        if(level==levelIdentity)return;
        if(level==null){disconnect();return;}
        begin();levelIdentity=level;
    }
    public static void disconnect(){clear();challenge=null;negotiating=false;levelIdentity=null;}
    private static void clear(){int count=LEDGER.columns();if(count>0)LOG.info("Cancelled {} pending HTTP columns on connection/level change",count);cancelAll();NEGOTIATION.clear();session=null;enabled=false;downloadedColumns=0;downloadedBytes=0;}
    public static int pendingCount(){return LEDGER.columns();}
    public static long pendingBytes(){return LEDGER.rawBytes();}
    public static void tick(){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.level==null)return;
        if(levelIdentity!=mc.level){begin();levelIdentity=mc.level;}
        ticks++;
        if(negotiating){
            if(ticks==1||ticks==20||ticks==40)try{PacketDistributor.sendToServer(CdnPayload.hello(challenge));}catch(RuntimeException ignored){}
            if(ticks>=100)fallback("Bridge configuration or backend identity not received within 5s");
        }
        syncSession();pump();
    }
    public static void receive(CdnPayload p){
        try(var in=p.reader()){
            int kind=in.readUnsignedByte();
            if(kind==CdnPayload.CONFIG||kind==CdnPayload.DIRECT_CONFIG){
                UUID echo=Wire.uuid(in);if(!NEGOTIATION.accepts(echo)||!negotiating)return;
                URI root=ControlProtocol.cdnRoot(in.readUTF());if(in.read()!=-1)throw new IOException("Trailing bridge config bytes");
                NEGOTIATION.configuration(echo,root,kind==CdnPayload.DIRECT_CONFIG);activate();
            }else if(kind==CdnPayload.SESSION){
                UUID echo=Wire.uuid(in);if(!NEGOTIATION.accepts(echo)||!negotiating)return;
                var identity=new SessionNegotiation.Identity(Wire.uuid(in),Wire.uuid(in),Wire.uuid(in),in.readUTF(),in.readLong());
                if(in.read()!=-1||Minecraft.getInstance().level==null||!identity.dimension().equals(Minecraft.getInstance().level.dimension().location().toString()))return;
                NEGOTIATION.identity(echo,identity);activate();
            }else if(kind==CdnPayload.REFERENCE){
                long nonce=in.readLong();ColumnKey key=ColumnKey.read(in);long stamp=in.readLong();byte source=in.readByte(),codec=in.readByte();int raw=in.readInt(),length=in.readInt();
                var s=syncSession();
                if(in.read()!=-1||s==null||nonce!=s.nonce||!key.backend().equals(s.backend)||!key.world().equals(s.world)||!key.epoch().equals(s.epoch)||!key.dimension().equals(s.dimension))return;
                long position=pack(key.x(),key.z());
                if(!enabled){retry(s,position);return;}
                if(stamp<=0||raw<1||raw>ColumnBlob.MAX_BYTES||length<1||length>ColumnBlob.MAX_BYTES||(codec!=0&&codec!=1))throw new IOException("Invalid column reference");
                var dim=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(key.dimension()));
                if(LssAccess.olderThanReceived(dim,key.x(),key.z(),stamp))return;
                var pending=LEDGER.find(key.dimension(),position);
                if(pending!=null&&(key.revision()<pending.key().revision()||stamp<pending.timestamp()))return;
                if(pending==null||!pending.key().equals(key)){
                    if(pending!=null)cancel(pending);
                    if(LssAccess.reuseReceived(dim,key.x(),key.z(),stamp))return;
                }
                var ticket=LEDGER.reserve(key,raw,stamp,source,codec,length);
                if(ticket==null){fallback("HTTP pending budget exhausted");retry(s,position);return;}
                if(ticket!=pending){waiting.add(ticket);pump();}
            }
        }catch(Exception e){fallback("Rejected CDN message: "+e.getMessage());}
    }
    private static void activate(){
        var agreed=NEGOTIATION.agreed();if(!negotiating||agreed==null)return;
        var id=agreed.identity();var level=Minecraft.getInstance().level;
        if(level==null||!id.dimension().equals(level.dimension().location().toString()))return;
        cancelAll();
        session=new Session(id.backend(),id.world(),id.epoch(),id.dimension(),id.nonce(),agreed.root(),level,LssAccess.manager());
        boolean direct=agreed.nativeVss();
        enabled=!direct;negotiating=false;
        // HTTP publication identifiers never change the native VSS/Voxy world or cache keys.
        PacketDistributor.sendToServer(direct?CdnPayload.disable():CdnPayload.ready(challenge,id.nonce()));
        if(direct)LOG.info("LAN direct session active: backend={} world={} epoch={} (original VSS; selected by Velocity bridge)",id.backend(),id.world(),id.epoch());
        else LOG.info("CDN session active: backend={} world={} epoch={} cdn={} (configured by Velocity bridge)",id.backend(),id.world(),id.epoch(),agreed.root());
    }
    /** Retire HTTP work when VSS replaces its manager, without changing VSS itself. */
    private static Session syncSession(){
        Session s=session;
        if(s==null||s.level!=Minecraft.getInstance().level)return null;
        Object manager=LssAccess.manager();
        if(manager!=s.manager){
            cancelAll();
            session=s=new Session(s.backend,s.world,s.epoch,s.dimension,s.nonce,s.base,s.level,manager);
        }
        return s;
    }
    private static void pump(){
        while(enabled&&active.size()<8&&!waiting.isEmpty()){
            var ticket=waiting.removeFirst();
            var request=HttpRequest.newBuilder(session.base.resolve(ticket.key().path())).header("Accept","application/octet-stream").GET().build();
            download(session,ticket,request,0);
        }
    }
    private static void download(Session owner,Ticket ticket,HttpRequest request,int retries){
        var f=HTTP.sendAsync(request,info->new LimitedBodySubscriber(ColumnBlob.MAX_BYTES+2048));active.put(ticket,f);
        // Time the full body while keeping the original request cancellable.
        f.copy().orTimeout(10,TimeUnit.SECONDS).whenComplete((response,error)->{
            if(error instanceof TimeoutException)f.cancel(true);
            Minecraft.getInstance().execute(()->{
                active.remove(ticket);
                if(!current(owner,ticket))return;
                if(error==null&&response.statusCode()==503&&retries<2){
                    // Keep the slot and the ticket while waiting for the next attempt.
                    var delay=CompletableFuture.delayedExecutor(250L<<retries,TimeUnit.MILLISECONDS);
                    active.put(ticket,CompletableFuture.runAsync(()->Minecraft.getInstance().execute(()->{
                        if(current(owner,ticket))download(owner,ticket,request,retries+1);
                        else active.remove(ticket);
                    }),delay));
                    return;
                }
                try{
                    if(error!=null)throw new IOException("HTTP request failed: "+error,error);
                    if(response.statusCode()==410)throw new IOException("Publication expired; requesting current VSS data");
                    if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode());
                    var e=Envelope.decode(response.body(),ticket.key());
                    if(e.blob().codec()!=ticket.codec()||e.blob().rawSize()!=ticket.rawSize()||e.blob().size()!=ticket.length())throw new IOException("Body does not match reference");
                    var dim=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension));
                    injecting=true;
                    try{LssAccess.receive(ticket.key().x(),ticket.key().z(),dim,ticket.timestamp(),ticket.source(),ticket.codec(),e.blob().bytes(),e.blob().rawSize());}
                    finally{injecting=false;}
                    downloadedColumns++;downloadedBytes+=response.body().length;
                    if(downloadedColumns==1||downloadedColumns%1000==0)LOG.info("CDN delivered {} columns / {} HTTP bytes to VSS (last codec={}, raw={})",downloadedColumns,downloadedBytes,ticket.codec(),e.blob().rawSize());
                }catch(Exception x){fallback(x.toString());}finally{LEDGER.release(ticket);}
            });
        });
    }
    private static boolean current(Session owner,Ticket ticket){
        if(owner!=session||owner.level!=Minecraft.getInstance().level||!LEDGER.current(ticket))return false;
        if(owner.manager==LssAccess.manager())return true;
        LEDGER.release(ticket);
        return false;
    }
    private static void retry(Session owner,long... positions){
        var level=Minecraft.getInstance().level;
        if(owner==null||level!=owner.level)return;
        try{LssAccess.retry(owner.manager,level.dimension(),positions);}
        catch(RuntimeException e){LOG.warn("VSS retry failed: {}",e.toString());}
    }
    public static void fallback(String reason){
        if(!enabled&&!negotiating)return;
        var owner=session;
        enabled=false;negotiating=false;NEGOTIATION.clear();
        try{PacketDistributor.sendToServer(CdnPayload.disable());}catch(RuntimeException ignored){}
        var cancelled=cancelAll();
        if(!cancelled.isEmpty()){
            retry(owner,cancelled.stream().map(Ticket::key).mapToLong(key->pack(key.x(),key.z())).toArray());
        }
        LOG.warn("CDN paused for this backend session; original VSS will retry: {}",reason);
    }
    private static List<Ticket> cancelAll(){
        var cancelled=LEDGER.reset();var futures=List.copyOf(active.values());active.clear();waiting.clear();
        for(var f:futures)f.cancel(true);
        return cancelled;
    }
    private static void cancel(Ticket ticket){
        LEDGER.release(ticket);
        waiting.remove(ticket);
        var f=active.remove(ticket);if(f!=null)f.cancel(true);
    }
    public static void dirty(Object payload){
        var s=syncSession();if(!enabled||s==null)return;
        for(long position:(long[])LssAccess.call(payload,"dirtyPositions")){
            var pending=LEDGER.find(s.dimension,position);
            if(pending!=null)cancel(pending);
        }
    }
    public static boolean nativeColumn(Object payload){
        if(injecting||!enabled)return false;var s=syncSession();if(s==null)return false;
        int x=(Integer)LssAccess.call(payload,"chunkX"),z=(Integer)LssAccess.call(payload,"chunkZ");
        @SuppressWarnings("unchecked") var dim=(ResourceKey<Level>)LssAccess.call(payload,"dimension");
        if(!s.dimension.equals(dim.location().toString()))return false;
        long stamp=(Long)LssAccess.call(payload,"columnTimestamp");
        if(LssAccess.olderThanReceived(dim,x,z,stamp))return true;
        var pending=LEDGER.find(s.dimension,pack(x,z));
        if(pending!=null&&stamp>0&&pending.timestamp()>stamp)return true;
        boolean reused=LssAccess.reuseReceived(dim,x,z,stamp);
        if(pending!=null)cancel(pending);
        return reused;
    }
    public static boolean filterBatch(Object payload){
        if(filtering||!enabled||syncSession()==null||LEDGER.columns()==0)return false;
        byte[] types=(byte[])LssAccess.call(payload,"responseTypes");long[] positions=(long[])LssAccess.call(payload,"packedPositions");int count=(Integer)LssAccess.call(payload,"count");
        byte[] filteredTypes=new byte[count];long[] filteredPositions=new long[count];int n=0;
        for(int i=0;i<count;i++){if(LEDGER.find(session.dimension,positions[i])!=null)continue;filteredTypes[n]=types[i];filteredPositions[n++]=positions[i];}
        if(n==count)return false;
        if(n>0)try{Object filtered=payload.getClass().getConstructor(byte[].class,long[].class,int.class).newInstance(filteredTypes,filteredPositions,n);
            filtering=true;try{LssAccess.call(LssAccess.type("dev.vox.lss.networking.client.ClientNetGlue"),"onBatchResponseFrame",filtered);}finally{filtering=false;}}
        catch(ReflectiveOperationException e){fallback("Unsupported batch response ABI");return false;}
        return true;
    }
}
