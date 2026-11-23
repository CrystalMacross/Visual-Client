package net.visualclient.mixin;

import net.minecraft.class_2394;
import net.minecraft.class_702;
import net.minecraft.class_703;
import net.visualclient.hooks.ParticleGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({class_702.class})
public abstract class ParticleMixin {
   @Inject(
      method = {"method_3056"},
      at = {@At("HEAD")},
      require = 0
   )
   private void visualclient$particle(
      class_2394 options, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<class_703> cir
   ) {
      ParticleGate.begin(options);
   }

   @Inject(
      method = {"method_3056"},
      at = {@At("RETURN")},
      require = 0
   )
   private void visualclient$particleDone(
      class_2394 options, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<class_703> cir
   ) {
      ParticleGate.end();
   }

   @Inject(
      method = {"method_3058"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void visualclient$add(class_703 particle, CallbackInfo ci) {
      if (ParticleGate.consumeDrop()) {
         ci.cancel();
      }
   }
}
