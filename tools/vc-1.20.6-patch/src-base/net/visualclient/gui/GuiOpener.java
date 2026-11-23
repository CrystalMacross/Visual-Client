package net.visualclient.gui;

import net.minecraft.class_437;
import net.visualclient.core.ExtraSettings;

public final class GuiOpener {
   private GuiOpener() {
   }

   public static class_437 open() {
      String ui = ExtraSettings.uiName();
      if (ModernGuiScreen.isModern(ui)) {
         return new ModernGuiScreen(ui);
      }

      return (class_437)(ExtraSettings.useNewUi() ? new NewGuiScreen() : new VisualGuiScreen());
   }
}
