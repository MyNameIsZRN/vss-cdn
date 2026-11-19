package cn.mctogether.vsscdn.client.mixin;

import cn.mctogether.vsscdn.client.CdnClient;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo @Mixin(targets="dev.vox.lss.networking.client.ClientNetGlue",remap=false)
public abstract class VssReceiveMixin {
    @Inject(method="onDirtyColumnsFrame",at=@At("HEAD"),remap=false)
    private static void vsscdn$dirty(@Coerce Object payload,CallbackInfo ci){CdnClient.dirty(payload);}
    @Inject(method="onVoxelColumnFrame",at=@At("HEAD"),cancellable=true,remap=false)
    private static void vsscdn$native(@Coerce Object payload,CallbackInfo ci){if(CdnClient.nativeColumn(payload))ci.cancel();}
    @Inject(method="onBatchResponseFrame",at=@At("HEAD"),cancellable=true,remap=false)
    private static void vsscdn$batch(@Coerce Object payload,CallbackInfo ci){if(CdnClient.filterBatch(payload))ci.cancel();}
}
