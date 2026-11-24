package net.visualstyles.mixin;

import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_465;
import net.visualstyles.Config;
import net.visualstyles.style.StylePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * GuiGraphics.drawString(Font, Component, int, int, int, boolean): container screens draw their titles
 * ("Inventory", "Chest", ...) in dark grey 0xFF404040. With a dark inventory style that grey is hard to read,
 * so it becomes light while a container screen is open.
 */
@Mixin(class_332.class)
public abstract class ContainerLabelMixin {
   private static final int LABEL_GREY = 0xFF404040;

   @ModifyVariable(
      method = "method_51439(Lnet/minecraft/class_327;Lnet/minecraft/class_2561;IIIZ)V",
      at = @At("HEAD"),
      argsOnly = true,
      ordinal = 2,
      require = 0
   )
   private int visualstyles$labelColor(int color) {
      if (color != LABEL_GREY || !Config.get().darkLabels) {
         return color;
      }
      if (!(class_310.method_1551().field_1755 instanceof class_465)) {
         return color;
      }
      return StylePack.darkInventory() ? 0xFFE6E8EF : color;
   }
}
