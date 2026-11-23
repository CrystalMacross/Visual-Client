package net.visualclient.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.visualclient.compat.Compat;
import net.visualclient.compat.CompatScreen;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.ExtraSettings;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.core.setting.Setting;
import net.visualclient.core.setting.TextSetting;
import net.visualclient.font.Fonts;
import net.visualclient.hud.HudModule;
import net.visualclient.modules.Modules;
import net.visualclient.modules.extra.ItemSizeModule;
import net.visualclient.modules.misc.GuiStyleModule;
import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;

/**
 * Three modern menus for Visual Client (GUI Style -> UI). They all show the same modules and settings:
 *  Glass     - light frosted panel, category pills on top, module cards, settings page
 *  Neon      - dark neon panel, category sidebar, module list that opens its settings in place (accordion)
 *  Spotlight - command palette: search first, grouped results, keyboard navigation, settings view
 */
public final class ModernGuiScreen extends CompatScreen {
   public static final String GLASS = "Glass";
   public static final String NEON = "Neon";
   public static final String SPOTLIGHT = "Spotlight";
   private static final String[] TAB_NAMES = {"All", "PvP", "Visuals", "HUD", "Player", "Misc", "Extra", "Configs"};
   private static final Category[] TAB_CATS = {null, Category.COMBAT, Category.VISUALS, null, Category.PLAYER, Category.MISC, Category.EXTRA, Category.CONFIGS};
   private static int lastTab;
   private static ClientModule lastOpen;

   private final String skin;
   private final Hits hits = new Hits();
   private final TextInput search = new TextInput();
   private final TextInput edit = new TextInput();
   private class_332 g;
   private class_327 font;
   private int mx;
   private int my;
   private int tab = lastTab;
   private ClientModule open = lastOpen;
   private ClientModule hoveredModule;
   private String hint;
   private boolean searchFocus;
   private Setting<?> editing;
   private int editKind;
   private Hits.Action dragAction;
   private float scroll;
   private int maxScroll;
   private int scrollX;
   private int scrollY;
   private int scrollW;
   private int scrollH;
   private int selected;
   private List<ClientModule> shown = List.of();
   private final long openedAt = System.currentTimeMillis();
   // palette of the current design
   private int cDim;
   private int cPanel;
   private int cPanel2;
   private int cSide;
   private int cCard;
   private int cCardHover;
   private int cField;
   private int cLine;
   private int cLine2;
   private int cFg;
   private int cMuted;
   private int cFaint;
   private int cA;
   private int cB;
   private int cOn;
   private boolean light;

   public ModernGuiScreen(String skin) {
      super(class_2561.method_43470("Visual Client"));
      this.skin = skin;
      this.searchFocus = SPOTLIGHT.equals(skin);
      this.search.begin("", 32, c -> true);
   }

   public static boolean isModern(String ui) {
      return GLASS.equals(ui) || NEON.equals(ui) || SPOTLIGHT.equals(ui);
   }

   public String skin() {
      return this.skin;
   }

   private void palette() {
      if (GLASS.equals(this.skin)) {
         this.light = true;
         this.cDim = 0x50101018;
         this.cPanel = 0xF2F6F7FB;
         this.cPanel2 = 0xFFFFFFFF;
         this.cSide = 0xFFEEF0F6;
         this.cCard = 0xFFFFFFFF;
         this.cCardHover = 0xFFF3F0FF;
         this.cField = 0xFFFFFFFF;
         this.cLine = 0xFFDDE1EA;
         this.cLine2 = 0xFFC8CEDB;
         this.cFg = 0xFF141822;
         this.cMuted = 0xFF4C5366;
         this.cFaint = 0xFF8990A3;
         this.cA = 0xFF6D4AFF;
         this.cB = 0xFF0EA5E9;
         this.cOn = 0xFFFFFFFF;
      } else if (NEON.equals(this.skin)) {
         this.light = false;
         this.cDim = 0x99000000;
         this.cPanel = 0xF7050509;
         this.cPanel2 = 0xFF0F0D19;
         this.cSide = 0xFF08070E;
         this.cCard = 0xFF0C0B14;
         this.cCardHover = 0xFF161126;
         this.cField = 0xFF06050A;
         this.cLine = 0xFF1F1A30;
         this.cLine2 = 0xFF3A2357;
         this.cFg = 0xFFF7F3FF;
         this.cMuted = 0xFFBBADDD;
         this.cFaint = 0xFF6E6690;
         this.cA = 0xFFFF2BD6;
         this.cB = 0xFF00F0FF;
         this.cOn = 0xFF07060C;
      } else {
         this.light = false;
         this.cDim = 0x88000000;
         this.cPanel = 0xF5101218;
         this.cPanel2 = 0xFF181B24;
         this.cSide = 0xFF13161E;
         this.cCard = 0xFF151821;
         this.cCardHover = 0xFF1E2230;
         this.cField = 0xFF0C0E14;
         this.cLine = 0xFF242836;
         this.cLine2 = 0xFF343A4E;
         this.cFg = 0xFFF1F3F9;
         this.cMuted = 0xFFA5ABBF;
         this.cFaint = 0xFF6B7186;
         int acc = Theme.accent() | 0xFF000000;
         this.cA = acc;
         this.cB = ColorUtil.withAlpha(mix(acc, 0xFFFFFFFF, 0.35F), 255);
         this.cOn = 0xFFFFFFFF;
      }
   }

   // ================================================================== screen basics

   @Override
   public boolean method_25421() {
      return false;
   }

   @Override
   public void method_25420(class_332 var1, int var2, int var3, float var4) {
      if (class_310.method_1551().field_1687 == null) {
         super.method_25420(var1, var2, var3, var4);
      } else if (GuiStyleModule.blur()) {
         try {
            this.blurBackground(var1, var4);
         } catch (Throwable var6) {
         }
      }
   }

   @Override
   public void method_25419() {
      this.commitEdit(true);
      lastTab = this.tab;
      lastOpen = this.open;
      super.method_25419();
   }

   private void switchUi() {
      String next = GLASS.equals(this.skin) ? NEON : NEON.equals(this.skin) ? SPOTLIGHT : GLASS;
      ExtraSettings.setUi(next);
      Compat.setScreen(class_310.method_1551(), new ModernGuiScreen(next));
   }

   // ================================================================== data

   private List<ClientModule> modulesForTab(int t) {
      List<ClientModule> out = new ArrayList<>();
      for (ClientModule m : Modules.ALL) {
         if (t == 0 || t == 3 && m instanceof HudModule || TAB_CATS[t] != null && m.category() == TAB_CATS[t]) {
            out.add(m);
         }
      }
      return out;
   }

