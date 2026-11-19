package cn.mctogether.vsscdn.client.mixin;

import cn.mctogether.vsscdn.client.CdnClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancel obsolete HTTP work before the host replaces its level. World management stays native. */
@Mixin(Minecraft.class)
public abstract class ClientLevelMixin {
    @Inject(method="setLevel",at=@At("HEAD"))
    private void vsscdn$level(ClientLevel level,net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,CallbackInfo ci){CdnClient.levelChanged(level);}
}
