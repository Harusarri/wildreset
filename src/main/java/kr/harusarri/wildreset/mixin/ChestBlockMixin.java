package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents any chest placed adjacent to an evacuated chest from merging into a Double Chest
 * (ChestType.LEFT / ChestType.RIGHT), closing the double-chest bypass vector.
 */
@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
    @Inject(method = "candidatePartnerFacing", at = @At("HEAD"), cancellable = true)
    private void wildreset$preventEvacuatedChestPartner(
        Level level,
        BlockPos pos,
        Direction direction,
        CallbackInfoReturnable<Direction> cir
    ) {
        if (EvacuatedChestRegistry.isEvacuatedChest(level, pos)
            || EvacuatedChestRegistry.isEvacuatedChest(level, pos.relative(direction))) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void wildreset$preventEvacuatedChestMergeOnUpdateShape(
        BlockState state,
        LevelReader levelReader,
        ScheduledTickAccess tickAccess,
        BlockPos pos,
        Direction direction,
        BlockPos neighborPos,
        BlockState neighborState,
        RandomSource random,
        CallbackInfoReturnable<BlockState> cir
    ) {
        if (direction.getAxis().isHorizontal() && levelReader instanceof Level level) {
            if (EvacuatedChestRegistry.isEvacuatedChest(level, pos)
                || EvacuatedChestRegistry.isEvacuatedChest(level, neighborPos)) {
                cir.setReturnValue(state.setValue(ChestBlock.TYPE, ChestType.SINGLE));
            }
        }
    }
}