   private List<ClientModule> visibleModules() {
      String q = this.search.text().trim().toLowerCase(Locale.ROOT);
      if (q.isEmpty()) {
         return this.modulesForTab(this.tab);
      }
      List<ClientModule> a = new ArrayList<>();
      List<ClientModule> b = new ArrayList<>();
      List<ClientModule> c = new ArrayList<>();
      for (ClientModule m : Modules.ALL) {
         String n = m.name().toLowerCase(Locale.ROOT);
         String d = m.description() == null ? "" : m.description().toLowerCase(Locale.ROOT);
         if (n.startsWith(q)) {
            a.add(m);
         } else if (n.contains(q)) {
            b.add(m);
         } else if (d.contains(q)) {
            c.add(m);
         }
      }
      a.addAll(b);
      a.addAll(c);
      return a;
   }

   private static String catName(ClientModule m) {
      return m.category() == Category.COMBAT ? "PvP" : m.category().label();
   }

   private static List<Setting<?>> visibleSettings(ClientModule m) {
      List<Setting<?>> out = new ArrayList<>();
      for (Setting<?> s : m.settings()) {
         if (s.isVisible()) {
            out.add(s);
         }
      }
      return out;
   }

   private void openModule(ClientModule m) {
      this.commitEdit(true);
      this.open = m;
      this.scroll = 0.0F;
   }

   private void closeModule() {
      this.commitEdit(true);
      this.open = null;
      this.scroll = 0.0F;
   }

   private void toggle(ClientModule m) {
      if (m.canToggle()) {
         m.toggle();
      }
   }

   // ================================================================== render

   @Override
   public void method_25394(class_332 var1, int var2, int var3, float var4) {
      this.g = var1;
      this.font = this.field_22793;
      this.mx = var2;
      this.my = var3;
      this.hits.clear();
      this.hint = null;
      this.hoveredModule = null;
      this.palette();
      int sw = this.field_22789;
      int sh = this.field_22790;
      float t = Math.min(1.0F, (System.currentTimeMillis() - this.openedAt) / 180.0F);
      float e = 1.0F - (1.0F - t) * (1.0F - t);
      Fonts.setGuiRendering(true);
      try {
         Render2D.rect(var1, 0, 0, sw, sh, ColorUtil.withAlpha(this.cDim, Math.round(ColorUtil.alpha(this.cDim) * e)));
         Compat.push(var1);
         Compat.translate(var1, 0.0F, (1.0F - e) * 8.0F);
         if (NEON.equals(this.skin)) {
            this.renderNeon(sw, sh);
         } else if (SPOTLIGHT.equals(this.skin)) {
            this.renderSpotlight(sw, sh);
         } else {
            this.renderGlass(sw, sh);
         }
         Compat.pop(var1);
         ColorPickerBridge.render(var1, this.font, this.hits, var2, var3);
      } finally {
         Fonts.setGuiRendering(false);
      }
   }

   // ------------------------------------------------------------------ Glass

   private void renderGlass(int sw, int sh) {
      int w = Math.min(sw - 20, 600);
      int h = Math.min(sh - 20, 340);
      int x0 = (sw - w) / 2;
      int y0 = (sh - h) / 2;
      for (int i = 1; i <= 5; i++) {
         Render2D.round(this.g, x0 - i, y0 - i + 2, w + i * 2, h + i * 2, 8 + i, (0x14 - i * 3) << 24);
      }
      Render2D.round(this.g, x0, y0, w, h, 8, this.cPanel);
      Render2D.gradientV(this.g, x0 + 4, y0 + 1, w - 8, 26, 0x90FFFFFF, 0x00FFFFFF);
      this.topBar(x0, y0, w, true);
      int ty = y0 + 32;
      int tx = x0 + 12;
      for (int i = 0; i < TAB_NAMES.length; i++) {
         String name = TAB_NAMES[i];
         int tw = this.width(name) + 16;
         boolean on = this.tab == i && this.open == null && this.search.text().isEmpty();
         boolean hover = this.over(tx, ty, tw, 16);
         if (on) {
            Render2D.round(this.g, tx, ty, tw, 16, 8, this.cA);
            this.gradH(tx + 3, ty + 1, tw - 6, 14, this.cA, this.cB);
         } else {
            Render2D.round(this.g, tx, ty, tw, 16, 8, hover ? this.cCardHover : this.cPanel2);
            Render2D.outline(this.g, tx + 2, ty, tw - 4, 1, this.cLine);
         }
         this.textCenter(name, tx + tw / 2, ty + 4, on ? this.cOn : hover ? this.cFg : this.cMuted);
         int index = i;
         this.hits.add(tx, ty, tw, 16).left = (a, b) -> this.selectTab(index);
         tx += tw + 4;
      }
      int cy = ty + 22;
      int ch = y0 + h - 22 - cy;
      if (this.open != null) {
         this.settingsPage(this.open, x0 + 12, cy, w - 24, ch);
      } else {
         this.cardGrid(x0 + 12, cy, w - 24, ch);
      }
      this.footer(x0, y0 + h - 20, w, "Click a card for its settings  ·  switch toggles  ·  type to search");
   }

   private void cardGrid(int x, int y, int w, int h) {
      List<ClientModule> list = this.visibleModules();
      int gap = 8;
      int cols = Math.max(1, Math.min(4, (w + gap) / (150 + gap)));
      int cw = (w - (cols - 1) * gap) / cols;
      int ch = 46;
      int rows = (list.size() + cols - 1) / cols;
      int content = rows * (ch + gap) - gap;
      this.scrollArea(x, y, w, h, content);
      this.scissor(x - 3, y, x + w + 3, y + h);
      for (int i = 0; i < list.size(); i++) {
         ClientModule m = list.get(i);
         int cx = x + i % cols * (cw + gap);
         int cy = y + i / cols * (ch + gap) - Math.round(this.scroll);
         if (cy + ch < y || cy > y + h) {
            continue;
         }
         boolean hover = this.my >= y && this.my < y + h && this.over(cx, cy, cw, ch);
         if (hover) {
            this.hoveredModule = m;
            this.hint = m.description();
         }
         Render2D.round(this.g, cx, cy + 1, cw, ch, 6, 0x0D000000);
         Render2D.round(this.g, cx, cy, cw, ch, 6, hover ? this.cCardHover : this.cCard);
         if (m.isEnabled()) {
            Render2D.gradientV(this.g, cx, cy + 6, 2, ch - 12, this.cA, this.cB);
         }
         this.text(this.fit(m.name(), cw - 46), cx + 9, cy + 8, this.cFg);
         this.small(this.fitSmall(catName(m) + (m.hasSettings() ? "  ·  settings ›" : ""), cw - 46), cx + 9, cy + 19, m.isEnabled() ? this.cA : this.cFaint);
         this.small(this.fitSmall(m.description(), cw - 16), cx + 9, cy + 31, this.cMuted);
         int sx = cx + cw - 30;
         this.drawSwitch(sx, cy + 8, m.isEnabled(), m.canToggle());
         this.hits.add(sx - 2, cy + 4, 28, 16).clip(y, y + h).left = (a, b) -> this.toggle(m);
         this.hits.add(cx, cy, cw, ch).clip(y, y + h).left = (a, b) -> {
            if (m.hasSettings()) {
               this.openModule(m);
            } else {
               this.toggle(m);
            }
         };
      }
      this.unscissor();
      this.empty(list.isEmpty(), x, y, w);
      this.scrollbar(x + w + 2, y, h, content);
   }

