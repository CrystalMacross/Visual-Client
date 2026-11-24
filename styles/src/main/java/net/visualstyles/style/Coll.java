package net.visualstyles.style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.visualstyles.Config;

/** A collection in the menu: which vanilla textures it changes and how its preview looks. Paths are for 1.21.11. */
public enum Coll {
   CRYSTAL("crystal", "Crystal", () -> class_1802.field_8301,
      List.of("textures/entity/end_crystal/end_crystal.png"),
      List.of("textures/entity/end_crystal/end_crystal.png")),
   ANCHOR("anchor", "Anchor", () -> class_1802.field_23141,
      List.of(
         "textures/block/respawn_anchor_bottom.png",
         "textures/block/respawn_anchor_side0.png",
         "textures/block/respawn_anchor_side1.png",
         "textures/block/respawn_anchor_side2.png",
         "textures/block/respawn_anchor_side3.png",
         "textures/block/respawn_anchor_side4.png",
         "textures/block/respawn_anchor_top.png",
         "textures/block/respawn_anchor_top_off.png"
      ),
      List.of(
         "textures/block/respawn_anchor_side0.png",
         "textures/block/respawn_anchor_side1.png",
         "textures/block/respawn_anchor_side2.png",
         "textures/block/respawn_anchor_side3.png",
         "textures/block/respawn_anchor_side4.png"
      )),
   GLOWSTONE("glowstone", "Glowstone", () -> class_1802.field_8801,
      List.of("textures/block/glowstone.png"),
      List.of("textures/block/glowstone.png")),
   OBSIDIAN("obsidian", "Obsidian", () -> class_1802.field_8281,
      List.of("textures/block/obsidian.png"),
      List.of("textures/block/obsidian.png")),
   ARMOR("armor", "Armor", () -> class_1802.field_22028,
      List.of(
         "textures/entity/equipment/humanoid/netherite.png",
         "textures/entity/equipment/humanoid/diamond.png",
         "textures/entity/equipment/humanoid_leggings/netherite.png",
         "textures/entity/equipment/humanoid_leggings/diamond.png",
         "textures/item/netherite_helmet.png",
         "textures/item/netherite_chestplate.png",
         "textures/item/netherite_leggings.png",
         "textures/item/netherite_boots.png",
         "textures/item/diamond_helmet.png",
         "textures/item/diamond_chestplate.png",
         "textures/item/diamond_leggings.png",
         "textures/item/diamond_boots.png"
      ),
      List.of("textures/entity/equipment/humanoid/netherite.png", "textures/entity/equipment/humanoid/diamond.png")),
   SWORD("sword", "Sword", () -> class_1802.field_22022,
      List.of("textures/item/netherite_sword.png", "textures/item/diamond_sword.png"),
      List.of("textures/item/netherite_sword.png", "textures/item/diamond_sword.png")),
   PICKAXE("pickaxe", "Pickaxe", () -> class_1802.field_22024,
      List.of("textures/item/netherite_pickaxe.png", "textures/item/diamond_pickaxe.png"),
      List.of("textures/item/netherite_pickaxe.png", "textures/item/diamond_pickaxe.png")),
   AXE("axe", "Axe", () -> class_1802.field_22025,
      List.of("textures/item/netherite_axe.png", "textures/item/diamond_axe.png"),
      List.of("textures/item/netherite_axe.png", "textures/item/diamond_axe.png")),
   TOTEM("totem", "Totem", () -> class_1802.field_8288,
      List.of("textures/item/totem_of_undying.png"),
      List.of("textures/item/totem_of_undying.png")),
   /** Mace, wind charge and ender pearl in one category: a pick changes all three (packs change what they have). */
   MACE_WIND_PEARL("mace_wind_pearl", "Mace + Wind + Pearl", () -> class_1802.field_49814,
      List.of("textures/item/mace.png", "textures/item/wind_charge.png", "textures/entity/projectiles/wind_charge.png", "textures/item/ender_pearl.png"),
      List.of("textures/item/mace.png", "textures/item/wind_charge.png", "textures/item/ender_pearl.png")),
   FIRE("fire", "Fire", () -> class_1802.field_8884,
      List.of("textures/block/fire_0.png", "textures/block/fire_1.png"),
      List.of("textures/block/fire_0.png", "textures/block/fire_1.png")),
   STONE("stone", "Stone", () -> class_1802.field_20391,
      List.of("textures/block/stone.png"),
      List.of("textures/block/stone.png")),
   DEEPSLATE("deepslate", "Deepslate", () -> class_1802.field_28866,
      List.of("textures/block/deepslate.png", "textures/block/deepslate_top.png"),
      List.of("textures/block/deepslate.png")),
   SNOW("snow", "Snow", () -> class_1802.field_8246,
      List.of("textures/block/snow.png"),
      List.of("textures/block/snow.png")),
   INVENTORY("inventory", "Inventory", () -> class_1802.field_8106,
      containers(),
      List.of("textures/gui/container/inventory.png")),
   GLINT("glint", "Enchant glint", () -> class_1802.field_8598,
      List.of("textures/misc/enchanted_glint_item.png", "textures/misc/enchanted_glint_armor.png"),
      List.of("textures/misc/enchanted_glint_item.png", "textures/misc/enchanted_glint_armor.png")),
   CROSSHAIR("crosshair", "Crosshair", () -> class_1802.field_8449,
      List.of("textures/gui/sprites/hud/crosshair.png"),
      List.of("textures/gui/sprites/hud/crosshair.png")),
   /** Whole bundled texture packs: every file of the pack (textures, models, sounds...). "pack.png" is only its picture. */
   PACKS("packs", "Whole packs", () -> class_1802.field_8106,
      List.of("pack.png"),
      List.of());

