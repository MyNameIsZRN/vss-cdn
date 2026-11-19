package cn.mctogether.vsscdn.bridge;

import cn.mctogether.vsscdn.common.*;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import org.slf4j.Logger;
import java.net.*;
import java.nio.file.*;
import java.util.*;

@Plugin(id="vss-cdn-bridge",name="VSS CDN Bridge",version="0.1.0-alpha.7",authors={"MCTogether"},description="Independent HTTP CDN origin and outbound VSS backend TCP routing")
public final class VssCdnBridge {
    private final Logger logger;
    private final Path directory;
    private final ProxyServer proxy;
    private static final MinecraftChannelIdentifier CONTROL=MinecraftChannelIdentifier.from(ControlProtocol.CONTROL_CHANNEL);
    private static final MinecraftChannelIdentifier DELIVERY=MinecraftChannelIdentifier.from(ControlProtocol.DELIVERY_CHANNEL);
    private volatile URI cdnRoot;
    private boolean lanDirect;
    private final Map<UUID,BackendPool> pools = new HashMap<>();
    private BridgeHttpServer http;
    @Inject public VssCdnBridge(ProxyServer proxy,Logger logger,@DataDirectory Path directory){this.proxy=proxy;this.logger=logger;this.directory=directory;}
    @Subscribe public void initialize(ProxyInitializeEvent event){
        try{
            Properties defaults=new Properties();
            defaults.setProperty("enabled","false"); defaults.setProperty("http.bind","127.0.0.1");defaults.setProperty("http.port","25590");defaults.setProperty("http.concurrency","32");
            defaults.setProperty("cdn.baseUrl","https://cdn.example.com");
            defaults.setProperty("lan.direct.enabled","true");
            defaults.setProperty("backend.names","survival");defaults.setProperty("backend.survival.id","00000000-0000-0000-0000-000000000001");
            defaults.setProperty("backend.survival.host","127.0.0.1");defaults.setProperty("backend.survival.port","25591");
            defaults.setProperty("backend.survival.secret","COPY_THE_SECRET_FROM_VSS_CDN_SERVER_CONFIG");defaults.setProperty("backend.survival.connections","4");
            Properties p=PropertiesConfig.load(directory.resolve("bridge.properties"),defaults,"VSS CDN Bridge: copy each backend id/port/secret from its server configuration before enabling");
            if(!Boolean.parseBoolean(p.getProperty("enabled"))){logger.info("VSS CDN bridge is configured but disabled; edit {}",directory.resolve("bridge.properties"));return;}
            URI root=ControlProtocol.cdnRoot(p.getProperty("cdn.baseUrl"));
            lanDirect=Boolean.parseBoolean(p.getProperty("lan.direct.enabled"));
            if(root.getHost().equalsIgnoreCase("cdn.example.com"))throw new IllegalArgumentException("Configure cdn.baseUrl on the Velocity bridge");
            for(String name:p.getProperty("backend.names","").split(",")){
                name=name.trim();if(name.isEmpty())continue;String prefix="backend."+name+".";
                UUID id=UUID.fromString(p.getProperty(prefix+"id"));String secret=p.getProperty(prefix+"secret","");
                if(secret.startsWith("COPY_"))throw new IllegalArgumentException("Configure the shared secret for "+name);
                var pool=new BackendPool(id,new InetSocketAddress(p.getProperty(prefix+"host"),PropertiesConfig.integer(p,prefix+"port",1,65535)),secret,PropertiesConfig.integer(p,prefix+"connections",1,32));
                if(pools.putIfAbsent(id,pool)!=null){pool.close();throw new IllegalArgumentException("Duplicate backend id "+id);}
            }
            if(pools.isEmpty())throw new IllegalArgumentException("No backends configured");
            http=new BridgeHttpServer(new InetSocketAddress(p.getProperty("http.bind"),PropertiesConfig.integer(p,"http.port",1,65535)),pools,PropertiesConfig.integer(p,"http.concurrency",1,256));
            proxy.getChannelRegistrar().register(CONTROL);cdnRoot=root;
            logger.info("VSS CDN HTTP origin listening on {}:{}; connecting outbound to {} backends",p.getProperty("http.bind"),http.port(),pools.size());
        }catch(Exception e){close();logger.error("VSS CDN bridge did not start; Minecraft proxy remains running",e);}
    }
    @Subscribe public void configuration(PluginMessageEvent event){
        URI root=cdnRoot;
        if(root==null||!CONTROL.equals(event.getIdentifier())||!event.getResult().isAllowed()
                ||!(event.getSource() instanceof Player player)||!(event.getTarget() instanceof ServerConnection))return;
        try{
            UUID challenge=ControlProtocol.helloChallenge(event.getData());
            if(challenge!=null){
                boolean direct=lanDirect&&ConnectionPolicy.nativeVss(player.getRemoteAddress());
                player.sendPluginMessage(DELIVERY,ControlProtocol.configuration(challenge,root,direct));
                if(direct)logger.debug("Selected original VSS for LAN player source {}",player.getRemoteAddress());
            }
            // Keep forwarding HELLO to the backend so its identity can be joined with this config.
        }catch(java.io.IOException e){logger.debug("Ignoring unsupported CDN client handshake: {}",e.toString());}
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event){close();}
    private void close(){cdnRoot=null;proxy.getChannelRegistrar().unregister(CONTROL);if(http!=null){http.close();http=null;}pools.values().forEach(BackendPool::close);pools.clear();}
}
