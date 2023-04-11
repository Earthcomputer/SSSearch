package net.earthcomputer.sssearch.mixin;

import net.earthcomputer.sssearch.SSSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow public abstract Slot getSlot(int i);

    @Inject(method = "initializeContents", at = @At("HEAD"))
    private void onInitializeContents(int i, List<ItemStack> list, ItemStack itemStack, CallbackInfo ci) {
        if (SSSearch.setupMode) {
            HitResult hitResult = Minecraft.getInstance().hitResult;
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
                ResourceKey<Level> dimension = Minecraft.getInstance().level.dimension();

                // filter out the player inv which will probably contain random shit
                List<ItemStack> itemsWeCareAbout = new ArrayList<>();
                for (int j = 0; j < list.size(); j++) {
                    if (!(getSlot(j).container instanceof Inventory)) {
                        itemsWeCareAbout.add(list.get(j));
                    }
                }

                SSSearch.setupContainer(GlobalPos.of(dimension, pos), itemsWeCareAbout);
            }
        }
    }
}