   // ------------------------------------------------------------------ Neon

   private void renderNeon(int sw, int sh) {
      int w = Math.min(sw - 20, 620);
      int h = Math.min(sh - 20, 350);
      int x0 = (sw - w) / 2;
      int y0 = (sh - h) / 2;
      for (int i = 1; i <= 7; i++) {
         float k = 1.0F - (float)i / 8.0F;
         Render2D.outline(this.g, x0 - i, y0 - i, w + i * 2, h + i * 2, ColorUtil.withAlpha(i % 2 == 0 ? this.cA : this.cB, Math.round(60 * k * k)));
      }
      Render2D.rect(this.g, x0, y0, w, h, this.cPanel);
      this.accentOutline(x0, y0, w, h);
      int sideW = 118;
      Render2D.rect(this.g, x0 + 1, y0 + 1, sideW, h - 2, this.cSide);
      Render2D.gradientV(this.g, x0 + sideW + 1, y0 + 1, 1, h - 2, this.cA, this.cB);
      // logo
      this.text("VISUAL", x0 + 12, y0 + 11, this.cFg);
      this.text("CLIENT", x0 + 12 + this.width("VISUAL "), y0 + 11, this.cB);
      this.gradH(x0 + 12, y0 + 23, 40, 1, this.cA, this.cB);
      int ty = y0 + 34;
      for (int i = 0; i < TAB_NAMES.length; i++) {
         boolean on = this.tab == i && this.search.text().isEmpty();
         boolean hover = this.over(x0 + 6, ty, sideW - 12, 18);
         if (on) {
            Render2D.rect(this.g, x0 + 6, ty, sideW - 12, 18, ColorUtil.withAlpha(this.cA, 40));
            Render2D.rect(this.g, x0 + 6, ty, 2, 18, this.cA);
            for (int k = 1; k <= 3; k++) {
               Render2D.outline(this.g, x0 + 6 - k, ty - k, sideW - 12 + k * 2, 18 + k * 2, ColorUtil.withAlpha(this.cA, 30 - k * 8));
            }
         } else if (hover) {
            Render2D.rect(this.g, x0 + 6, ty, sideW - 12, 18, this.cCardHover);
         }
         this.text(TAB_NAMES[i].toUpperCase(Locale.ROOT), x0 + 14, ty + 5, on ? this.cFg : hover ? this.cB : this.cMuted);
         String count = String.valueOf(this.modulesForTab(i).size());
         this.text(count, x0 + sideW - 12 - this.width(count), ty + 5, on ? this.cB : this.cFaint);
         int index = i;
         this.hits.add(x0 + 6, ty, sideW - 12, 18).left = (a, b) -> this.selectTab(index);
         ty += 20;
      }
      // side buttons
      int by = y0 + h - 44;
      this.sideButton(x0 + 8, by, sideW - 16, "HUD EDITOR", () -> {
         if (ThemeModule.openHudEditor != null) {
            ThemeModule.openHudEditor.run();
         }
      });
      this.sideButton(x0 + 8, by + 20, sideW - 16, "UI: " + this.skin.toUpperCase(Locale.ROOT), this::switchUi);
      // search + list
      int lx = x0 + sideW + 12;
      int lw = x0 + w - 12 - lx;
      this.searchField(lx, y0 + 10, lw, 16, "SEARCH MODULES");
      int listY = y0 + 34;
      int listH = y0 + h - 24 - listY;
      this.accordion(lx, listY, lw, listH);
      this.footer(x0 + sideW + 1, y0 + h - 20, w - sideW - 1, "Click a module to open its settings  ·  switch toggles");
      for (int yy = y0 + 1; yy < y0 + h - 1; yy += 3) {
         Render2D.rect(this.g, x0 + 1, yy, w - 2, 1, 0x0BFFFFFF);
      }
   }

   private void sideButton(int x, int y, int w, String label, Runnable action) {
      boolean hover = this.over(x, y, w, 16);
      Render2D.rect(this.g, x, y, w, 16, hover ? ColorUtil.withAlpha(this.cB, 40) : this.cField);
      Render2D.outline(this.g, x, y, w, 16, hover ? this.cB : this.cLine2);
      this.textCenter(label, x + w / 2, y + 4, hover ? this.cFg : this.cMuted);
      this.hits.add(x, y, w, 16).left = (a, b) -> action.run();
   }

