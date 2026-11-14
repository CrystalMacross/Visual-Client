package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.visualclient.modules.visuals.HideArmorModule;

/** Hide Armor: every worn armor piece of every humanoid goes through this helper before it is drawn. */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidMobRendererMixin {
	@Inject(method = "getEquipmentIfRenderable", at = @At("RETURN"), cancellable = true)
	private static void visualclient$hideArmor(LivingEntity entity, EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
		cir.setReturnValue(HideArmorModule.filter(entity, slot, cir.getReturnValue()));
	}
}
