package net.visualstyles.mixin;

import net.minecraft.class_4603;
import net.visualstyles.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * ScreenEffectRenderer.renderFire: both flame quads are placed with poseStack.translate(x, -0.3F, 0).
 * Lowering that y moves the burning overlay down so you can see more while on fire.
 */
@Mixin(class_4603.class)
public abstract class LowFireMixin {
   @ModifyArg(
      method = "method_23070(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;Lnet/minecraft/class_1058;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/class_4587;method_46416(FFF)V"),
      index = 1,
      require = 0
   )
   private static float visualstyles$lowFire(float y) {
      float low = Config.get().lowFire;
      return low <= 0.0F ? y : y - low * 0.45F;
   }
}
