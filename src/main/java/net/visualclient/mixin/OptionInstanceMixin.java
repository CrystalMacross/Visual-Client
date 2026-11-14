package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.OptionInstance;
import net.visualclient.modules.visuals.FullbrightModule;

/** Fullbright: the game reads its brightness option every frame. We answer with a high value, without changing options.txt. */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin<T> {
	@SuppressWarnings("unchecked")
	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void visualclient$gamma(CallbackInfoReturnable<T> cir) {
		if (FullbrightModule.gammaActive() && FullbrightModule.isGammaOption(this)) {
			cir.setReturnValue((T) (Object) Double.valueOf(FullbrightModule.gammaValue()));
		}
	}
}
