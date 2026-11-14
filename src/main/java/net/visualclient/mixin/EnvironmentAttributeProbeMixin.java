package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.visualclient.modules.visuals.SkyColorModule;

/** In 1.21.11 sky, fog and cloud colours are environment attributes read through the camera's probe. */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class EnvironmentAttributeProbeMixin {
	@SuppressWarnings("unchecked")
	@Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
	private <Value> void visualclient$recolor(EnvironmentAttribute<Value> attribute, float partialTick, CallbackInfoReturnable<Value> cir) {
		Object key = attribute;
		Integer replacement = null;
		if (key == EnvironmentAttributes.SKY_COLOR) {
			replacement = SkyColorModule.modifySky((Integer) (Object) cir.getReturnValue());
		} else if (key == EnvironmentAttributes.FOG_COLOR) {
			replacement = SkyColorModule.modifyFog((Integer) (Object) cir.getReturnValue());
		} else if (key == EnvironmentAttributes.CLOUD_COLOR) {
			replacement = SkyColorModule.modifyCloud((Integer) (Object) cir.getReturnValue());
		}
		if (replacement != null) {
			cir.setReturnValue((Value) (Object) replacement);
		}
	}
}