   /** Neon list: module rows; the open module shows its settings right below its row. */
   private void accordion(int x, int y, int w, int h) {
      List<ClientModule> list = this.visibleModules();
      int rowH = 28;
      int content = 0;
      for (ClientModule m : list) {
         content += rowH + 3;
         if (m == this.open) {
            content += this.settingsHeight(m, w - 16) + 6;
         }
      }
      this.scrollArea(x, y, w, h, content);
      this.scissor(x - 2, y, x + w + 2, y + h);
      int cy = y - Math.round(this.scroll);
      for (ClientModule m : list) {
         boolean expanded = m == this.open;
         if (cy + rowH >= y && cy <= y + h) {
            boolean hover = this.my >= y && this.my < y + h && this.over(x, cy, w, rowH);
            if (hover) {
               this.hoveredModule = m;
               this.hint = m.description();
            }
            Render2D.rect(this.g, x, cy, w, rowH, hover || expanded ? this.cCardHover : this.cCard);
            if (m.isEnabled()) {
               Render2D.rect(this.g, x, cy, 2, rowH, this.cB);
               for (int k = 1; k <= 3; k++) {
                  Render2D.rect(this.g, x + 2, cy, k, rowH, ColorUtil.withAlpha(this.cB, 24 - k * 6));
               }
            }
            if (expanded || hover) {
               Render2D.outline(this.g, x, cy, w, rowH, expanded ? this.cA : this.cLine2);
            }
            this.text(this.fit(m.name(), w / 3), x + 10, cy + 5, m.isEnabled() ? this.cFg : this.cMuted);
            this.small(this.fitSmall(catName(m).toUpperCase(Locale.ROOT) + "  ·  " + m.description(), w - 90), x + 10, cy + 17, this.cFaint);
            int sx = x + w - 34;
            this.drawSwitch(sx, cy + 8, m.isEnabled(), m.canToggle());
            this.hits.add(sx - 2, cy + 4, 30, 20).clip(y, y + h).left = (a, b) -> this.toggle(m);
            if (m.hasSettings()) {
               this.text(expanded ? "▾" : "▸", sx - 14, cy + 9, expanded ? this.cA : this.cFaint);
            }
            this.hits.add(x, cy, w, rowH).clip(y, y + h).left = (a, b) -> {
               if (m.hasSettings()) {
                  if (this.open == m) {
                     this.closeModule();
                  } else {
                     this.open = m;
                  }
               } else {
                  this.toggle(m);
               }
            };
         }
         cy += rowH + 3;
         if (expanded) {
            int sh = this.settingsHeight(m, w - 16);
            Render2D.rect(this.g, x + 4, cy - 2, w - 8, sh + 4, this.cField);
            Render2D.rect(this.g, x + 4, cy - 2, 1, sh + 4, this.cA);
            this.settingsList(m, x + 10, cy, w - 16, y, y + h);
            cy += sh + 6;
         }
      }
      this.unscissor();
      this.empty(list.isEmpty(), x, y, w);
      this.scrollbar(x + w + 3, y, h, content);
   }

   // ------------------------------------------------------------------ Spotlight

   private void renderSpotlight(int sw, int sh) {
      int w = Math.min(sw - 40, 430);
      int h = Math.min(sh - 40, 330);
      int x0 = (sw - w) / 2;
      int y0 = Math.max(16, Math.min((sh - h) / 2, sh / 8));
      for (int i = 1; i <= 6; i++) {
         Render2D.round(this.g, x0 - i, y0 - i + 3, w + i * 2, h + i * 2, 10 + i, (0x22 - i * 5) << 24);
      }
      Render2D.round(this.g, x0, y0, w, h, 10, this.cPanel);
      this.gradH(x0 + 10, y0, w - 20, 1, this.cA, this.cB);
      if (this.open != null) {
         ClientModule m = this.open;
         boolean bh = this.over(x0 + 8, y0 + 8, 18, 18);
         Render2D.round(this.g, x0 + 8, y0 + 8, 18, 18, 6, bh ? this.cCardHover : this.cPanel2);
         this.textCenter("‹", x0 + 17, y0 + 13, bh ? this.cFg : this.cMuted);
         this.hits.add(x0 + 8, y0 + 8, 18, 18).left = (a, b) -> this.closeModule();
         this.text(this.fit(m.name(), w - 110), x0 + 34, y0 + 10, this.cFg);
         this.small(this.fitSmall(catName(m) + "  ·  " + m.description(), w - 110), x0 + 34, y0 + 21, this.cFaint);
         this.drawSwitch(x0 + w - 36, y0 + 12, m.isEnabled(), m.canToggle());
         this.hits.add(x0 + w - 38, y0 + 8, 30, 18).left = (a, b) -> this.toggle(m);
         Render2D.rect(this.g, x0 + 8, y0 + 33, w - 16, 1, this.cLine);
         this.settingsPage(m, x0 + 12, y0 + 40, w - 24, h - 40 - 22, false);
         this.footer(x0, y0 + h - 20, w, "Esc / ← back  ·  click a value to change it");
         return;
      }
      // big search
      int sy = y0 + 8;
      Render2D.round(this.g, x0 + 8, sy, w - 16, 24, 7, this.cField);
      if (this.searchFocus) {
         this.accentOutline(x0 + 8, sy, w - 16, 24);
      }
      this.text("⌕", x0 + 16, sy + 8, this.cA);
      String q = this.search.text();
      boolean caret = this.searchFocus && System.currentTimeMillis() / 500L % 2L == 0L;
      if (q.isEmpty() && !caret) {
         this.text("Type to search modules…", x0 + 30, sy + 8, this.cFaint);
      } else {
         this.text(this.fit(q + (caret ? "_" : ""), w - 120), x0 + 30, sy + 8, this.cFg);
      }
      String ui = "UI: " + this.skin;
      int uw = this.width(ui) + 12;
      boolean uh = this.over(x0 + w - 14 - uw, sy + 4, uw, 16);
      Render2D.round(this.g, x0 + w - 14 - uw, sy + 4, uw, 16, 5, uh ? this.cCardHover : this.cPanel2);
      this.textCenter(ui, x0 + w - 14 - uw / 2, sy + 8, uh ? this.cFg : this.cMuted);
      this.hits.add(x0 + w - 14 - uw, sy + 4, uw, 16).left = (a, b) -> this.switchUi();
      this.hits.add(x0 + 8, sy, w - 30 - uw, 24).left = (a, b) -> this.searchFocus = true;
      // category chips
      int cx = x0 + 10;
      int cy = sy + 30;
      for (int i = 0; i < TAB_NAMES.length; i++) {
         int tw = this.smallWidth(TAB_NAMES[i]) + 10;
         boolean on = this.tab == i;
         boolean hover = this.over(cx, cy, tw, 12);
         Render2D.round(this.g, cx, cy, tw, 12, 6, on ? ColorUtil.withAlpha(this.cA, 70) : hover ? this.cCardHover : this.cPanel2);
         this.small(TAB_NAMES[i], cx + 5, cy + 3, on ? this.cFg : this.cMuted);
         int index = i;
         this.hits.add(cx, cy, tw, 12).left = (a, b) -> this.selectTab(index);
         cx += tw + 3;
      }
      // grouped results
      int listY = cy + 18;
      int listH = y0 + h - 22 - listY;
      List<ClientModule> list = this.visibleModules();
      this.shown = list;
      this.selected = Math.max(0, Math.min(this.selected, list.size() - 1));
      int rowH = 22;
      int headerH = 13;
      int content = 0;
      String lastCat = null;
      for (ClientModule m : list) {
         if (!catName(m).equals(lastCat)) {
            content += headerH;
            lastCat = catName(m);
         }
         content += rowH;
      }
      this.scrollArea(x0 + 8, listY, w - 16, listH, content);
      // keep the keyboard selection in view
      int selY = 0;
      lastCat = null;
      for (int i = 0; i < list.size(); i++) {
         if (!catName(list.get(i)).equals(lastCat)) {
            selY += headerH;
            lastCat = catName(list.get(i));
         }
         if (i == this.selected) {
            break;
         }
         selY += rowH;
      }
      if (selY - this.scroll < 0) {
         this.scroll = Math.max(0, selY - headerH);
      } else if (selY + rowH - this.scroll > listH) {
         this.scroll = Math.min(this.maxScroll, selY + rowH - listH);
      }
      this.scissor(x0 + 6, listY, x0 + w - 6, listY + listH);
      int ry = listY - Math.round(this.scroll);
      lastCat = null;
      for (int i = 0; i < list.size(); i++) {
         ClientModule m = list.get(i);
         if (!catName(m).equals(lastCat)) {
            lastCat = catName(m);
            this.small(lastCat.toUpperCase(Locale.ROOT), x0 + 14, ry + 4, this.cFaint);
            ry += headerH;
         }
         if (ry + rowH >= listY && ry <= listY + listH) {
            boolean sel = i == this.selected;
            boolean hover = this.my >= listY && this.my < listY + listH && this.over(x0 + 8, ry, w - 16, rowH);
            if (hover) {
               this.hoveredModule = m;
               this.hint = m.description();
            }
            if (sel || hover) {
               Render2D.round(this.g, x0 + 8, ry, w - 16, rowH, 6, sel ? ColorUtil.withAlpha(this.cA, 46) : this.cCardHover);
               if (sel) {
                  Render2D.round(this.g, x0 + 8, ry + 4, 2, rowH - 8, 1, this.cA);
               }
            }
            Render2D.round(this.g, x0 + 16, ry + 7, 8, 8, 4, m.isEnabled() ? this.cA : this.cLine2);
            this.text(this.fit(m.name(), w / 2 - 20), x0 + 30, ry + 7, this.cFg);
            this.small(this.fitSmall(m.description(), w / 2 - 60), x0 + w / 2 + 10, ry + 8, this.cFaint);
            this.drawSwitch(x0 + w - 40, ry + 6, m.isEnabled(), m.canToggle());
            this.hits.add(x0 + w - 42, ry + 2, 30, 18).clip(listY, listY + listH).left = (a, b) -> this.toggle(m);
            int index = i;
            this.hits.add(x0 + 8, ry, w - 16, rowH).clip(listY, listY + listH).left = (a, b) -> {
               this.selected = index;
               if (m.hasSettings()) {
                  this.openModule(m);
               } else {
                  this.toggle(m);
               }
            };
         }
         ry += rowH;
      }
      this.unscissor();
      this.empty(list.isEmpty(), x0 + 8, listY, w - 16);
      this.scrollbar(x0 + w - 6, listY, listH, content);
      this.footer(x0, y0 + h - 20, w, "↑↓ navigate  ·  Enter toggle  ·  → settings  ·  Esc close");
   }

