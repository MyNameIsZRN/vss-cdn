package cn.mctogether.vsscdn.server.mixin;

import cn.mctogether.vsscdn.server.ServerMod;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="dev.vox.lss.networking.server.FabricOffThreadProcessor",remap=false)
public abstract class VssSendMixin {
    // VSS only reaches frame() for the current wire dialect with compression negotiated.
    @Inject(method="buildAndEnqueueColumnPayload",at=@At(value="INVOKE",
            target="Ldev/vox/lss/common/processing/ColumnBytes;frame()[B"),cancellable=true,remap=false)
    private void vsscdn$send(@Coerce Object state,int x,int z,String dimension,long timestamp,long order,@Coerce Object bytes,int estimated,byte source,CallbackInfoReturnable<Boolean> ci){
        if(ServerMod.intercept(state,x,z,dimension,timestamp,order,bytes,source))ci.setReturnValue(true);
    }
}
