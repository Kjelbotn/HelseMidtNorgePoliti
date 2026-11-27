package no.bangerfabrikken.helsemnpoliti.mixin;

import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl;
import no.bangerfabrikken.helsemnpoliti.PoliceData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Narrow FTB Chunks integration: bypasses only the INTERACT_BLOCK protection
 * check for doors when a registered police officer is currently on duty.
 */
@Mixin(value = ClaimedChunkManagerImpl.class, remap = false)
public abstract class ClaimedChunkManagerMixin {
    @Inject(
        method = "shouldPreventInteraction",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void helsemnpoliti$allowOnDutyPoliceDoorInteraction(
            Entity actor,
            InteractionHand hand,
            BlockPos pos,
            Protection protection,
            Entity targetEntity,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(actor instanceof ServerPlayer player)) return;
        if (protection != Protection.INTERACT_BLOCK) return;

        PoliceData data = PoliceData.get(player.getServer());
        if (!data.isOnDuty(player.getUUID()) || data.isJailed(player.getUUID())) return;

        // Do not let a block held in hand bypass claim protection for placement.
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) return;

        var block = player.level().getBlockState(pos).getBlock();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
            cir.setReturnValue(false);
        }
    }
}
