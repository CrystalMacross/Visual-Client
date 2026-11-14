package net.visualclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.visualclient.pack.FilteredPackResources;
import net.visualclient.pack.PackMix;

/** Pack Mixer: wraps packs that have a category filter so they only offer the parts you picked. */
@Mixin(Pack.class)
public abstract class PackMixin {
	@Shadow
	public abstract String getId();

	@Inject(method = "open", at = @At("RETURN"), cancellable = true)
	private void visualclient$filter(CallbackInfoReturnable<PackResources> cir) {
		if (!PackMix.isActive()) {
			return;
		}
		String id = getId();
		PackResources original = cir.getReturnValue();
		if (original == null || original instanceof FilteredPackResources || !PackMix.isFiltered(id)) {
			return;
		}
		cir.setReturnValue(new FilteredPackResources(original, PackMix.maskOf(id)));
	}
}
