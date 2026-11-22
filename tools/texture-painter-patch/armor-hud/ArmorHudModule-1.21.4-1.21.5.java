package net.visualclient.modules.combat;

import net.minecraft.class_1921;
import net.minecraft.class_1304;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_746;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.hud.HudModule;

/**
 * Armor HUD (1.20.4): the armor pieces sit in hotbar slots drawn with the game's own hotbar
 * texture, so resource packs restyle it too. Each piece shows the vanilla durability bar;
 * an empty slot shows the faint armor outline of the inventory.
 */
public final class ArmorHudModule extends HudModule {
   private static final class_2960 HOTBAR = class_2960.method_60656("hud/hotbar");
   private static final class_2960[] EMPTY_SLOT = new class_2960[]{
      class_2960.method_60656("container/slot/helmet"),
      class_2960.method_60656("container/slot/chestplate"),
      class_2960.method_60656("container/slot/leggings"),
      class_2960.method_60656("container/slot/boots"),
      null,
      class_2960.method_60656("container/slot/shield")
   };
   private final BoolSetting hands = this.add(new BoolSetting("Show held items", false));
   private final class_1799[] stacks = new class_1799[6];
   private class_1799[] preview;

   public ArmorHudModule() {
      super("Armor HUD", "Shows your armor in hotbar slots with the durability bar of each piece.", Category.COMBAT);

      for (int i = 0; i < this.stacks.length; i++) {
         this.stacks[i] = class_1799.field_8037;
      }

      this.defaultEnabled(true);
      this.commonSettings();
   }

   @Override
   protected void tick() {
      class_746 p = class_310.method_1551().field_1724;
      if (p != null) {
         this.stacks[0] = p.method_6118(class_1304.field_6169);
         this.stacks[1] = p.method_6118(class_1304.field_6174);
         this.stacks[2] = p.method_6118(class_1304.field_6172);
         this.stacks[3] = p.method_6118(class_1304.field_6166);
         this.stacks[4] = p.method_6047();
         this.stacks[5] = p.method_6079();
      }
   }

   @Override
   protected int defaultX(int sw, int sh) {
      return 6;
   }

   @Override
   protected int defaultY(int sw, int sh) {
      return sh / 2 - 11;
   }

   /** Shown in the HUD editor while you wear nothing: a worn helmet, an empty chest slot, new leggings, used boots. */
   private class_1799[] demoStacks() {
      if (this.preview == null) {
         this.preview = new class_1799[]{
            damaged(class_1802.field_22027, 380),
            class_1799.field_8037,
            damaged(class_1802.field_22029, 0),
            damaged(class_1802.field_22030, 120),
            class_1799.field_8037,
            class_1799.field_8037
         };
      }

      return this.preview;
   }

   private static class_1799 damaged(class_1792 item, int damage) {
      class_1799 s = new class_1799(item);
      s.method_7974(damage);
      return s;
   }

   @Override
   protected void renderContent(class_332 g, class_310 mc, boolean editing) {
      int slots = this.hands.on() ? 6 : 4;
      class_1799[] source = this.stacks;
      boolean any = false;

      for (int i = 0; i < slots; i++) {
         any |= !source[i].method_7960();
      }

      if (!any && editing) {
         source = this.demoStacks();
         any = true;
      }

      if (!any) {
         this.contentWidth = 0;
         this.contentHeight = 0;
         return;
      }

      // hotbar texture (182 x 22): 1 px border, then one 20 px cell per slot, then the 1 px right border
      int cells = 1 + slots * 20;
      this.contentWidth = cells + 1;
      this.contentHeight = 22;
      sprite(g, HOTBAR, 182, 22, 0, 0, 0, 0, cells, 22);
      sprite(g, HOTBAR, 182, 22, 181, 0, cells, 0, 1, 22);
      class_327 font = mc.field_1772;

      for (int i = 0; i < slots; i++) {
         int x = 3 + i * 20;
         class_1799 stack = source[i];
         if (stack.method_7960()) {
            if (EMPTY_SLOT[i] != null) {
               sprite(g, EMPTY_SLOT[i], x, 3, 16, 16);
            }
         } else {
            g.method_51427(stack, x, 3);
            g.method_51431(font, stack, x, 3);
         }
      }
   }

   /** A section of a GUI sprite (GuiGraphics.blitSprite with texture size and offset). */
   private static void sprite(class_332 g, class_2960 id, int texW, int texH, int u, int v, int x, int y, int w, int h) {
      g.method_52708(class_1921::method_62277, id, texW, texH, u, v, x, y, w, h);
   }

   /** A whole GUI sprite (GuiGraphics.blitSprite). */
   private static void sprite(class_332 g, class_2960 id, int x, int y, int w, int h) {
      g.method_52706(class_1921::method_62277, id, x, y, w, h);
   }
}
