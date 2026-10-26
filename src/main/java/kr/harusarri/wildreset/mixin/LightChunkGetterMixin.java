package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.WildResetEngine;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures ServerChunkCache#getChunkForLighting always finds in-memory chunks in updatingChunkMap
 * or Fabric's LoadedChunksCache when ScalableLux runs synchronous relighting on the main thread.
 */
@Mixin(ServerChunkCache.class)
public abstract class LightChunkGetterMixin {
    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "getChunkForLighting", at = @At("RETURN"), cancellable = true)
    private void wildreset$fallbackGetChunkForLightingOnMainThread(
        int cx,
        int cz,
        CallbackInfoReturnable<LightChunk> cir
    ) {
        if (cir.getReturnValue() == null
            && Boolean.TRUE.equals(WildResetEngine.IS_RELIGHTING_ON_MAIN_THREAD.get())
            && this.level != null
            && this.level.getServer().isSameThread()) {
            long packed = ChunkPos.pack(cx, cz);
            ChunkHolder holder = this.chunkMap.getUpdatingChunkIfPresent(packed);
            if (holder != null) {
                ChunkAccess chunk = holder.getChunkIfPresentUnchecked(ChunkStatus.INITIALIZE_LIGHT.getParent());
                if (chunk != null) {
                    cir.setReturnValue(chunk);
                    return;
                }
            }
            LevelChunk loaded = WildResetEngine.resolveInMemoryLevelChunk(this.level, cx, cz);
            if (loaded != null) {
                cir.setReturnValue(loaded);
            }
        }
    }
}
