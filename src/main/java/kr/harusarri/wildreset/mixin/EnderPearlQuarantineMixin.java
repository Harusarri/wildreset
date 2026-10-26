package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.QuarantineAndFreezeManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Ender Pearl landing inside resetting wilderness chunks while Quarantine is active,
 * returning the pearl to the player and displaying a MiniMessage warning.
 */
@Mixin(ThrownEnderpearl.class)
public abstract class EnderPearlQuarantineMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void wildreset$onEnderPearlHit(HitResult result, CallbackInfo ci) {
        if (!QuarantineAndFreezeManager.isQuarantineActive() || result == null) {
            return;
        }
        ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;
        if (!(pearl.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 hitPos = result.getLocation();
        Entity owner = pearl.getOwner();
        ServerPlayer sp = owner instanceof ServerPlayer p ? p : null;
        if (QuarantineAndFreezeManager.shouldBlockTeleportTo(serverLevel, hitPos.x, hitPos.y, hitPos.z, sp)) {
            if (sp != null) {
                sp.getInventory().add(new ItemStack(Items.ENDER_PEARL));
            }
            pearl.discard();
            ci.cancel();
        }
    }
}
