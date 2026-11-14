package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.visualclient.hooks.OverlayAccess;
import net.visualclient.modules.combat.DamageTintModule;

/** Gives the Damage Tint module access to the 16x16 overlay texture (red hurt flash, white creeper flash). */
@Mixin(OverlayTexture.class)
public abstract class OverlayTextureMixin implements OverlayAccess {
	@Shadow
	@Final
	private DynamicTexture texture;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void visualclient$capture(CallbackInfo ci) {
		DamageTintModule.captureOverlay(this);
	}

	@Override
	public DynamicTexture visualclient$texture() {
		return this.texture;
	}
}
