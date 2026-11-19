package cn.mctogether.vsscdn.client.mixin;

import cn.mctogether.vsscdn.client.CdnClient;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo @Mixin(targets="dev.vox.lss.networking.client.ClientColumnProcessor",remap=false)
public abstract class VssBudgetMixin {
    @Inject(method="getQueuedCount",at=@At("RETURN"),cancellable=true,remap=false)
    private void vsscdn$count(CallbackInfoReturnable<Integer> ci){ci.setReturnValue(ci.getReturnValue()+CdnClient.pendingCount());}
    @Inject(method="getQueuedBytes",at=@At("RETURN"),cancellable=true,remap=false)
    private void vsscdn$bytes(CallbackInfoReturnable<Long> ci){ci.setReturnValue(ci.getReturnValue()+CdnClient.pendingBytes());}
}