   // ------------------------------------------------------------------ shared pieces

   private void topBar(int x0, int y0, int w, boolean showUi) {
      Render2D.round(this.g, x0 + 12, y0 + 9, 9, 9, 4, this.cA);
      Render2D.round(this.g, x0 + 14, y0 + 11, 5, 5, 2, this.cB);
      this.text("Visual Client", x0 + 26, y0 + 10, this.cFg);
      int closeX = x0 + w - 22;
      boolean ch = this.over(closeX, y0 + 6, 15, 15);
      Render2D.round(this.g, closeX, y0 + 6, 15, 15, 7, ch ? 0xFFFFE4E8 : 0x00000000);
      this.textCenter("×", closeX + 8, y0 + 9, ch ? 0xFFE11D48 : this.cMuted);
      this.hits.add(closeX, y0 + 6, 15, 15).left = (a, b) -> this.method_25419();
      int bx = closeX - 8;
      if (showUi) {
         String ui = "UI: " + this.skin;
         int uw = this.width(ui) + 14;
         bx -= uw;
         boolean uh = this.over(bx, y0 + 6, uw, 15);
         Render2D.round(this.g, bx, y0 + 6, uw, 15, 7, uh ? this.cCardHover : this.cPanel2);
         this.textCenter(ui, bx + uw / 2, y0 + 10, uh ? this.cFg : this.cMuted);
         this.hits.add(bx, y0 + 6, uw, 15).left = (a, b) -> this.switchUi();
         bx -= 4;
         String hud = "HUD editor";
         int hw = this.width(hud) + 14;
         bx -= hw;
         boolean hh = this.over(bx, y0 + 6, hw, 15);
         Render2D.round(this.g, bx, y0 + 6, hw, 15, 7, hh ? this.cCardHover : this.cPanel2);
         this.textCenter(hud, bx + hw / 2, y0 + 10, hh ? this.cFg : this.cMuted);
         this.hits.add(bx, y0 + 6, hw, 15).left = (a, b) -> {
            if (ThemeModule.openHudEditor != null) {
               ThemeModule.openHudEditor.run();
            }
         };
         bx -= 8;
      }
      int sx = x0 + 26 + this.width("Visual Client") + 14;
      int sw = Math.min(220, bx - sx);
      if (sw > 60) {
         this.searchField(sx, y0 + 6, sw, 15, "Search modules");
      }
   }

   private void searchField(int x, int y, int w, int h, String placeholder) {
      boolean hover = this.over(x, y, w, h);
      Render2D.round(this.g, x, y, w, h, NEON.equals(this.skin) ? 0 : h / 2, this.cField);
      if (this.searchFocus) {
         this.accentOutline(x, y, w, h);
      } else if (!this.light || hover) {
         Render2D.outline(this.g, x + 2, y, w - 4, 1, hover ? this.cLine2 : this.cLine);
      }
      String q = this.search.text();
      boolean caret = this.searchFocus && System.currentTimeMillis() / 500L % 2L == 0L;
      this.text("⌕", x + 6, y + (h - 8) / 2, this.searchFocus ? this.cA : this.cFaint);
      if (q.isEmpty() && !this.searchFocus) {
         this.text(this.fit(placeholder, w - 26), x + 18, y + (h - 8) / 2, this.cFaint);
      } else {
         this.text(this.fit(q + (caret ? "_" : ""), w - 26), x + 18, y + (h - 8) / 2, this.cFg);
      }
      this.hits.add(x, y, w, h).left = (a, b) -> {
         this.commitEdit(true);
         this.searchFocus = true;
      };
   }

