package net.visualclient.core;

import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;

public final class ExtraSettings {
   public static BoolSetting noPopAnimation;
   private static final String MENU_SCREEN = "net.visualclient.gui.NewGuiScreen";
   public static ModeSetting uiMode;

   private ExtraSettings() {
   }

   public static void totemTweaks(ClientModule var0) {
      noPopAnimation = var0.add(new BoolSetting("No Pop Animation", false));
      noPopAnimation.describe("Completely removes the totem pop animation (the totem that flies over your screen).");
   }

   public static boolean noPopActive() {
      return noPopAnimation != null && noPopAnimation.on();
   }

   public static float popScale(NumberSetting var0) {
      return noPopActive() ? 1.0E-4F : var0.asFloat();
   }

   public static void guiStyle(ClientModule var0) {
      uiMode = var0.add(new ModeSetting("UI", "New", "Old", "New", "Glass", "Neon", "Spotlight"));
      uiMode.describe("Old = classic menu, New = reworked menu, Glass / Neon / Spotlight = the modern designs.");
      uiMode.onChange(var0x -> reopenMenu());
   }

   /** Name of the menu design: Old, New, Glass, Neon or Spotlight. */
   public static String uiName() {
      return uiMode == null ? "New" : uiMode.get();
   }

   public static boolean useNewUi() {
      return uiMode == null || !uiMode.is("Old");
   }

   public static void setUi(String var0) {
      if (uiMode != null) {
         uiMode.set(var0);
      }
   }

   static void reopenMenu() {
      try {
         Class<?> var0 = Class.forName(MENU_SCREEN);
         Object var1 = var0.getMethod("vc$currentScreen").invoke(null);
         if (var1 == null) {
            return;
         }

         String var2 = var1.getClass().getName();
         String have;
         if (var2.equals("net.visualclient.gui.VisualGuiScreen")) {
            have = "Old";
         } else if (var2.equals(MENU_SCREEN)) {
            have = "New";
         } else if (var2.equals("net.visualclient.gui.ModernGuiScreen")) {
            have = (String)var1.getClass().getMethod("skin").invoke(var1);
         } else {
            return;
         }

         if (!have.equals(uiName())) {
            var0.getMethod("vc$openMenu").invoke(null);
         }
      } catch (Throwable var5) {
      }
   }
}
