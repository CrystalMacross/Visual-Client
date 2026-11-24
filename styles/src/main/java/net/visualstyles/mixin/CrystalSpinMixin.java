package net.visualstyles.mixin;

import net.minecraft.class_10014;
import net.minecraft.class_1511;
import net.minecraft.class_892;
import net.visualstyles.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EndCrystalRenderer.extractRenderState(EndCrystal, EndCrystalRenderState, float): the model spins and bobs
 * with state.ageInTicks, so scaling it changes the spin speed (0 = still).
 */
@Mixin(class_892.class)
public abstract class CrystalSpinMixin {
   @Inject(method = "method_62417(Lnet/minecraft/class_1511;Lnet/minecraft/class_10014;F)V", at = @At("TAIL"), require = 0)
   private void visualstyles$spin(class_1511 crystal, class_10014 state, float partialTick, CallbackInfo ci) {
      float speed = Config.get().crystalSpin;
      if (speed != 1.0F) {
         state.field_53328 = state.field_53328 * speed;
      }
   }
}