   private void footer(int x0, int y, int w, String help) {
      Render2D.rect(this.g, x0 + 8, y, w - 16, 1, this.cLine);
      String text = this.hint != null && !this.hint.isEmpty() ? this.hint : help;
      this.small(this.fitSmall(text, w - 80), x0 + 12, y + 7, this.hint != null ? this.cMuted : this.cFaint);
      String count = Modules.ALL.size() + " modules";
      this.small(count, x0 + w - 12 - this.smallWidth(count), y + 7, this.cFaint);
   }

   private void selectTab(int index) {
      this.commitEdit(true);
      this.tab = index;
      this.open = null;
      this.scroll = 0.0F;
      this.selected = 0;
      this.search.clear();
   }

   private void empty(boolean empty, int x, int y, int w) {
      if (empty) {
         this.textCenter("No module matches \"" + this.search.text() + "\"", x + w / 2, y + 20, this.cFaint);
      }
   }

   private void scrollArea(int x, int y, int w, int h, int content) {
      this.maxScroll = Math.max(0, content - h);
      this.scroll = Math.max(0.0F, Math.min(this.scroll, this.maxScroll));
      this.scrollX = x;
      this.scrollY = y;
      this.scrollW = w;
      this.scrollH = h;
   }

   private void scrollbar(int x, int y, int h, int content) {
      if (this.maxScroll > 0) {
         int bar = Math.max(14, h * h / Math.max(1, content));
         int by = y + Math.round((h - bar) * (this.scroll / this.maxScroll));
         Render2D.rect(this.g, x, y, 2, h, this.cLine);
         Render2D.gradientV(this.g, x, by, 2, bar, this.cA, this.cB);
      }
   }

   private void drawSwitch(int x, int y, boolean on, boolean canToggle) {
      int w = 24;
      int h = 12;
      if (!canToggle) {
         this.small("always on", x + w - this.smallWidth("always on"), y + 3, this.cFaint);
         return;
      }
      if (on) {
         Render2D.round(this.g, x, y, w, h, 6, this.cA);
         this.gradH(x + 3, y + 1, w - 6, h - 2, this.cA, this.cB);
      } else {
         Render2D.round(this.g, x, y, w, h, 6, this.light ? 0xFFD9DDE7 : this.cLine2);
      }
      Render2D.round(this.g, on ? x + w - h + 1 : x + 1, y + 1, h - 2, h - 2, 5, 0xFFFFFFFF);
   }

   private void gradH(int x, int y, int w, int h, int left, int right) {
      Render2D.gradientH(this.g, x, y, w, h, left, right, Math.max(1, Math.min(48, w)));
   }

   private void accentOutline(int x, int y, int w, int h) {
      this.gradH(x, y, w, 1, this.cA, this.cB);
      this.gradH(x, y + h - 1, w, 1, this.cA, this.cB);
      Render2D.gradientV(this.g, x, y + 1, 1, h - 2, this.cA, this.cB);
      Render2D.gradientV(this.g, x + w - 1, y + 1, 1, h - 2, this.cA, this.cB);
   }

   // ------------------------------------------------------------------ settings

   private void settingsPage(ClientModule m, int x, int y, int w, int h) {
      this.settingsPage(m, x, y, w, h, true);
   }

   private void settingsPage(ClientModule m, int x, int y, int w, int h, boolean header) {
      int top = y;
      if (header) {
         boolean bh = this.over(x, y, 50, 16);
         Render2D.round(this.g, x, y, 50, 16, 8, bh ? this.cCardHover : this.cPanel2);
         this.textCenter("‹ Back", x + 25, y + 4, bh ? this.cFg : this.cMuted);
         this.hits.add(x, y, 50, 16).left = (a, b) -> this.closeModule();
         this.text(this.fit(m.name(), w - 120), x + 58, y + 4, this.cFg);
         this.drawSwitch(x + w - 26, y + 2, m.isEnabled(), m.canToggle());
         this.hits.add(x + w - 28, y, 30, 16).left = (a, b) -> this.toggle(m);
         this.small(this.fitSmall(m.description(), w - 4), x + 2, y + 21, this.cFaint);
         top = y + 32;
      }
      int hh = y + h - top;
      int content = this.settingsHeight(m, w - 6);
      this.scrollArea(x, top, w, hh, content);
      this.scissor(x - 2, top, x + w + 2, top + hh);
      this.settingsList(m, x, top - Math.round(this.scroll), w - 6, top, top + hh);
      this.unscissor();
      this.scrollbar(x + w - 2, top, hh, content);
      if (visibleSettings(m).isEmpty()) {
         this.textCenter("This module has no settings.", x + w / 2, top + 14, this.cFaint);
      }
   }

   private int rowHeight(Setting<?> s) {
      return s instanceof NumberSetting || s instanceof TextSetting ? 32 : 22;
   }

   private int settingsHeight(ClientModule m, int w) {
      int h = 0;
      for (Setting<?> s : visibleSettings(m)) {
         h += this.rowHeight(s) + 4;
      }
      return Math.max(h, 18);
   }

   private void settingsList(ClientModule m, int x, int y, int w, int clipTop, int clipBottom) {
      int ry = y;
      for (Setting<?> s : visibleSettings(m)) {
         int rh = this.rowHeight(s);
         if (ry + rh >= clipTop && ry <= clipBottom) {
            this.settingRow(s, x, ry, w, rh, clipTop, clipBottom);
         }
         ry += rh + 4;
      }
   }

