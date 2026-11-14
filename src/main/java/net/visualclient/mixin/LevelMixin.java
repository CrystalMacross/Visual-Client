package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.visualclient.modules.visuals.WeatherModule;

/** Overrides the rain and thunder level, but only for the client side world. */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
	private void visualclient$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (WeatherModule.active() && (Object) this instanceof ClientLevel) {
			cir.setReturnValue(WeatherModule.rain());
		}
	}

	@Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
	private void visualclient$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (WeatherModule.active() && (Object) this instanceof ClientLevel) {
			cir.setReturnValue(WeatherModule.thunder());
		}
	}
}
