package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Prevents MC 26.3 Copper Golems (TransportItemsBetweenContainers AI behavior)
 * from targeting or extracting/inserting items on locked evacuated chests.
 */
@Mixin(TransportItemsBetweenContainers.class)
public abstract class CopperGolemProtectionMixin {
    @Inject(method = "isTargetValidToPick", at = @At("HEAD"), cancellable = true)
    private void wildreset$preventCopperGolemPickingLockedChest(
        PathfinderMob mob,
        Level level,
        BlockEntity blockEntity,
        Set<GlobalPos> visited,
        Set<GlobalPos> unreachable,
        AABB searchBox,
        CallbackInfoReturnable<TransportItemsBetweenContainers.TransportItemTarget> cir
    ) {
        if (blockEntity != null && EvacuatedChestRegistry.isLockedEvacuatedChest(level, blockEntity.getBlockPos())) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "isContainerLocked", at = @At("HEAD"), cancellable = true)
    private void wildreset$treatEvacuatedChestAsLockedForCopperGolem(
        TransportItemsBetweenContainers.TransportItemTarget target,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (target != null
            && target.blockEntity() != null
            && target.blockEntity().getLevel() != null
            && EvacuatedChestRegistry.isLockedEvacuatedChest(target.blockEntity().getLevel(), target.pos())) {
            cir.setReturnValue(true);
        }
    }
}
