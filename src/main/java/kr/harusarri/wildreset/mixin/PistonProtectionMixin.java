package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.EvacuatedChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Prevents pistons from pushing or destroying locked evacuated chests.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonProtectionMixin {
    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private List<BlockPos> toPush;

    @Shadow
    @Final
    private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void wildreset$preventPistonMovingLockedEvacuatedChest(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        for (BlockPos pos : this.toPush) {
            if (EvacuatedChestRegistry.isLockedEvacuatedChest(this.level, pos)) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos pos : this.toDestroy) {
            if (EvacuatedChestRegistry.isLockedEvacuatedChest(this.level, pos)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