   private void settingRow(Setting<?> s, int x, int y, int w, int rh, int ct, int cb) {
      boolean hover = this.my >= ct && this.my < cb && this.over(x, y, w, rh);
      Render2D.round(this.g, x, y, w, rh, this.light ? 6 : NEON.equals(this.skin) ? 0 : 5, hover ? this.cCardHover : this.cCard);
      if (hover && s.description() != null && !s.description().isEmpty()) {
         this.hint = s.name() + ": " + s.description();
      }
      int ctrlW = Math.min(170, w / 2);
      int cx = x + w - 8 - ctrlW;
      if (s instanceof ActionSetting act) {
         boolean bh = hover;
         Render2D.round(this.g, x, y, w, rh, 5, bh ? ColorUtil.withAlpha(this.cA, 60) : ColorUtil.withAlpha(this.cA, 30));
         this.textCenter(this.fit(s.name(), w - 12), x + w / 2, y + 7, this.cFg);
         this.hits.add(x, y, w, rh).clip(ct, cb).left = (a, b) -> {
            this.commitEdit(true);
            act.run();
            if (ItemSizeModule.class.isInstance(this.open) || "Open item editor".equals(act.name())) {
               // the per-item editor lives in the New menu; it picks up the request on its first frame
               if ("Open item editor".equals(act.name())) {
                  Compat.setScreen(class_310.method_1551(), new NewGuiScreen());
               }
            }
         };
         return;
      }
      this.text(this.fit(s.name(), w - ctrlW - 24), x + 8, y + 7, this.cFg);
      if (s instanceof BoolSetting bool) {
         int sx = x + w - 32;
         this.drawSwitch(sx, y + 5, bool.on(), true);
         this.hits.add(x, y, w, rh).clip(ct, cb).left = (a, b) -> bool.toggle();
      } else if (s instanceof NumberSetting num) {
         boolean editingThis = this.editing == num;
         String val = editingThis ? this.edit.text() + (System.currentTimeMillis() / 500L % 2L == 0L ? "_" : "") : num.display();
         int vw = this.width(val);
         this.text(val, x + w - 8 - vw, y + 7, editingThis ? this.cA : this.cMuted);
         this.hits.add(x + w - 12 - Math.max(vw, 24), y + 3, Math.max(vw, 24) + 6, 14).clip(ct, cb).left = (a, b) ->
            this.beginEdit(num, plain(num.asDouble()), 2, 12, c -> Character.isDigit(c) || c == '.' || c == '-' || c == ',');
         int tx = x + 8;
         int tw = w - 16;
         int ty = y + 22;
         float f = (float)Math.max(0.0, Math.min(1.0, num.fraction()));
         Render2D.round(this.g, tx, ty, tw, 4, 2, this.light ? 0xFFE3E6EE : this.cField);
         this.gradH(tx, ty, Math.round(tw * f), 4, this.cA, this.cB);
         int kx = tx + Math.round(tw * f) - 4;
         Render2D.round(this.g, kx, ty - 2, 8, 8, 4, 0xFFFFFFFF);
         Render2D.outline(this.g, kx, ty - 2, 8, 8, this.cA);
         Hits.Action set = (a, b) -> {
            this.commitEdit(true);
            num.setFraction(Math.max(0.0, Math.min(1.0, (a - tx) / Math.max(1.0, tw))));
         };
         Hits.Hit hit = this.hits.add(tx - 4, ty - 5, tw + 8, 14).clip(ct, cb);
         hit.left = set;
         hit.drag = set;
      } else if (s instanceof ModeSetting mode) {
         String[] modes = mode.modes();
         int total = 0;
         for (String o : modes) {
            total += this.smallWidth(o) + 10;
         }
         if (modes.length <= 5 && total <= ctrlW) {
            int ox = x + w - 8 - total;
            for (String o : modes) {
               int ow = this.smallWidth(o) + 10;
               boolean on = mode.is(o);
               boolean oh = this.over(ox, y + 4, ow, 14);
               Render2D.round(this.g, ox, y + 4, ow, 14, 4, on ? this.cA : oh ? this.cCardHover : this.light ? 0xFFF0F2F7 : this.cField);
               this.small(o, ox + 5, y + 8, on ? this.cOn : this.cMuted);
               this.hits.add(ox, y + 4, ow, 14).clip(ct, cb).left = (a, b) -> mode.set(o);
               ox += ow;
            }
         } else {
            String v = "‹ " + mode.get() + " ›";
            int vw = this.width(v) + 12;
            int vx = x + w - 8 - vw;
            Render2D.round(this.g, vx, y + 4, vw, 14, 4, this.light ? 0xFFF0F2F7 : this.cField);
            this.textCenter(v, vx + vw / 2, y + 7, this.cFg);
            Hits.Hit hit = this.hits.add(vx, y + 4, vw, 14).clip(ct, cb);
            hit.left = (a, b) -> mode.cycle(a < vx + vw / 2.0 ? -1 : 1);
            hit.right = (a, b) -> mode.cycle(-1);
         }
      } else if (s instanceof ColorSetting col) {
         boolean editingThis = this.editing == col;
         String hex = editingThis ? this.edit.text() + (System.currentTimeMillis() / 500L % 2L == 0L ? "_" : "") : col.hex();
         int hw = this.width(hex) + 12;
         int hx = x + w - 8 - hw;
         Render2D.round(this.g, hx, y + 4, hw, 14, 4, this.light ? 0xFFF0F2F7 : this.cField);
         this.text(hex, hx + 6, y + 7, editingThis ? this.cA : this.cMuted);
         this.hits.add(hx, y + 4, hw, 14).clip(ct, cb).left = (a, b) ->
            this.beginEdit(col, col.hex(), 1, col.hasAlpha() ? 9 : 7, c -> c == '#' || Character.digit(c, 16) >= 0);
         int sx = hx - 22;
         Render2D.round(this.g, sx, y + 4, 18, 14, 4, 0xFF000000 | 0x303030);
         Render2D.round(this.g, sx + 1, y + 5, 16, 12, 3, col.argb());
         this.hits.add(sx, y + 4, 18, 14).clip(ct, cb).left = (a, b) -> {
            this.commitEdit(true);
            ColorPickerBridge.open(col, a, b);
         };
      } else if (s instanceof TextSetting txt) {
         boolean editingThis = this.editing == txt;
         int fx = x + 8;
         int fy = y + 17;
         int fw = w - 16;
         Render2D.round(this.g, fx, fy, fw, 12, 4, this.light ? 0xFFF0F2F7 : this.cField);
         if (editingThis) {
            this.accentOutline(fx, fy, fw, 12);
         }
         String v = editingThis ? this.edit.text() + (System.currentTimeMillis() / 500L % 2L == 0L ? "_" : "") : txt.get();
         if ((v == null || v.isEmpty()) && !editingThis) {
            this.small(this.fitSmall(txt.placeholder(), fw - 8), fx + 4, fy + 3, this.cFaint);
         } else {
            this.small(this.fitSmall(v, fw - 8), fx + 4, fy + 3, this.cFg);
         }
         this.hits.add(fx, fy, fw, 12).clip(ct, cb).left = (a, b) -> this.beginEdit(txt, txt.get() == null ? "" : txt.get(), 0, Math.max(1, txt.maxLength()), c -> true);
      }
   }

   private static String plain(double v) {
      if (v == Math.rint(v)) {
         return String.valueOf((long)v);
      }
      String s = String.format(Locale.ROOT, "%.3f", v);
      while (s.endsWith("0")) {
         s = s.substring(0, s.length() - 1);
      }
      return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
   }

   // ------------------------------------------------------------------ text editing

   private void beginEdit(Setting<?> s, String text, int kind, int max, java.util.function.IntPredicate filter) {
      this.commitEdit(true);
      this.searchFocus = false;
      this.editing = s;
      this.editKind = kind;
      this.edit.begin(text, max, filter);
   }

