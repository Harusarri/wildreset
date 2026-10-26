package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.WildResetEngine;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapVisibilityMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    public abstract ChunkHolder getUpdatingChunkIfPresent(long chunkPos);

    @Inject(method = "getVisibleChunkIfPresent", at = @At("RETURN"), cancellable = true)
    private void wildreset$fallbackToUpdatingChunkOnMainThread(
        long chunkPos,
        CallbackInfoReturnable<ChunkHolder> cir
    ) {
        if (cir.getReturnValue() == null
            && Boolean.TRUE.equals(WildResetEngine.IS_RELIGHTING_ON_MAIN_THREAD.get())
            && this.level != null
            && this.level.getServer().isSameThread()) {
            ChunkHolder updating = this.getUpdatingChunkIfPresent(chunkPos);
            if (updating != null) {
                cir.setReturnValue(updating);
            }
        }
    }
}
