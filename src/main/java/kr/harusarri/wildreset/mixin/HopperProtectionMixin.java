package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks both HopperBlockEntity and MinecartHopper (under both Vanilla and Lithium's Hopper optimizations)
 * from extracting items from or inserting items into a locked evacuated chest.
 */
@Mixin(value = HopperBlockEntity.class, priority = 500)
public abstract class HopperProtectionMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void wildreset$blockHopperSuckFromLockedChest(
        Level level,
        Hopper hopper,
        CallbackInfoReturnable<Boolean> cir
    ) {
        double hx = hopper.getLevelX();
        double hy = hopper.getLevelY();
        double hz = hopper.getLevelZ();
        BlockPos pos1 = BlockPos.containing(hx, hy + 1.0, hz);
        if (EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos1)) {
            cir.setReturnValue(false);
            return;
        }
        BlockPos pos2 = BlockPos.containing(hx, hy + 0.5, hz);
        if (!pos2.equals(pos1) && EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos2)) {
            cir.setReturnValue(false);
            return;
        }
        BlockPos pos3 = BlockPos.containing(hx, hy + 1.5, hz);
        if (!pos3.equals(pos1) && EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos3)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void wildreset$blockHopperEjectIntoLockedChest(
        Level level,
        BlockPos pos,
        HopperBlockEntity hopper,
        CallbackInfoReturnable<Boolean> cir
    ) {
        BlockState state = hopper.getBlockState();
        if (state.hasProperty(HopperBlock.FACING)) {
            Direction facing = state.getValue(HopperBlock.FACING);
            if (EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos.relative(facing))) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "getBlockContainer", at = @At("HEAD"), cancellable = true)
    private static void wildreset$hideLockedChestFromGetBlockContainer(
        Level level,
        BlockPos pos,
        BlockState state,
        CallbackInfoReturnable<Container> cir
    ) {
        if (EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "getContainerAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/Container;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void wildreset$hideLockedChestFromGetContainerAt2(
        Level level,
        BlockPos pos,
        CallbackInfoReturnable<Container> cir
    ) {
        if (EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "getContainerAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;DDD)Lnet/minecraft/world/Container;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void wildreset$hideLockedChestFromGetContainerAt6(
        Level level,
        BlockPos pos,
        BlockState state,
        double x,
        double y,
        double z,
        CallbackInfoReturnable<Container> cir
    ) {
        if (EvacuatedChestRegistry.isLockedEvacuatedChest(level, pos)) {
            cir.setReturnValue(null);
        }
    }
}