   private void commitEdit(boolean apply) {
      Setting<?> s = this.editing;
      if (s == null) {
         return;
      }
      this.editing = null;
      if (!apply) {
         return;
      }
      String t = this.edit.text().trim();
      if (s instanceof TextSetting txt) {
         txt.set(t);
      } else if (s instanceof ColorSetting col) {
         col.setHex(t.startsWith("#") ? t : "#" + t);
      } else if (s instanceof NumberSetting num) {
         try {
            double v = Double.parseDouble(t.replace(',', '.'));
            num.set(Math.max(num.min(), Math.min(num.max(), v)));
         } catch (NumberFormatException ignored) {
         }
      }
   }

   // ------------------------------------------------------------------ input

   @Override
   protected boolean onKey(int key, int scancode, int mods) {
      if (this.editing != null) {
         if (key == 256) {
            this.commitEdit(false);
         } else if (key == 257 || key == 335) {
            this.commitEdit(true);
         } else if (key == 259) {
            this.edit.backspace();
         }
         return true;
      }
      if (key == 256) {
         if (this.searchFocus && !this.search.text().isEmpty()) {
            this.search.clear();
            return true;
         }
         if (this.open != null) {
            this.closeModule();
            return true;
         }
         this.method_25419();
         return true;
      }
      if (SPOTLIGHT.equals(this.skin) && this.open == null) {
         if (key == 264 || key == 265) {
            int n = this.shown.size();
            if (n > 0) {
               this.selected = Math.floorMod(this.selected + (key == 264 ? 1 : -1), n);
            }
            return true;
         }
         if ((key == 257 || key == 335) && this.selected < this.shown.size()) {
            this.toggle(this.shown.get(this.selected));
            return true;
         }
         if ((key == 262 || key == 258) && this.selected < this.shown.size()) {
            this.openModule(this.shown.get(this.selected));
            return true;
         }
      } else if (SPOTLIGHT.equals(this.skin) && key == 263) {
         this.closeModule();
         return true;
      }
      if (this.searchFocus) {
         if (key == 259) {
            this.search.backspace();
            this.scroll = 0.0F;
            this.selected = 0;
            if (!this.search.text().isEmpty()) {
               this.open = this.open == null || NEON.equals(this.skin) ? this.open : null;
            }
            return true;
         }
         if (key == 257 || key == 335) {
            this.searchFocus = SPOTLIGHT.equals(this.skin);
            return true;
         }
      }
      return false;
   }

   @Override
   protected boolean onChar(String chars) {
      if (this.editing != null) {
         this.edit.append(chars);
         return true;
      }
      if (!this.searchFocus && chars != null && !chars.isBlank() && this.open == null) {
         // typing anywhere starts a search
         this.searchFocus = true;
      }
      if (this.searchFocus) {
         this.search.append(chars);
         this.scroll = 0.0F;
         this.selected = 0;
         if (!NEON.equals(this.skin)) {
            this.open = null;
         }
         return true;
      }
      return false;
   }

   @Override
   protected boolean onMouseDown(double x, double y, int button) {
      Hits.Hit hit = this.hits.find(x, y);
      boolean wasEditing = this.editing != null;
      if (hit == null || hit.left == null && hit.right == null && hit.drag == null) {
         this.commitEdit(true);
         if (!SPOTLIGHT.equals(this.skin)) {
            this.searchFocus = false;
         }
         return wasEditing;
      }
      if (this.editing != null && hit.left != null) {
         // clicking another control commits the text first (the hit's own action may start a new edit)
         Setting<?> before = this.editing;
         hit.left.run(x, y);
         if (this.editing == before) {
            this.commitEdit(true);
         }
         this.dragAction = hit.drag;
         return true;
      }
      if (!SPOTLIGHT.equals(this.skin)) {
         this.searchFocus = false;
      }
      if (button == 0) {
         if (hit.left != null) {
            hit.left.run(x, y);
         }
         this.dragAction = hit.drag;
      } else if (button == 1 && hit.right != null) {
         hit.right.run(x, y);
      }
      return true;
   }

   @Override
   protected boolean onMouseDrag(double x, double y, int button, double dx, double dy) {
      if (this.dragAction != null) {
         this.dragAction.run(x, y);
         return true;
      }
      return false;
   }

   @Override
   protected boolean onMouseUp(double x, double y, int button) {
      boolean had = this.dragAction != null;
      this.dragAction = null;
      return had;
   }

   @Override
   public boolean method_25401(double x, double y, double h, double v) {
      if (x >= this.scrollX && x < this.scrollX + this.scrollW + 6 && y >= this.scrollY && y < this.scrollY + this.scrollH) {
         this.scroll = (float)Math.max(0.0, Math.min(this.maxScroll, this.scroll - v * 24.0));
         return true;
      }
      return false;
   }

   // ------------------------------------------------------------------ small helpers

   private boolean over(int x, int y, int w, int h) {
      return this.mx >= x && this.my >= y && this.mx < x + w && this.my < y + h;
   }

   private void scissor(int x1, int y1, int x2, int y2) {
      this.g.method_44379(x1, y1, x2, y2);
   }

   private void unscissor() {
      this.g.method_44380();
   }

   private int width(String s) {
      return Fonts.width(this.font, s);
   }

   private int smallWidth(String s) {
      return Math.round(Fonts.width(this.font, s) * 0.75F);
   }

   private String fit(String s, int w) {
      return Render2D.fit(this.font, s == null ? "" : s, Math.max(8, w));
   }

   private String fitSmall(String s, int w) {
      return Render2D.fit(this.font, s == null ? "" : s, Math.max(8, Math.round(w / 0.75F)));
   }

   private void text(String s, int x, int y, int c) {
      Fonts.draw(this.g, this.font, s, x, y, c, false);
   }

   private void textCenter(String s, int cx, int y, int c) {
      Fonts.draw(this.g, this.font, s, cx - this.width(s) / 2, y, c, false);
   }

   private void small(String s, int x, int y, int c) {
      Compat.push(this.g);
      Compat.translate(this.g, x, y);
      Compat.scale(this.g, 0.75F, 0.75F);
      Fonts.draw(this.g, this.font, s, 0, 0, c, false);
      Compat.pop(this.g);
   }

   private static int mix(int a, int b, float t) {
      int ar = a >> 16 & 255;
      int ag = a >> 8 & 255;
      int ab = a & 255;
      int r = Math.round(ar + ((b >> 16 & 255) - ar) * t);
      int g = Math.round(ag + ((b >> 8 & 255) - ag) * t);
      int bb = Math.round(ab + ((b & 255) - ab) * t);
      return 0xFF000000 | r << 16 | g << 8 | bb;
   }
}
