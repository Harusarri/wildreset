package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.CleanChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelStructureMixin {
    @Inject(method = "structureManager", at = @At("HEAD"), cancellable = true)
    private void wildreset$onGetStructureManager(CallbackInfoReturnable<StructureManager> cir) {
        StructureManager isolated = CleanChunkCache.getThreadIsolatedStructureManager();
        if (isolated != null) {
            cir.setReturnValue(isolated);
        }
    }
}
