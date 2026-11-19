package cn.mctogether.vsscdn.server;

import cn.mctogether.vsscdn.common.*;
import cn.mctogether.vsscdn.neo.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Mod("vss_cdn_server")
public final class ServerMod {
    public static final Logger LOG=LoggerFactory.getLogger("VSS-CDN-Server");
    private static volatile RuntimeState runtime;
    private static final java.util.concurrent.atomic.AtomicLong lastPublicationWarning=new java.util.concurrent.atomic.AtomicLong();
    public record Peer(UUID world,String dimension,long session,UUID challenge,boolean active){}
    public static final class RuntimeState implements AutoCloseable{
        public final Properties config,worlds;
        public final Path worldFile;
        public final UUID backend;
        public final SnapshotStore snapshots;
        public final BackendTcpServer tcp;
        private final ScheduledExecutorService housekeeping=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"vss-cdn-publication-expiry");t.setDaemon(true);return t;});
        public final ConcurrentMap<UUID,Peer> peers=new ConcurrentHashMap<>();
        RuntimeState(MinecraftServer server)throws Exception{
            Path dir=net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("vss-cdn");
            Properties defaults=new Properties();defaults.setProperty("backend.id",UUID.randomUUID().toString());defaults.setProperty("backend.name","survival");
            defaults.setProperty("tcp.bind","127.0.0.1");defaults.setProperty("tcp.port","25591");defaults.setProperty("tcp.maxConnections","16");
            defaults.setProperty("bridge.secret",UUID.randomUUID().toString()+UUID.randomUUID());
            defaults.setProperty("snapshot.hotMiB","64");
            defaults.setProperty("snapshot.directory","vss-cdn-publications");
            defaults.setProperty("snapshot.maxGiB","64");defaults.setProperty("snapshot.retentionDays","30");
            config=PropertiesConfig.load(dir.resolve("server.properties"),defaults,"VSS CDN Server; backend.id must be unique on each independent backend");
            int days=PropertiesConfig.integer(config,"snapshot.retentionDays",1,3650);
            if(config.remove("cdn.baseUrl")!=null)PropertiesConfig.save(dir.resolve("server.properties"),config,"CDN URL is configured by the Velocity bridge; backend identity and TCP settings only");
            backend=UUID.fromString(config.getProperty("backend.id"));worldFile=dir.resolve("worlds.properties");
            worlds=PropertiesConfig.load(worldFile,new Properties(),"Persistent world identifiers; regenerate a value when cloning/resetting a world");
            Path publications=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(config.getProperty("snapshot.directory")).normalize();
            snapshots=new SnapshotStore(publications,backend,(long)PropertiesConfig.integer(config,"snapshot.hotMiB",1,1024)*1024*1024,(long)PropertiesConfig.integer(config,"snapshot.maxGiB",1,1048576)*1024*1024*1024);
            tcp=new BackendTcpServer(new InetSocketAddress(config.getProperty("tcp.bind"),PropertiesConfig.integer(config,"tcp.port",1,65535)),snapshots,backend,config.getProperty("bridge.secret"),PropertiesConfig.integer(config,"tcp.maxConnections",1,128));
            housekeeping.scheduleWithFixedDelay(()->{try{int n=snapshots.expireOlderThan(System.currentTimeMillis()-java.time.Duration.ofDays(days).toMillis());if(n>0)LOG.info("Expired {} immutable LOD publications",n);}catch(Exception e){LOG.warn("Publication cleanup failed: {}",e.toString());}},0,10,TimeUnit.MINUTES);
        }
        public synchronized UUID world(String dimension)throws Exception{
            String value=worlds.getProperty(dimension);
            if(value==null){value=UUID.randomUUID().toString();worlds.setProperty(dimension,value);PropertiesConfig.save(worldFile,worlds,"Persistent world identifiers");}
            return UUID.fromString(value);
        }
        @Override public void close(){peers.clear();housekeeping.shutdownNow();tcp.close();}
    }
    public ServerMod(IEventBus bus){
        bus.addListener(RegisterPayloadHandlersEvent.class,e->{var r=e.registrar(Integer.toString(ControlProtocol.VERSION)).optional().executesOn(HandlerThread.MAIN);
            r.playToServer(CdnPayload.C2S,CdnPayload.C2S_CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)control(player,p);});
            r.playToClient(CdnPayload.S2C,CdnPayload.S2C_CODEC,(p,c)->{});
        });
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{try{
            if(!LssAccess.supported())throw new IllegalStateException("Only LSS/VSS protocol 20 is supported");
            runtime=new RuntimeState(e.getServer());LOG.info("CDN backend {} listening at {}:{}",runtime.backend,runtime.config.getProperty("tcp.bind"),runtime.tcp.port());
        }catch(Exception x){LOG.error("CDN backend disabled; original VSS remains available",x);}});
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,e->{var r=runtime;runtime=null;if(r!=null)r.close();});
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,e->{var r=runtime;if(r!=null)r.peers.remove(e.getEntity().getUUID());});
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class,e->{var r=runtime;if(r!=null)r.peers.remove(e.getEntity().getUUID());});
    }
    private static void control(ServerPlayer player,CdnPayload payload){
        var r=runtime;if(r==null)return;
        try(var in=payload.reader()){
            int kind=in.readUnsignedByte();
            if(kind==CdnPayload.DISABLE){r.peers.remove(player.getUUID());return;}
            if(kind==CdnPayload.READY){
                UUID challenge=Wire.uuid(in);long nonce=in.readLong();if(in.read()!=-1)return;
                r.peers.computeIfPresent(player.getUUID(),(id,peer)->peer.challenge.equals(challenge)&&peer.session==nonce
                        &&peer.dimension.equals(player.level().dimension().location().toString())?new Peer(peer.world,peer.dimension,peer.session,peer.challenge,true):peer);
                return;
            }
            if(kind!=CdnPayload.HELLO)return;UUID challenge=Wire.uuid(in);if(in.read()!=-1)return;
            String dimension=player.level().dimension().location().toString();UUID world=r.world(dimension);
            Peer old=r.peers.get(player.getUUID());
            long session=old!=null&&old.dimension.equals(dimension)&&old.challenge.equals(challenge)?old.session:ThreadLocalRandom.current().nextLong();
            // Only the backend identity is declared here. The Velocity bridge owns the CDN URL.
            PacketDistributor.sendToPlayer(player,CdnPayload.message(false,CdnPayload.SESSION,out->{Wire.uuid(out,challenge);Wire.uuid(out,r.backend);Wire.uuid(out,world);Wire.uuid(out,r.snapshots.epoch());out.writeUTF(dimension);out.writeLong(session);}));
            boolean active=old!=null&&old.challenge.equals(challenge)&&old.dimension.equals(dimension)&&old.active;
            r.peers.put(player.getUUID(),new Peer(world,dimension,session,challenge,active));
        }catch(Exception e){LOG.warn("Rejected CDN handshake: {}",e.toString());}
    }
    /** Called only from the existing VSS processing thread, after terrain has been encoded. */
    public static boolean intercept(Object state,int x,int z,String dimension,long timestamp,long order,Object bytes,byte source){
        var r=runtime;if(r==null)return false;Peer peer=r.peers.get(LssAccess.playerId(state));
        if(peer==null||!peer.active||!peer.dimension.equals(dimension))return false;
        try{
            int raw=(Integer)LssAccess.call(bytes,"rawSize");if(raw<256||raw>2_000_000)return false;
            byte[] frame=(byte[])LssAccess.call(bytes,"frame");byte codec=(byte)(frame==null?0:1);
            byte[] shipped=frame!=null?frame:(byte[])LssAccess.call(bytes,"raw");
            var published=r.snapshots.publish(peer.world,dimension,x,z,new ColumnBlob(codec,raw,shipped));
            var reference=CdnPayload.message(false,CdnPayload.REFERENCE,out->{out.writeLong(peer.session);published.key().write(out);out.writeLong(timestamp);out.writeByte(source);out.writeByte(codec);out.writeInt(raw);out.writeInt(shipped.length);});
            LssAccess.enqueue(state,reference,reference.body().length+32,order,x,z);return true;
        }catch(Exception e){long now=System.nanoTime(),last=lastPublicationWarning.get();if(now-last>java.time.Duration.ofSeconds(15).toNanos()&&lastPublicationWarning.compareAndSet(last,now))LOG.warn("CDN publication refused; sending original VSS columns (warnings limited to once per 15s): {}",e.toString());return false;}
    }
}
