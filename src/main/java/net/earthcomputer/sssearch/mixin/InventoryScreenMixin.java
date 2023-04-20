package net.earthcomputer.sssearch.mixin;

import net.earthcomputer.sssearch.SSSearchScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends EffectRenderingInventoryScreen<InventoryMenu> {
    public InventoryScreenMixin(InventoryMenu abstractContainerMenu, Inventory inventory, Component component) {
        super(abstractContainerMenu, inventory, component);
    }

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen;init()V", shift = At.Shift.AFTER))
    private void onInit(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.translatable("sssearch.name"), button -> minecraft.setScreen(new SSSearchScreen(this, minecraft.getConnection().enabledFeatures(), minecraft.player, minecraft.options.operatorItemsTab().get())))
            .bounds(width - 102, 2, 100, 20)
            .build());
    }
}
