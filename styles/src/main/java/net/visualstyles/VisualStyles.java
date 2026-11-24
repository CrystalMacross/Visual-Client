package net.visualstyles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3675;
import net.visualstyles.gui.StylesScreen;
import net.visualstyles.style.StylePack;
import org.lwjgl.glfw.GLFW;

/** Visual Styles: pick looks for crystals, anchors, armor, tools and more from one menu (key K). */
public final class VisualStyles implements ClientModInitializer {
   public static final String MOD_ID = "visualstyles";
   private static class_304 menuKey;
   private static boolean startupChecked;

   @Override
   public void onInitializeClient() {
      Config.load();
      // KeyMapping.Category.register(Identifier) + new KeyMapping(name, InputConstants.Type.KEYSYM, key, category)
      class_304.class_11900 category = class_304.class_11900.method_74698(class_2960.method_60655(MOD_ID, "main"));
      menuKey = KeyBindingHelper.registerKeyBinding(new class_304("key.visualstyles.menu", class_3675.class_307.field_1668, GLFW.GLFW_KEY_K, category));
      ClientTickEvents.END_CLIENT_TICK.register(VisualStyles::tick);
   }

   private static void tick(class_310 mc) {
      if (!startupChecked && mc.method_18506() == null) {
         // the first frame without the loading overlay: make sure the pack still matches the picks
         startupChecked = true;
         try {
            StylePack.checkOnStartup();
         } catch (Throwable ignored) {
         }
      }
      while (menuKey.method_1436()) {
         if (mc.field_1755 == null) {
            mc.method_1507(new StylesScreen());
         }
      }
   }
}
