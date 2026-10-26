package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks BlockItem#place to:
 * 1. Block non-owners from placing Chests or Hoppers adjacent to a locked evacuated chest.
 * 2. Deliver the 3~4x Newbie ActionBar + Chat warning when placing blocks inside unprotected reset chunks.
 * 3. Track container ownership (chests, barrels, shulkers) without needing CoreProtect SQLite queries.
 */
@Mixin(BlockItem.class)
public abstract class BlockPlaceMixin {
    @Shadow
    public abstract Block getBlock();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void wildreset$onBeforePlaceBlock(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() instanceof ServerPlayer serverPlayer
            && context.getLevel() instanceof ServerLevel serverLevel) {
            BlockPos pos = context.getClickedPos();
            Block block = this.getBlock();
            if (block instanceof ChestBlock) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = pos.relative(dir);
                    if (EvacuatedChestRegistry.isLockedForPlayer(serverLevel, neighbor, serverPlayer)) {
                        EvacuatedChestRegistry.sendDenyMessageFor(serverLevel, neighbor, serverPlayer);
                        serverPlayer.containerMenu.sendAllDataToRemote();
                        cir.setReturnValue(InteractionResult.FAIL);
                        return;
                    }
                }
            } else if (block instanceof HopperBlock) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.relative(dir);
                    if (EvacuatedChestRegistry.isLockedForPlayer(serverLevel, neighbor, serverPlayer)) {
                        EvacuatedChestRegistry.sendDenyMessageFor(serverLevel, neighbor, serverPlayer);
                        serverPlayer.containerMenu.sendAllDataToRemote();
                        cir.setReturnValue(InteractionResult.FAIL);
                        return;
                    }
                }
            }
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void wildreset$onPlaceBlock(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }
        if (context.getPlayer() instanceof ServerPlayer serverPlayer
            && context.getLevel() instanceof ServerLevel serverLevel) {
            BlockPos pos = context.getClickedPos();
            BlockState placedState = serverLevel.getBlockState(pos);
            EvacuatedChestRegistry.onPlayerPlacedBlock(serverPlayer, serverLevel, pos, placedState);
        }
    }
}