   public final String id;
   public final String title;
   private final Supplier<class_1792> icon;
   /** Every vanilla texture a style of this collection may replace. */
   public final List<String> targets;
   /** Textures a single custom PNG replaces (custom folders replace files by name). */
   public final List<String> customTargets;
   private class_1799 stack;

   Coll(String id, String title, Supplier<class_1792> icon, List<String> targets, List<String> customTargets) {
      this.id = id;
      this.title = title;
      this.icon = icon;
      this.targets = targets;
      this.customTargets = customTargets;
   }

   private static List<String> containers() {
      List<String> out = new ArrayList<>();
      for (String n : new String[]{
         "inventory", "generic_54", "shulker_box", "crafting_table", "furnace", "blast_furnace", "smoker", "dispenser",
         "hopper", "anvil", "grindstone", "smithing", "enchanting_table", "brewing_stand", "beacon", "stonecutter",
         "cartography_table", "loom", "villager", "horse", "crafter", "nautilus"
      }) {
         out.add("textures/gui/container/" + n + ".png");
      }
      return List.copyOf(out);
   }

   public class_1799 stack() {
      if (this.stack == null) {
         this.stack = new class_1799(this.icon.get());
      }
      return this.stack;
   }

   /** True while an extra (crystal core colour, anchor glow colour) changes this collection's textures. */
   public boolean hasExtras() {
      Config c = Config.get();
      return this == CRYSTAL && c.crystalCoreOn || this == ANCHOR && c.anchorGlowOn;
   }

   /** Applies the collection's extras on top of a style. */
   public Pic extras(String path, Pic p) {
      Config c = Config.get();
      if (this == CRYSTAL && c.crystalCoreOn) {
         return Ops.crystalCore(p, Col.parseHex(c.crystalCore, 0xFF33E1FF));
      }
      if (this == ANCHOR && c.anchorGlowOn) {
         return Ops.recolorGlow(p, Col.parseHex(c.anchorGlow, 0xFF8B5CF6));
      }
      return p;
   }

   /** End crystal as a small 3D (isometric) picture: core cube inside, glass cube around it. */
   static Pic crystalIso(Pic t) {
      int n = 48;
      Pic out = new Pic(n, n);
      float s = t.w / 64.0F;
      float cx = n / 2.0F;
      float cy = n / 2.0F;
      // core (texOffs 32,0) smaller, then glass (texOffs 0,0) over it
      isoCube(out, t, 32, 0, s, cx, cy, 1.55F);
      isoCube(out, t, 0, 0, s, cx, cy, 2.75F);
      return out;
   }

   /** Draws the top, front and right faces of an 8x8x8 box-UV cube whose texture starts at (ox, oy). */
   private static void isoCube(Pic out, Pic t, int ox, int oy, float s, float cx, float cy, float k) {
      float top = cy - 8.0F * k;
      // top face (u: ox+8, v: oy), front (ox+8, oy+8), right (ox+16, oy+8)
      isoFace(out, t, ox + 8, oy, s, cx, top, k, k / 2.0F, -k, k / 2.0F, 1.0F);
      isoFace(out, t, ox + 8, oy + 8, s, cx - 8.0F * k, top + 4.0F * k, k, k / 2.0F, 0.0F, k, 0.86F);
      isoFace(out, t, ox + 16, oy + 8, s, cx, top + 8.0F * k, k, -k / 2.0F, 0.0F, k, 0.7F);
   }

