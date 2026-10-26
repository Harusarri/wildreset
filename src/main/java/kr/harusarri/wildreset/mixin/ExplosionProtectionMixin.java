package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Prevents TNT, Creepers, End Crystals, Wither skulls, Beds, and Respawn Anchors
 * from destroying locked evacuated chests via explosions.
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionProtectionMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void wildreset$filterLockedEvacuatedChestsFromExplosion(List<BlockPos> positions, CallbackInfo ci) {
        positions.removeIf(pos -> EvacuatedChestRegistry.isLockedEvacuatedChest(this.level, pos));
    }

    @Inject(method = "createFire", at = @At("HEAD"))
    private void wildreset$filterLockedEvacuatedChestsFromFire(List<BlockPos> positions, CallbackInfo ci) {
        positions.removeIf(pos -> EvacuatedChestRegistry.isLockedEvacuatedChest(this.level, pos));
    }
}
