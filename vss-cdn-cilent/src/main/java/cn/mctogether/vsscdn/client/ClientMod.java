package cn.mctogether.vsscdn.client;

import cn.mctogether.vsscdn.neo.*;
import cn.mctogether.vsscdn.common.ControlProtocol;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

@Mod(value="vss_cdn_client",dist=Dist.CLIENT)
public final class ClientMod {
    public ClientMod(IEventBus bus){
        bus.addListener(RegisterPayloadHandlersEvent.class,e->{var r=e.registrar(Integer.toString(ControlProtocol.VERSION)).optional().executesOn(HandlerThread.MAIN);
            r.playToServer(CdnPayload.C2S,CdnPayload.C2S_CODEC,(p,c)->{});
            r.playToClient(CdnPayload.S2C,CdnPayload.S2C_CODEC,(p,c)->CdnClient.receive(p));
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,e->CdnClient.begin());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,e->CdnClient.disconnect());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,e->CdnClient.tick());
    }
}