   private static void isoFace(Pic out, Pic t, int fx, int fy, float s, float x0, float y0,
                               float ux, float uy, float vx, float vy, float shade) {
      int size = Math.max(1, Math.round(8 * s));
      int steps = 4;
      for (int v = 0; v < size; v++) {
         for (int u = 0; u < size; u++) {
            int tx = Math.round(fx * s) + u;
            int ty = Math.round(fy * s) + v;
            if (tx >= t.w || ty >= t.h) {
               continue;
            }
            int c = t.get(tx, ty);
            if (Col.a(c) == 0) {
               continue;
            }
            c = Col.argb(Col.a(c), Math.round(Col.r(c) * shade), Math.round(Col.g(c) * shade), Math.round(Col.b(c) * shade));
            for (int sv = 0; sv < steps; sv++) {
               for (int su = 0; su < steps; su++) {
                  float uu = (u + (su + 0.5F) / steps) / s;
                  float vv = (v + (sv + 0.5F) / steps) / s;
                  int px = (int)Math.floor(x0 + uu * ux + vv * vx);
                  int py = (int)Math.floor(y0 + uu * uy + vv * vy);
                  if (px >= 0 && py >= 0 && px < out.w && py < out.h) {
                     out.set(px, py, Col.over(c, out.get(px, py)));
                  }
               }
            }
         }
      }
   }

   /** Two textures next to each other at the same size (first frame of animated strips). */
   static Pic pair(Pic a, Pic b) {
      if (a == null || b == null) {
         return a != null ? a : b;
      }
      a = a.crop(0, 0, a.w, a.frameHeight());
      b = b.crop(0, 0, b.w, b.frameHeight());
      int s = Math.max(16, Math.max(Math.max(a.w, a.h), Math.max(b.w, b.h)));
      int gap = Math.max(1, s / 8);
      Pic out = new Pic(s * 2 + gap, s);
      out.blend(a.padSquare(Math.max(a.w, a.h)).scaled(s, s), 0, 0);
      out.blend(b.padSquare(Math.max(b.w, b.h)).scaled(s, s), s + gap, 0);
      return out;
   }

   /** Several textures in a row at the same size (first frame of animated strips); missing ones leave a gap. */
   static Pic row(Pic... pics) {
      int s = 16;
      int n = 0;
      for (int i = 0; i < pics.length; i++) {
         if (pics[i] != null) {
            pics[i] = pics[i].crop(0, 0, pics[i].w, pics[i].frameHeight());
            s = Math.max(s, Math.max(pics[i].w, pics[i].h));
            n++;
         }
      }
      if (n == 0) {
         return null;
      }
      int gap = Math.max(1, s / 8);
      Pic out = new Pic(s * pics.length + gap * (pics.length - 1), s);
      for (int i = 0; i < pics.length; i++) {
         if (pics[i] != null) {
            out.blend(pics[i].padSquare(Math.max(pics[i].w, pics[i].h)).scaled(s, s), i * (s + gap), 0);
         }
      }
      return out;
   }

   /**
    * Worn armor as a flat front view (helmet, chestplate, arms, leggings, boots from the 64x32 equipment
    * layers) next to the chestplate item icon, so worn-only and icon-only packs both show what they change.
    */
   static Pic armorPreview(Pic layer1, Pic layer2, Pic icon) {
      int k = layer1 != null ? Math.max(1, layer1.w / 64) : layer2 != null ? Math.max(1, layer2.w / 64) : 1;
      Pic fig = new Pic(16 * k, 32 * k);
      if (layer2 != null) {
         int k2 = Math.max(1, layer2.w / 64);
         fig.blend(part(layer2, 20, 20, 8, 12, k2, k, false), 4 * k, 8 * k);
         fig.blend(part(layer2, 4, 20, 4, 12, k2, k, false), 4 * k, 20 * k);
         fig.blend(part(layer2, 4, 20, 4, 12, k2, k, true), 8 * k, 20 * k);
      }
      if (layer1 != null) {
         fig.blend(part(layer1, 8, 8, 8, 8, k, k, false), 4 * k, 0);
         fig.blend(part(layer1, 40, 8, 8, 8, k, k, false), 4 * k, 0);
         fig.blend(part(layer1, 20, 20, 8, 12, k, k, false), 4 * k, 8 * k);
         fig.blend(part(layer1, 44, 20, 4, 12, k, k, false), 0, 8 * k);
         fig.blend(part(layer1, 44, 20, 4, 12, k, k, true), 12 * k, 8 * k);
         fig.blend(part(layer1, 4, 20, 4, 12, k, k, false), 4 * k, 20 * k);
         fig.blend(part(layer1, 4, 20, 4, 12, k, k, true), 8 * k, 20 * k);
      }
      if (icon == null) {
         return fig;
      }
      int is = 16 * k;
      Pic out = new Pic(fig.w + 2 * k + is, fig.h);
      out.blend(fig, 0, 0);
      out.blend(icon.crop(0, 0, icon.w, icon.frameHeight()).padSquare(Math.max(icon.w, icon.frameHeight())).scaled(is, is), fig.w + 2 * k, (fig.h - is) / 2);
      return out;
   }

