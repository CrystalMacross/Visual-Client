package net.visualclient.mixin;

import net.minecraft.class_4184;
import net.minecraft.class_757;
import net.visualclient.modules.misc.PinModule;
import net.visualclient.modules.visuals.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({class_757.class})
public abstract class ZoomMixin {
   @Inject(
      method = {"method_3196"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void visualclient$zoom(class_4184 camera, float partialTick, boolean worldFov, CallbackInfoReturnable<Float> cir) {
      float fov = cir.getReturnValueF();
      float zoomed = ZoomModule.modifyFov(fov);
      if (zoomed != fov) {
         cir.setReturnValue(zoomed);
      }

      if (worldFov) {
         PinModule.worldFov = zoomed;
      }
   }
}
