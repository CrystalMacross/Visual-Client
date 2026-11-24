package net.visualclient.mixin;

import net.minecraft.client.Camera;
import net.visualclient.modules.misc.PinModule;
import net.visualclient.modules.visuals.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 26.x: the field of view moved from GameRenderer into Camera.getFov()
@Mixin({Camera.class})
public abstract class ZoomMixin {
   @Inject(
      method = {"getFov"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void visualclient$zoom(CallbackInfoReturnable<Float> cir) {
      float fov = cir.getReturnValueF();
      float zoomed = ZoomModule.modifyFov(fov);
      if (zoomed != fov) {
         cir.setReturnValue(zoomed);
      }

      PinModule.worldFov = zoomed;
   }
}