   /** Cuts a box-UV face (in 64x32 units) out of a texture with scale ks, rescaled to scale k, optionally mirrored. */
   private static Pic part(Pic t, int u, int v, int w, int h, int ks, int k, boolean mirror) {
      Pic c = t.crop(u * ks, v * ks, w * ks, h * ks);
      if (ks != k) {
         c = c.scaled(w * k, h * k);
      }
      if (mirror) {
         Pic m = new Pic(c.w, c.h);
         for (int y = 0; y < c.h; y++) {
            for (int x = 0; x < c.w; x++) {
               m.px[y * c.w + x] = c.px[y * c.w + (c.w - 1 - x)];
            }
         }
         c = m;
      }
      return c;
   }

   /** Small picture for the menu, built from the styled textures. */
   public Pic preview(Function<String, Pic> tex) {
      switch (this) {
         case CRYSTAL -> {
            Pic t = tex.apply(this.targets.get(0));
            if (t == null) {
               return null;
            }
            return crystalIso(t);
         }
         case ANCHOR -> {
            Pic t = tex.apply("textures/block/respawn_anchor_side4.png");
            // animated packs store frames as a vertical strip: show the first frame
            return t == null ? null : t.crop(0, 0, t.w, t.frameHeight());
         }
         case ARMOR -> {
            return armorPreview(
               tex.apply("textures/entity/equipment/humanoid/netherite.png"),
               tex.apply("textures/entity/equipment/humanoid_leggings/netherite.png"),
               tex.apply("textures/item/netherite_chestplate.png"));
         }
         case MACE_WIND_PEARL -> {
            return row(tex.apply("textures/item/mace.png"), tex.apply("textures/item/wind_charge.png"), tex.apply("textures/item/ender_pearl.png"));
         }
         case SWORD, PICKAXE, AXE, DEEPSLATE -> {
            // both textures side by side, so styles that share one texture still look different
            return pair(tex.apply(this.targets.get(0)), tex.apply(this.targets.get(1)));
         }
         case FIRE -> {
            Pic t = tex.apply(this.targets.get(0));
            return t == null ? null : t.crop(0, 0, t.w, t.frameHeight());
         }
         case INVENTORY -> {
            // 2x2 mosaic: inventory, chest, furnace, crafting table (each the border and first slots)
            Pic out = new Pic(82, 82);
            int[][] parts = {{0, 3, 79}, {1, 3, 13}, {4, 51, 13}, {3, 26, 13}};
            int n = 0;
            for (int[] p : parts) {
               Pic t = tex.apply(this.targets.get(p[0]));
               if (t != null) {
                  int s = Math.max(1, t.w / 256);
                  Pic c = t.crop(p[1] * s, p[2] * s, 41 * s, 41 * s);
                  out.blend(s == 1 ? c : c.scaled(41, 41), n % 2 * 41, n / 2 * 41);
               }
               n++;
            }
            return out;
         }
         case GLINT -> {
            Pic sword = Vanilla.pic("textures/item/netherite_sword.png");
            Pic glint = tex.apply(this.targets.get(0));
            if (sword == null || glint == null) {
               return glint;
            }
            Pic base = sword.scaled(32, 32);
            Pic g = glint.crop(0, 0, Math.min(glint.w, 64), Math.min(glint.h, 64)).scaled(32, 32);
            for (int i = 0; i < base.px.length; i++) {
               int c = base.px[i];
               if (Col.a(c) == 0) {
                  continue;
               }
               int add = g.px[i];
               float k = 0.85F;
               base.px[i] = Col.argb(Col.a(c), Col.r(c) + Math.round(Col.r(add) * k), Col.g(c) + Math.round(Col.g(add) * k), Col.b(c) + Math.round(Col.b(add) * k));
            }
            return base;
         }
         case CROSSHAIR -> {
            Pic t = tex.apply(this.targets.get(0));
            return t == null ? null : t.padSquare(16);
         }
         default -> {
            return tex.apply(this.targets.get(0));
         }
      }
   }
}
