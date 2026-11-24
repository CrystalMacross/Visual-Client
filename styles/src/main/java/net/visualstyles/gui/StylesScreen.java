package net.visualstyles.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.class_11905;
import net.minecraft.class_11908;
import net.minecraft.class_11909;
import net.minecraft.class_156;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_437;
import net.visualstyles.Config;
import net.visualstyles.style.Col;
import net.visualstyles.style.Coll;
import net.visualstyles.style.ImportedPacks;
import net.visualstyles.style.Previews;
import net.visualstyles.style.Style;
import net.visualstyles.style.StylePack;
import net.visualstyles.style.Styles;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * The Styles menu with five designs that all work the same way (same categories, click picks, Apply wears every
 * pick at once, right click zooms, F favourites, presets, extras):
 *  Studio   - category rail, card grid, inspector
 *  Glass    - light frosted panel, category pills on top, floating inspector
 *  Neon     - dark neon panel with glow and scanlines, icon strip
 *  Showcase - big preview on the left, list of looks on the right, category tabs
 *  Dock     - drawer on the right edge, the game stays visible next to it
 * Screen methods use the 1.21.11 intermediary names (1.21.9+ input events).
 */
public final class StylesScreen extends class_437 {
   private static Coll lastColl = Coll.CRYSTAL;
   private static boolean lastFavOnly;

   private enum Tab { STYLES, EXTRAS }

   private enum Layout {
      STUDIO("studio", "Studio", Ui.STUDIO),
      GLASS("glass", "Glass", Ui.GLASS),
      NEON("neon", "Neon", Ui.NEON),
      SHOWCASE("showcase", "Showcase", Ui.SHOWCASE),
      DOCK("dock", "Dock", Ui.DOCK);

      final String id;
      final String title;
      final Ui.Palette palette;

      Layout(String id, String title, Ui.Palette palette) {
         this.id = id;
         this.title = title;
         this.palette = palette;
      }

      static Layout of(String id) {
         for (Layout l : values()) {
            if (l.id.equals(id)) {
               return l;
            }
         }
         return STUDIO;
      }
   }

   private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
      boolean in(double mx, double my) {
         return mx >= this.x && my >= this.y && mx < this.x + this.w && my < this.y + this.h;
      }
   }

   private static final int SLIDER_SPIN = 0;
   private static final int SLIDER_FIRE = 1;
   private static final int HEX_CORE = 0;
   private static final int HEX_GLOW = 1;

   private Tab tab = Tab.STYLES;
   private Coll coll;
   private String search = "";
   private boolean searchFocus;
   private boolean swallowChar;
   private boolean favOnly;
   private float scroll;
   private int maxScroll;
   private int gridX;
   private int gridY;
   private int gridW;
   private int gridH;
   private Style hovered;
   private Style focus;
   private Style zoom;
   private String toast = "";
   private long toastUntil;
   private boolean shiftClick;
   private long lastScan;
   private int editHex = -1;
   private String hexText = "";
   private int dragSlider = -1;
   private String tooltip;
   private int tooltipX;
   private int tooltipY;
   private final Map<Integer, int[]> sliderRects = new HashMap<>();
   private final List<Hit> hits = new ArrayList<>();
   /** Styles picked but not applied yet (collection -> style id); Apply writes them all at once. */
   private static final Map<Coll, String> PENDING = new EnumMap<>(Coll.class);

   public StylesScreen() {
      super(class_2561.method_43470("Visual Styles"));
      this.coll = lastColl;
      this.favOnly = lastFavOnly;
      Styles.refreshCustom();
      this.lastScan = System.currentTimeMillis();
   }

   private static Layout layout() {
      return Layout.of(Config.get().ui);
   }

   private void cycleLayout(int dir) {
      Layout[] all = Layout.values();
      Layout next = all[Math.floorMod(layout().ordinal() + dir, all.length)];
      this.setLayout(next);
   }

   private void setLayout(Layout l) {
      Config.get().ui = l.id;
      Config.get().save();
      this.scroll = 0.0F;
      this.focus = null;
      this.toast("Menu design: " + l.title + "  ·  same functions, new look");
   }

   // ================================================================== screen basics

   @Override
   public boolean method_25421() {
      return false;
   }

   @Override
   public void method_25420(class_332 g, int mouseX, int mouseY, float delta) {
      if (class_310.method_1551().field_1687 == null) {
         super.method_25420(g, mouseX, mouseY, delta);
      } else if (layout() != Layout.DOCK) {
         // Dock keeps the game sharp next to the drawer
         this.method_57734(g);
      }
   }

   @Override
   public void method_25419() {
      lastColl = this.coll;
      lastFavOnly = this.favOnly;
      Config.get().save();
      Previews.clear();
      super.method_25419();
   }

   private void toast(String message) {
      this.toast = message;
      this.toastUntil = System.currentTimeMillis() + 2600L;
   }

   private void longToast(String message) {
      this.toast = message;
      this.toastUntil = System.currentTimeMillis() + 6000L;
   }

   // ------------------------------------------------------------------ importing .zip packs

   private static volatile boolean dialogOpen;

   /** "Import ZIP": native file dialog (several zips at once); without one the drop folder opens. */
   private void importZip() {
      if (dialogOpen) {
         return;
      }
      dialogOpen = true;
      Thread t = new Thread(() -> {
         try {
            String picked;
            try (MemoryStack st = MemoryStack.stackPush()) {
               PointerBuffer filter = st.mallocPointer(1);
               filter.put(st.UTF8("*.zip"));
               filter.flip();
               Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
               String start = (Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home"))).toString() + java.io.File.separator;
               picked = TinyFileDialogs.tinyfd_openFileDialog("Import texture packs (.zip)", start, filter, "Texture packs (*.zip)", true);
            }
            if (picked == null || picked.isBlank()) {
               return;
            }
            List<Path> zips = new ArrayList<>();
            for (String f : picked.split("\\|")) {
               Path p = Path.of(f.trim());
               if (ImportedPacks.isZip(p)) {
                  zips.add(p);
               }
            }
            if (!zips.isEmpty()) {
               ImportedPacks.importAsync(zips);
               importing = zips.size();
            }
         } catch (Throwable e) {
            importing = -1;
         } finally {
            dialogOpen = false;
         }
      }, "Visual Styles file dialog");
      t.setDaemon(true);
      t.start();
   }

   /** Set by the dialog thread: number of zips being imported, -1 = no dialog available. */
   private static volatile int importing;

   private void openZipFolder() {
      try {
         Path in = ImportedPacks.inbox();
         Files.createDirectories(in);
         class_156.method_668().method_60932(in);
         this.longToast("Drop .zip packs into config/visualstyles/zips/ (or onto this menu)");
      } catch (Exception e) {
         this.toast("Could not open the folder: " + e.getMessage());
      }
   }

   /** Zips dragged onto the window while the menu is open (Screen.onFilesDrop). */
   public void method_29638(List<Path> paths) {
      List<Path> zips = new ArrayList<>();
      for (Path p : paths) {
         if (ImportedPacks.isZip(p)) {
            zips.add(p);
         }
      }
      if (zips.isEmpty()) {
         this.toast("Only .zip texture packs can be imported");
         return;
      }
      ImportedPacks.importAsync(zips);
      this.longToast("Importing " + zips.size() + (zips.size() == 1 ? " pack" : " packs") + " ...");
   }

   private void pollImports() {
      int n = importing;
      if (n != 0) {
         importing = 0;
         if (n < 0) {
            this.openZipFolder();
         } else {
            this.longToast("Importing " + n + (n == 1 ? " pack" : " packs") + " ...");
         }
      }
      String done = ImportedPacks.takeFinished();
      if (done != null) {
         Styles.refreshCustom();
         this.longToast(done);
      }
   }

   private void deleteImported(Style s) {
      String id = s.id.substring(s.id.indexOf(':') + 1);
      if (ImportedPacks.delete(id)) {
         for (Coll c : Coll.values()) {
            if (s.id.equals(PENDING.get(c))) {
               PENDING.remove(c);
            }
         }
         Styles.refreshCustom();
         this.hovered = null;
         this.focus = null;
         this.toast(s.name + " removed  ·  press Apply if you were wearing it");
         for (Coll c : Coll.values()) {
            String sel = Config.get().selected.get(c.id);
            if (sel != null && sel.substring(sel.indexOf(':') + 1).equals(id) && (sel.startsWith("zip:") || sel.startsWith("zipfull:"))) {
               PENDING.put(c, Style.VANILLA.id);
            }
         }
      }
   }

   // ================================================================== actions (identical in every design)

   /** Picks a style for the open collection; nothing changes in game until Apply. */
   private void wear(Style s) {
      if (s.id.equals(Styles.selected(this.coll).id)) {
         PENDING.remove(this.coll);
      } else {
         PENDING.put(this.coll, s.id);
      }
      this.toast(this.coll.title + ": " + s.name + " picked  ·  press Apply to wear everything");
   }

   /** The style a collection will have after Apply (pending pick, else the applied one). */
   private static Style effective(Coll c) {
      String id = PENDING.get(c);
      return id != null ? Styles.find(c, id) : Styles.selected(c);
   }

   private void applyPending() {
      if (PENDING.isEmpty()) {
         this.toast("Nothing picked yet");
         return;
      }
      int n = PENDING.size();
      for (Map.Entry<Coll, String> e : PENDING.entrySet()) {
         Styles.select(e.getKey(), Styles.find(e.getKey(), e.getValue()));
      }
      PENDING.clear();
      this.toast("Applied " + n + (n == 1 ? " collection" : " collections") + "  ·  " + StylePack.apply());
   }

   private void resetCollection() {
      PENDING.put(this.coll, Style.VANILLA.id);
      this.toast(this.coll.title + " back to vanilla  ·  press Apply");
   }

   private void resetAll() {
      for (Coll c : Coll.values()) {
         PENDING.put(c, Style.VANILLA.id);
      }
      this.toast("Everything back to vanilla  ·  press Apply");
   }

   private void preset(int i) {
      Config cfg = Config.get();
      if (this.shiftClick) {
         cfg.presets.set(i, new LinkedHashMap<>(cfg.selected));
         cfg.save();
         this.toast("Saved your look as preset " + (i + 1));
         return;
      }
      Map<String, String> p = cfg.presets.get(i);
      if (p == null) {
         this.toast("Preset " + (i + 1) + " is empty  ·  Shift+click to save your look");
         return;
      }
      PENDING.clear();
      cfg.selected = new LinkedHashMap<>(p);
      cfg.save();
      this.toast("Preset " + (i + 1) + "  ·  " + StylePack.apply());
   }

   private void openFolder() {
      try {
         Path root = Styles.customRoot();
         Path dir = Styles.customDir(this.coll);
         Files.createDirectories(dir);
         Path readme = root.resolve("README.txt");
         if (!Files.exists(readme)) {
            Files.writeString(readme, README);
         }
         class_156.method_668().method_60932(dir);
         Styles.refreshCustom();
         this.toast("Put PNGs into config/visualstyles/custom/" + this.coll.id + "/");
      } catch (Exception e) {
         this.toast("Could not open the folder: " + e.getMessage());
      }
   }

   /** Extras that change textures (core / glow colour) need the pack rebuilt. */
   private void applyTextureExtras() {
      Config.get().save();
      Previews.clear();
      this.toast(StylePack.apply());
   }

   private void startHex(int which) {
      Config cfg = Config.get();
      this.editHex = which;
      this.hexText = which == HEX_CORE ? cfg.crystalCore : cfg.anchorGlow;
      this.searchFocus = false;
   }

   private void commitHex() {
      if (this.editHex < 0) {
         return;
      }
      int which = this.editHex;
      this.editHex = -1;
      if (this.hexText.length() != 6 || Col.parseHex(this.hexText, -1) == -1) {
         this.toast("A colour needs 6 hex digits, like 8B5CF6");
         return;
      }
      Config cfg = Config.get();
      String hex = this.hexText.toUpperCase(Locale.ROOT);
      if (which == HEX_CORE) {
         cfg.crystalCore = hex;
         cfg.crystalCoreOn = true;
      } else {
         cfg.anchorGlow = hex;
         cfg.anchorGlowOn = true;
      }
      this.applyTextureExtras();
   }

   private void setSlider(int which, double mouseX) {
      int[] r = this.sliderRects.get(which);
      if (r == null) {
         return;
      }
      float t = (float)Math.max(0.0, Math.min(1.0, (mouseX - r[0]) / Math.max(1, r[1])));
      Config cfg = Config.get();
      if (which == SLIDER_SPIN) {
         float v = Math.round(t * 30.0F) / 10.0F;
         cfg.crystalSpin = Math.abs(v - 1.0F) < 0.05F ? 1.0F : v;
      } else {
         cfg.lowFire = Math.round(t * 20.0F) / 20.0F;
      }
   }

   private void selectColl(Coll c) {
      if (this.coll != c) {
         this.coll = c;
         this.scroll = 0.0F;
         this.focus = null;
      }
   }

   private List<Style> visibleStyles() {
      String q = this.search.trim().toLowerCase(Locale.ROOT);
      List<Style> out = new ArrayList<>();
      for (Style s : Styles.all(this.coll)) {
         if (this.favOnly && !Styles.favorite(this.coll, s)) {
            continue;
         }
         if (!q.isEmpty() && !s.name.toLowerCase(Locale.ROOT).contains(q)) {
            continue;
         }
         out.add(s);
      }
      return out;
   }

   private static String source(Style s) {
      return s.custom() ? "Your file" : s.imported() ? "Your ZIP pack" : s.pack() ? "Texture pack" : s.vanilla() ? "Minecraft default" : "Built in";
   }

   private static String tag(Style s) {
      return s.custom() ? "YOUR FILE" : s.imported() ? "YOUR ZIP" : s.pack() ? "PACK" : s.vanilla() ? "DEFAULT" : "BUILT-IN";
   }

   private static int sourceColor(Style s) {
      return s.custom() || s.imported() ? Ui.TAG : s.pack() ? Ui.GOLD : s.vanilla() ? Ui.FAINT : Ui.SOFT;
   }

   // ================================================================== render

   @Override
   public void method_25394(class_332 g, int mouseX, int mouseY, float delta) {
      class_327 font = this.field_22793;
      Layout l = layout();
      Ui.use(l.palette);
      this.hits.clear();
      this.hovered = null;
      this.tooltip = null;
      if (System.currentTimeMillis() - this.lastScan > 2000L) {
         // pick up PNGs the player just dropped into the custom folder
         Styles.refreshCustom();
         this.lastScan = System.currentTimeMillis();
         if (ImportedPacks.importInbox()) {
            this.longToast("Importing the zips from config/visualstyles/zips/ ...");
         }
      }
      this.pollImports();

      int sw = this.field_22789;
      int sh = this.field_22790;
      int[] box;
      if (l == Layout.DOCK) {
         box = this.renderDock(g, font, sw, sh, mouseX, mouseY);
      } else if (l == Layout.SHOWCASE) {
         box = this.renderShowcase(g, font, sw, sh, mouseX, mouseY);
      } else {
         box = this.renderPanel(g, font, l, sw, sh, mouseX, mouseY);
      }

      if (this.tooltip != null) {
         int tw = Ui.width(font, this.tooltip) + 10;
         int tx = Math.min(this.tooltipX, sw - tw - 2);
         Ui.round(g, tx, this.tooltipY, tw, 14, Ui.PANEL2);
         Ui.roundOutline(g, tx, this.tooltipY, tw, 14, Ui.LINE2);
         Ui.text(g, font, this.tooltip, tx + 5, this.tooltipY + 3, Ui.FG);
      }

      if (System.currentTimeMillis() < this.toastUntil && !this.toast.isEmpty()) {
         int x0 = box[0];
         int w = box[2];
         String t = Ui.fit(font, this.toast, w - 40);
         int tw = Ui.width(font, t) + 20;
         int tx = x0 + (w - tw) / 2;
         int ty = box[1] + box[3] - box[4] - 22;
         Ui.round(g, tx, ty, tw, 17, Ui.alpha(Ui.PANEL2, 0.96F));
         Ui.accentOutline(g, tx, ty, tw, 17);
         Ui.text(g, font, t, tx + 10, ty + 5, Ui.FG);
      }

      if (this.zoom != null) {
         this.renderZoom(g, font, sw, sh);
      }
   }

   // ------------------------------------------------------------------ designs

   /** Studio, Glass and Neon: a centred panel. Returns {x, y, w, h, footerH}. */
   private int[] renderPanel(class_332 g, class_327 font, Layout l, int sw, int sh, int mx, int my) {
      Ui.rect(g, 0, 0, sw, sh, Ui.DIM);
      int w = Math.min(sw - 12, 640);
      int h = Math.min(sh - 12, 352);
      int x0 = (sw - w) / 2;
      int y0 = (sh - h) / 2;
      if (l == Layout.NEON) {
         Ui.glow(g, x0, y0, w, h, Ui.VIOLET, 7);
         Ui.rect(g, x0, y0, w, h, Ui.PANEL);
         Ui.accentOutline(g, x0, y0, w, h);
      } else if (l == Layout.GLASS) {
         Ui.shadow(g, x0, y0, w, h);
         Ui.round2(g, x0, y0, w, h, Ui.PANEL);
         Ui.gradientV(g, x0 + 2, y0 + 1, w - 4, 24, 0x80FFFFFF, 0x00FFFFFF);
         Ui.roundOutline(g, x0, y0, w, h, 0xFFFFFFFF);
      } else {
         Ui.shadow(g, x0, y0, w, h);
         Ui.round(g, x0, y0, w, h, Ui.PANEL);
         Ui.roundOutline(g, x0, y0, w, h, Ui.LINE2);
         Ui.gradientH(g, x0 + 1, y0, w - 2, 1, Ui.VIOLET, Ui.CYAN);
      }

      this.header(g, font, x0, y0, w, mx, my, false);
      int bodyY = y0 + 27;
      int bodyH = h - 27 - 25;
      if (this.tab == Tab.STYLES) {
         if (l == Layout.GLASS) {
            this.stylesGlass(g, font, x0, bodyY, w, bodyH, mx, my);
         } else if (l == Layout.NEON) {
            this.stylesNeon(g, font, x0, bodyY, w, bodyH, mx, my);
         } else {
            this.stylesStudio(g, font, x0, bodyY, w, bodyH, mx, my);
         }
      } else {
         this.renderExtras(g, font, x0 + 12, bodyY + 8, w - 24, bodyH - 10, mx, my, false);
      }
      this.footer(g, font, x0, y0 + h - 25, w, mx, my, false);
      if (l == Layout.NEON) {
         Ui.scanlines(g, x0 + 1, y0 + 1, w - 2, h - 2);
      }
      return new int[]{x0, y0, w, h, 25};
   }

   private void stylesStudio(class_332 g, class_327 font, int x0, int bodyY, int w, int bodyH, int mx, int my) {
      int railW = w >= 520 ? 128 : 104;
      int inspW = w >= 540 ? 146 : 0;
      int railX = x0 + 1;
      Ui.rect(g, railX, bodyY, railW, bodyH, Ui.RAIL);
      Ui.rect(g, railX + railW, bodyY, 1, bodyH, Ui.LINE);
      Ui.small(g, font, "CATEGORIES", railX + 10, bodyY + 7, Ui.FAINT);
      this.categoryRail(g, font, railX, bodyY + 17, railW, bodyH - 21, mx, my);
      int gx = railX + railW + 10;
      int gRight = x0 + w - 8 - (inspW > 0 ? inspW + 9 : 0);
      int top = this.toolbar(g, font, gx, bodyY + 7, gRight - gx, mx, my, true);
      boolean big = gRight - gx >= 300;
      this.grid(g, font, gx, top, gRight - gx, bodyY + bodyH - 6 - top, mx, my, big ? 66 : 56, big ? 46 : 38, 6, false);
      if (inspW > 0) {
         int ix = x0 + w - 8 - inspW;
         Ui.rect(g, ix - 5, bodyY, 1, bodyH, Ui.LINE);
         this.inspector(g, font, ix, bodyY + 6, inspW, bodyH - 10, mx, my);
      }
   }

   private void stylesGlass(class_332 g, class_327 font, int x0, int bodyY, int w, int bodyH, int mx, int my) {
      // category pills along the top
      this.categoryPills(g, font, x0 + 10, bodyY + 6, w - 20, mx, my, false);
      int inspW = w >= 540 ? 150 : 0;
      int gx = x0 + 12;
      int gRight = x0 + w - 12 - (inspW > 0 ? inspW + 10 : 0);
      int top = this.toolbar(g, font, gx, bodyY + 36, gRight - gx, mx, my, true);
      boolean big = gRight - gx >= 330;
      this.grid(g, font, gx, top, gRight - gx, bodyY + bodyH - 6 - top, mx, my, big ? 70 : 58, big ? 50 : 40, 7, false);
      if (inspW > 0) {
         int ix = x0 + w - 12 - inspW;
         int iy = bodyY + 36;
         int ih = bodyH - 42;
         Ui.round2(g, ix - 4, iy - 4, inspW + 8, ih + 8, Ui.PANEL2);
         Ui.roundOutline(g, ix - 4, iy - 4, inspW + 8, ih + 8, Ui.LINE);
         this.inspector(g, font, ix, iy, inspW, ih, mx, my);
      }
   }

   private void stylesNeon(class_332 g, class_327 font, int x0, int bodyY, int w, int bodyH, int mx, int my) {
      int stripW = 30;
      Ui.rect(g, x0 + 1, bodyY, stripW, bodyH, Ui.RAIL);
      Ui.gradientV(g, x0 + stripW + 1, bodyY, 1, bodyH, Ui.VIOLET, Ui.CYAN);
      this.categoryIcons(g, font, x0 + 4, bodyY + 4, bodyH - 8, mx, my);
      int inspW = w >= 520 ? 140 : 0;
      int gx = x0 + stripW + 12;
      int gRight = x0 + w - 8 - (inspW > 0 ? inspW + 9 : 0);
      int top = this.toolbar(g, font, gx, bodyY + 7, gRight - gx, mx, my, true);
      boolean big = gRight - gx >= 320;
      this.grid(g, font, gx, top, gRight - gx, bodyY + bodyH - 6 - top, mx, my, big ? 66 : 56, big ? 46 : 38, 6, true);
      if (inspW > 0) {
         int ix = x0 + w - 8 - inspW;
         Ui.gradientV(g, ix - 5, bodyY, 1, bodyH, Ui.CYAN, Ui.VIOLET);
         this.inspector(g, font, ix, bodyY + 6, inspW, bodyH - 10, mx, my);
      }
   }

   /** Showcase: tabs on top, big preview on the left, list of looks on the right. */
   private int[] renderShowcase(class_332 g, class_327 font, int sw, int sh, int mx, int my) {
      Ui.rect(g, 0, 0, sw, sh, Ui.DIM);
      int w = Math.min(sw - 12, 680);
      int h = Math.min(sh - 12, 372);
      int x0 = (sw - w) / 2;
      int y0 = (sh - h) / 2;
      Ui.shadow(g, x0, y0, w, h);
      Ui.round2(g, x0, y0, w, h, Ui.PANEL);
      Ui.roundOutline(g, x0, y0, w, h, Ui.LINE2);
      Ui.gradientH(g, x0 + 2, y0, w - 4, 1, Ui.VIOLET, Ui.CYAN);
      this.header(g, font, x0, y0, w, mx, my, false);
      int bodyY = y0 + 27;
      int bodyH = h - 27 - 25;
      if (this.tab == Tab.STYLES) {
         this.categoryPills(g, font, x0 + 10, bodyY + 5, w - 20, mx, my, true);
         int top = bodyY + 32;
         int leftW = Math.max(150, Math.min(260, w * 2 / 5));
         int lx = x0 + 10;
         int lh = bodyY + bodyH - 6 - top;
         Ui.round2(g, lx, top, leftW, lh, Ui.PANEL2);
         Ui.roundOutline(g, lx, top, leftW, lh, Ui.LINE);
         this.showcasePanel(g, font, lx, top, leftW, lh, mx, my);
         int rx = lx + leftW + 10;
         int rw = x0 + w - 10 - rx;
         int listTop = this.toolbar(g, font, rx, top, rw, mx, my, false);
         this.rows(g, font, rx, listTop, rw, bodyY + bodyH - 6 - listTop, mx, my);
      } else {
         this.renderExtras(g, font, x0 + 12, bodyY + 8, w - 24, bodyH - 10, mx, my, false);
      }
      this.footer(g, font, x0, y0 + h - 25, w, mx, my, false);
      return new int[]{x0, y0, w, h, 25};
   }

   /** Dock: a drawer on the right edge; the game stays visible (and unblurred) on the left. */
   private int[] renderDock(class_332 g, class_327 font, int sw, int sh, int mx, int my) {
      int w = Math.max(Math.min(250, sw - 4), Math.min(348, sw * 2 / 5));
      int x0 = sw - w;
      int y0 = 0;
      int h = sh;
      Ui.gradientH(g, x0 - 24, 0, 24, sh, 0x00000000, 0x66000000);
      Ui.rect(g, x0, y0, w, h, Ui.PANEL);
      Ui.gradientV(g, x0, y0, 1, h, Ui.VIOLET, Ui.CYAN);
      this.header(g, font, x0, y0, w, mx, my, true);
      int footerH = 42;
      int bodyY = y0 + 27;
      int bodyH = h - 27 - footerH;
      if (this.tab == Tab.STYLES) {
         int stripW = 28;
         Ui.rect(g, x0 + 1, bodyY, stripW, bodyH, Ui.RAIL);
         Ui.rect(g, x0 + stripW + 1, bodyY, 1, bodyH, Ui.LINE);
         this.categoryIcons(g, font, x0 + 4, bodyY + 4, bodyH - 8, mx, my);
         int gx = x0 + stripW + 9;
         int gw = x0 + w - 8 - gx;
         int top = this.toolbar(g, font, gx, bodyY + 7, gw, mx, my, true);
         // compact inspector line
         Style s = this.hovered != null ? this.hovered : effective(this.coll);
         top = this.dockInspector(g, font, gx, top, gw, mx, my);
         this.grid(g, font, gx, top, gw, bodyY + bodyH - 6 - top, mx, my, 56, 38, 5, false);
      } else {
         this.renderExtras(g, font, x0 + 10, bodyY + 8, w - 20, bodyH - 10, mx, my, false);
      }
      this.footer(g, font, x0, y0 + h - footerH, w, mx, my, true);
      return new int[]{x0, y0, w, h, footerH};
   }

   // ------------------------------------------------------------------ header + footer

   private void header(class_332 g, class_327 font, int x0, int y0, int w, int mx, int my, boolean compact) {
      int lx = x0 + 10;
      int ly = y0 + 8;
      Ui.gradientV(g, lx + 3, ly, 4, 10, Ui.VIOLET, Ui.CYAN);
      Ui.gradientV(g, lx + 1, ly + 2, 8, 6, Ui.VIOLET, Ui.CYAN);
      Ui.rect(g, lx + 4, ly + 3, 2, 4, 0xCCFFFFFF);
      int tx = x0 + 24;
      if (!compact) {
         Ui.text(g, font, "VISUAL", tx, y0 + 9, Ui.FG);
         tx += Ui.width(font, "VISUAL ");
      }
      Ui.text(g, font, "STYLES", tx, y0 + 9, Ui.SOFT);
      tx += Ui.width(font, "STYLES") + 8;
      Ui.rect(g, x0 + 1, y0 + 26, w - 2, 1, Ui.LINE);

      int closeX = x0 + w - 20;
      boolean closeHover = mx >= closeX && my >= y0 + 5 && mx < closeX + 15 && my < y0 + 20;
      if (closeHover) {
         Ui.round(g, closeX, y0 + 5, 15, 15, Ui.light ? 0xFFFFE4E8 : 0xFF2A1520);
      }
      Ui.textCenter(g, font, "×", closeX + 8, y0 + 8, closeHover ? 0xFFFF4D6D : Ui.MUTED);
      this.hits.add(new Hit(closeX, y0 + 5, 15, 15, this::method_25419, null));

      // segmented tabs
      String[] names = {"Styles", "Extras"};
      int segW = 0;
      int[] tws = new int[names.length];
      for (int i = 0; i < names.length; i++) {
         tws[i] = Ui.width(font, names[i]) + (compact ? 12 : 20);
         segW += tws[i];
      }
      int sx = closeX - 8 - segW;
      int sy = y0 + 5;
      Ui.round(g, sx - 2, sy - 2, segW + 4, 19, Ui.FIELD);
      Ui.roundOutline(g, sx - 2, sy - 2, segW + 4, 19, Ui.LINE);
      int segX = sx;
      for (int i = 0; i < names.length; i++) {
         Tab t = Tab.values()[i];
         boolean active = this.tab == t;
         boolean hover = mx >= segX && my >= sy && mx < segX + tws[i] && my < sy + 15;
         if (active) {
            Ui.round(g, segX, sy, tws[i], 15, Ui.light ? 0xFFF1EEFF : Ui.PANEL2);
            Ui.gradientH(g, segX + 4, sy + 14, tws[i] - 8, 1, Ui.VIOLET, Ui.CYAN);
         }
         Ui.textCenter(g, font, names[i], segX + tws[i] / 2, sy + 4, active ? Ui.FG : hover ? Ui.SOFT : Ui.MUTED);
         this.hits.add(new Hit(segX, sy, tws[i], 15, () -> {
            this.tab = t;
            this.searchFocus = false;
         }, null));
         segX += tws[i];
      }

      // design switch: left click next design, right click previous
      Layout l = layout();
      String ui = "◐ " + l.title;
      int uw = Ui.width(font, ui) + 12;
      int ux = sx - 8 - uw;
      if (ux > tx) {
         boolean uh = mx >= ux && my >= sy && mx < ux + uw && my < sy + 15;
         Ui.round(g, ux, sy, uw, 15, Ui.buttonFill(uh));
         Ui.roundOutline(g, ux, sy, uw, 15, uh ? Ui.VIOLET : Ui.LINE2);
         Ui.textCenter(g, font, ui, ux + uw / 2, sy + 4, uh ? Ui.FG : Ui.MUTED);
         this.hits.add(new Hit(ux, sy, uw, 15, () -> this.cycleLayout(1), () -> this.cycleLayout(-1)));
         if (uh) {
            this.tooltip = "Menu design  ·  click: next  ·  right-click: back";
            this.tooltipX = ux;
            this.tooltipY = sy + 18;
         }
      }

      if (!PENDING.isEmpty()) {
         String p = PENDING.size() + " picked";
         int pw = Ui.smallWidth(font, p) + 10;
         if (tx + pw < ux - 4) {
            Ui.round(g, tx, y0 + 8, pw, 11, Ui.alpha(Ui.VIOLET, 0.25F + 0.2F * Ui.pulse(1400L)));
            Ui.small(g, font, p, tx + 5, y0 + 11, Ui.light ? Ui.VIOLET : Ui.SOFT);
         }
      }
   }

   private void footer(class_332 g, class_327 font, int x0, int y, int w, int mx, int my, boolean twoRows) {
      Ui.rect(g, x0 + 1, y, w - 2, 1, Ui.LINE);
      int bx = x0 + 8;
      int by = y + 6;
      bx = this.button(g, font, bx, by, "Folder", mx, my, this::openFolder) + 4;
      bx = this.button(g, font, bx, by, "Import ZIP", mx, my, this::importZip) + 4;
      bx = this.button(g, font, bx, by, "Reset", mx, my, this::resetCollection) + 4;
      bx = this.button(g, font, bx, by, "Reset all", mx, my, this::resetAll) + 12;
      if (twoRows) {
         bx = x0 + 8;
         by += 18;
      }
      Ui.small(g, font, "PRESETS", bx, by + 5, Ui.FAINT);
      bx += Ui.smallWidth(font, "PRESETS") + 5;
      Config cfg = Config.get();
      for (int i = 0; i < 3; i++) {
         boolean filled = cfg.presets.get(i) != null;
         boolean hover = mx >= bx && my >= by && mx < bx + 15 && my < by + 14;
         Ui.round(g, bx, by, 15, 14, Ui.buttonFill(hover));
         if (filled) {
            Ui.accentOutline(g, bx, by, 15, 14);
         } else {
            Ui.roundOutline(g, bx, by, 15, 14, Ui.LINE2);
         }
         Ui.textCenter(g, font, String.valueOf(i + 1), bx + 8, by + 3, filled ? Ui.FG : Ui.FAINT);
         int index = i;
         this.hits.add(new Hit(bx, by, 15, 14, () -> this.preset(index), null));
         bx += 18;
      }
      boolean live = !PENDING.isEmpty();
      String applyText = live ? "Apply (" + PENDING.size() + ")" : "Apply";
      int aw = Ui.width(font, applyText) + 24;
      int ax = x0 + w - 8 - aw;
      boolean aHover = mx >= ax && my >= by && mx < ax + aw && my < by + 14;
      if (live) {
         Ui.round(g, ax - 1, by - 1, aw + 2, 16, Ui.alpha(Ui.CYAN, 0.18F + 0.25F * Ui.pulse(1400L)));
         Ui.gradientH(g, ax, by, aw, 14, aHover ? Col.lerp(Ui.VIOLET, 0xFFFFFFFF, 0.2F) : Ui.VIOLET, aHover ? Col.lerp(Ui.CYAN, 0xFFFFFFFF, 0.2F) : Ui.CYAN);
      } else {
         Ui.round(g, ax, by, aw, 14, Ui.buttonFill(false));
         Ui.roundOutline(g, ax, by, aw, 14, Ui.LINE2);
      }
      Ui.textCenter(g, font, applyText, ax + aw / 2, by + 3, live ? Ui.ON_ACCENT : Ui.FAINT);
      this.hits.add(new Hit(ax, by, aw, 14, this::applyPending, null));

      String hint = "Click: pick  ·  Right-click: zoom  ·  F: favourite  ·  Del: remove zip  ·  Drop .zip: import  ·  /: search  ·  ↑↓: category  ·  Shift+click preset: save";
      int room = ax - 10 - (bx + 6);
      if (room > 60) {
         int hw = Math.min(room, Ui.smallWidth(font, hint));
         Ui.small(g, font, Ui.fitSmall(font, hint, room), ax - 10 - hw, by + 5, Ui.FAINT);
      }
   }

   private int button(class_332 g, class_327 font, int x, int y, String text, int mx, int my, Runnable action) {
      int bw = Ui.width(font, text) + 14;
      boolean hover = mx >= x && my >= y && mx < x + bw && my < y + 14;
      Ui.round(g, x, y, bw, 14, Ui.buttonFill(hover));
      Ui.roundOutline(g, x, y, bw, 14, hover ? Ui.VIOLET : Ui.LINE2);
      Ui.textCenter(g, font, text, x + bw / 2, y + 3, hover ? Ui.FG : Ui.MUTED);
      this.hits.add(new Hit(x, y, bw, 14, action, null));
      return x + bw;
   }

   // ------------------------------------------------------------------ categories (same list in every design)

   private void categoryRail(class_332 g, class_327 font, int railX, int listY, int railW, int listH, int mx, int my) {
      Coll[] all = Coll.values();
      int rowH = Math.max(11, Math.min(20, listH / all.length));
      boolean icons = rowH >= 17;
      for (int i = 0; i < all.length; i++) {
         Coll c = all[i];
         int ry = listY + i * rowH;
         boolean sel = c == this.coll;
         boolean hover = mx >= railX && my >= ry && mx < railX + railW && my < ry + rowH;
         if (sel) {
            Ui.round(g, railX + 4, ry, railW - 8, rowH, Ui.PANEL2);
            Ui.gradientV(g, railX + 4, ry + 2, 2, rowH - 4, Ui.VIOLET, Ui.CYAN);
         } else if (hover) {
            Ui.round(g, railX + 4, ry, railW - 8, rowH, Ui.hoverFill());
         }
         int textX = railX + 12;
         if (icons) {
            g.method_51427(c.stack(), railX + 9, ry + (rowH - 16) / 2);
            textX = railX + 29;
         }
         int textW = railX + railW - textX - 16;
         Ui.text(g, font, Ui.fit(font, c.title, textW), textX, ry + (rowH - 8) / 2, sel ? Ui.FG : hover ? Ui.SOFT : Ui.MUTED);
         this.collDot(g, c, railX + railW - 13, ry + rowH / 2 - 2);
         this.hits.add(new Hit(railX, ry, railW, rowH, () -> this.selectColl(c), null));
      }
   }

   /** Small state dot: pulsing = picked but not applied, swatch = a look is worn. */
   private void collDot(class_332 g, Coll c, int dx, int dy) {
      Style picked = effective(c);
      if (PENDING.containsKey(c)) {
         Ui.rect(g, dx - 1, dy - 1, 7, 7, Ui.alpha(Ui.VIOLET, 0.35F + 0.5F * Ui.pulse(1200L)));
         Ui.rect(g, dx, dy, 5, 5, Ui.VIOLET);
      } else if (!picked.vanilla() || c.hasExtras()) {
         Ui.rect(g, dx, dy, 5, 5, 0xFF000000 | (picked.vanilla() ? Ui.SOFT : picked.swatch));
      }
   }

   /** Horizontal category pills: icons, the open category also shows its name (Glass, Showcase). */
   private void categoryPills(class_332 g, class_327 font, int x, int y, int maxW, int mx, int my, boolean tabs) {
      Coll[] all = Coll.values();
      int titleW = Ui.width(font, this.coll.title) + 8;
      int cell = Math.max(18, Math.min(26, (maxW - titleW) / all.length - 2));
      int px = x;
      for (Coll c : all) {
         boolean sel = c == this.coll;
         int pw = sel ? cell + titleW : cell;
         boolean hover = mx >= px && my >= y && mx < px + pw && my < y + 22;
         if (tabs) {
            if (sel) {
               Ui.round(g, px, y, pw, 22, Ui.PANEL2);
               Ui.gradientH(g, px + 3, y + 21, pw - 6, 1, Ui.VIOLET, Ui.CYAN);
            } else if (hover) {
               Ui.round(g, px, y, pw, 22, Ui.hoverFill());
            }
         } else {
            if (sel) {
               Ui.round2(g, px, y, pw, 22, Ui.VIOLET);
               Ui.gradientH(g, px + 2, y + 1, pw - 4, 20, Ui.VIOLET, Ui.CYAN);
            } else {
               Ui.round2(g, px, y, pw, 22, hover ? Ui.hoverFill() : Ui.PANEL2);
               Ui.roundOutline(g, px, y, pw, 22, Ui.LINE);
            }
         }
         g.method_51427(c.stack(), px + (cell - 16) / 2, y + 3);
         if (sel) {
            Ui.text(g, font, c.title, px + cell, y + 7, tabs ? Ui.FG : Ui.ON_ACCENT);
         }
         this.collDot(g, c, px + cell - 7, y + 2);
         if (hover && !sel) {
            this.tooltip = c.title;
            this.tooltipX = px;
            this.tooltipY = y + 25;
         }
         this.hits.add(new Hit(px, y, pw, 22, () -> this.selectColl(c), null));
         px += pw + 2;
      }
   }

   /** Vertical icon strip (Neon, Dock); the name shows as a tooltip. */
   private void categoryIcons(class_332 g, class_327 font, int x, int y, int h, int mx, int my) {
      Coll[] all = Coll.values();
      int cell = Math.max(16, Math.min(22, h / all.length));
      for (int i = 0; i < all.length; i++) {
         Coll c = all[i];
         int cy = y + i * cell;
         boolean sel = c == this.coll;
         boolean hover = mx >= x && my >= cy && mx < x + 22 && my < cy + cell;
         if (sel) {
            Ui.round(g, x, cy, 22, cell, Ui.PANEL2);
            Ui.gradientV(g, x - 3, cy + 1, 2, cell - 2, Ui.VIOLET, Ui.CYAN);
            if (layout() == Layout.NEON) {
               Ui.glow(g, x, cy, 22, cell, Ui.CYAN, 3);
            }
         } else if (hover) {
            Ui.round(g, x, cy, 22, cell, Ui.hoverFill());
         }
         g.method_51427(c.stack(), x + 3, cy + (cell - 16) / 2);
         this.collDot(g, c, x + 17, cy + 1);
         if (hover) {
            this.tooltip = c.title;
            this.tooltipX = x + 26;
            this.tooltipY = cy + (cell - 14) / 2;
         }
         this.hits.add(new Hit(x, cy, 22, cell, () -> this.selectColl(c), null));
      }
   }

   // ------------------------------------------------------------------ search + filter

   /** Title, All / ★ chips, search field and the worn / picked line. Returns the y where the content starts. */
   private int toolbar(class_332 g, class_327 font, int gx, int gy, int gw, int mx, int my, boolean title) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      List<Style> allStyles = Styles.all(this.coll);
      int favCount = 0;
      for (Style s : allStyles) {
         if (Styles.favorite(this.coll, s)) {
            favCount++;
         }
      }
      int y = gy;
      int chipsRight = gx + gw;
      if (title) {
         Ui.text(g, font, this.coll.title, gx, y + 1, Ui.FG);
         Ui.small(g, font, allStyles.size() + " styles", gx + Ui.width(font, this.coll.title) + 6, y + 3, Ui.FAINT);
      }
      String[] chips = {"All", "★ " + favCount};
      int cx = chipsRight;
      for (int i = 1; i >= 0; i--) {
         int cw = Ui.width(font, chips[i]) + 14;
         cx -= cw;
         boolean on = i == 0 ? !this.favOnly : this.favOnly;
         boolean hover = mx >= cx && my >= y - 1 && mx < cx + cw && my < y + 12;
         Ui.round(g, cx, y - 1, cw, 13, on ? (Ui.light ? 0xFFEDE8FF : Col.lerp(Ui.FIELD, Ui.VIOLET, 0.18F)) : hover ? Ui.hoverFill() : Ui.FIELD);
         if (on) {
            Ui.accentOutline(g, cx, y - 1, cw, 13);
         } else {
            Ui.roundOutline(g, cx, y - 1, cw, 13, Ui.LINE2);
         }
         Ui.textCenter(g, font, chips[i], cx + cw / 2, y + 2, on ? Ui.FG : i == 1 ? Ui.GOLD : Ui.MUTED);
         boolean fav = i == 1;
         this.hits.add(new Hit(cx, y - 1, cw, 13, () -> {
            this.favOnly = fav;
            this.scroll = 0.0F;
         }, null));
         cx -= 4;
      }
      int sy = title ? y + 16 : y;
      int sw = title ? gw : cx - gx - 2;
      Ui.round(g, gx, sy, sw, 15, Ui.FIELD);
      if (this.searchFocus) {
         Ui.accentOutline(g, gx, sy, sw, 15);
      } else {
         Ui.roundOutline(g, gx, sy, sw, 15, Ui.LINE2);
      }
      Ui.text(g, font, "⌕", gx + 5, sy + 4, this.searchFocus ? Ui.SOFT : Ui.FAINT);
      boolean placeholder = this.search.isEmpty() && !this.searchFocus;
      String shown = placeholder ? "Search " + this.coll.title.toLowerCase(Locale.ROOT) + " styles" : this.search;
      boolean caret = this.searchFocus && System.currentTimeMillis() / 500L % 2L == 0L;
      Ui.text(g, font, Ui.fit(font, shown + (caret ? "_" : ""), sw - 30), gx + 16, sy + 4, placeholder ? Ui.FAINT : Ui.FG);
      if (placeholder && sw > 80) {
         Ui.small(g, font, "/", gx + sw - 9, sy + 5, Ui.FAINT);
      }
      this.hits.add(new Hit(gx, sy, sw, 15, () -> this.searchFocus = true, null));
      String info = "worn: " + applied.name + (picked.id.equals(applied.id) ? "" : "   →   picked: " + picked.name);
      Ui.small(g, font, Ui.fitSmall(font, info, gw), gx + 1, sy + 20, picked.id.equals(applied.id) ? Ui.FAINT : Ui.SOFT);
      return sy + 30;
   }

   // ------------------------------------------------------------------ styles: cards and rows

   /** Grid of cards: texture on top, pack / style name under it. */
   private void grid(class_332 g, class_327 font, int gx, int cardsY, int gw, int cardsH, int mx, int my, int cw, int box, int gap, boolean neon) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      List<Style> list = this.visibleStyles();
      int ch = box + 32;
      int cols = Math.max(1, (gw + gap) / (cw + gap));
      int rows = (list.size() + cols - 1) / cols;
      int contentH = rows * (ch + gap) - gap;
      this.setScrollArea(gx, cardsY, gw, cardsH, contentH);
      int offsetX = gx + (gw - (cols * (cw + gap) - gap)) / 2;

      g.method_44379(gx - 4, cardsY - 2, gx + gw + 4, cardsY + cardsH);
      for (int i = 0; i < list.size(); i++) {
         Style s = list.get(i);
         int x = offsetX + i % cols * (cw + gap);
         int y = cardsY + i / cols * (ch + gap) - Math.round(this.scroll);
         if (y + ch < cardsY || y > cardsY + cardsH) {
            continue;
         }
         boolean worn = s.id.equals(applied.id);
         boolean pick = s.id.equals(picked.id) && !worn;
         boolean inView = my >= cardsY && my < cardsY + cardsH;
         boolean hover = inView && mx >= x && my >= y && mx < x + cw && my < y + ch;
         if (hover) {
            this.hovered = s;
            y -= 1;
         }
         if (neon && (hover || pick)) {
            Ui.glow(g, x, y, cw, ch, pick ? Ui.VIOLET : Ui.CYAN, 3);
         }
         Ui.round(g, x, y, cw, ch, hover ? Ui.CARD_HOVER : Ui.CARD);
         if (pick) {
            Ui.pulseOutline(g, x, y, cw, ch);
         } else if (worn) {
            Ui.roundOutline(g, x, y, cw, ch, Ui.CYAN);
         } else {
            Ui.roundOutline(g, x, y, cw, ch, hover ? Ui.LINE2 : Ui.LINE);
         }
         int bx = x + (cw - box) / 2;
         int by = y + 5;
         Ui.checker(g, bx, by, box, box, 5);
         class_2960 id = Previews.get(this.coll, s);
         int[] size = Previews.size(id);
         Ui.blitFit(g, id, bx + 1, by + 1, box - 2, size[0], size[1]);
         // name of the pack / style under the texture
         Ui.textCenter(g, font, Ui.fit(font, s.name, cw - 6), x + cw / 2, by + box + 5, worn || pick ? Ui.FG : Ui.MUTED);
         Ui.smallCenter(g, font, tag(s), x + cw / 2, by + box + 16, Ui.alpha(sourceColor(s), 0.85F));
         this.badges(g, font, s, x, y, cw, ch, worn, pick);
         int hy = Math.max(y, cardsY);
         int hb = Math.min(y + ch, cardsY + cardsH);
         if (hb > hy) {
            this.hits.add(new Hit(x, hy, cw, hb - hy, () -> this.wear(s), () -> this.zoom = s));
         }
      }
      g.method_44380();
      this.emptyAndScrollbar(g, font, list.isEmpty(), gx, cardsY, gw, cardsH, contentH);
   }

   /** Showcase list: one row per look (thumbnail, name, source, state). */
   private void rows(class_332 g, class_327 font, int gx, int top, int gw, int h, int mx, int my) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      List<Style> list = this.visibleStyles();
      int rh = 24;
      int contentH = list.size() * (rh + 2) - 2;
      this.setScrollArea(gx, top, gw - 4, h, contentH);
      g.method_44379(gx - 2, top - 1, gx + gw, top + h);
      for (int i = 0; i < list.size(); i++) {
         Style s = list.get(i);
         int y = top + i * (rh + 2) - Math.round(this.scroll);
         if (y + rh < top || y > top + h) {
            continue;
         }
         boolean worn = s.id.equals(applied.id);
         boolean pick = s.id.equals(picked.id) && !worn;
         boolean hover = my >= top && my < top + h && mx >= gx && my >= y && mx < gx + gw - 6 && my < y + rh;
         if (hover) {
            this.hovered = s;
            this.focus = s;
         }
         boolean focused = this.focus != null && this.focus.id.equals(s.id);
         Ui.round(g, gx, y, gw - 6, rh, hover || focused ? Ui.CARD_HOVER : Ui.CARD);
         if (pick) {
            Ui.pulseOutline(g, gx, y, gw - 6, rh);
         } else if (worn) {
            Ui.roundOutline(g, gx, y, gw - 6, rh, Ui.CYAN);
         } else if (focused) {
            Ui.roundOutline(g, gx, y, gw - 6, rh, Ui.LINE2);
         }
         Ui.checker(g, gx + 3, y + 3, 18, 18, 3);
         class_2960 id = Previews.get(this.coll, s);
         int[] size = Previews.size(id);
         Ui.blitFit(g, id, gx + 3, y + 3, 18, size[0], size[1]);
         Ui.text(g, font, Ui.fit(font, s.name, gw - 90), gx + 27, y + 4, worn || pick ? Ui.FG : Ui.MUTED);
         Ui.small(g, font, tag(s), gx + 27, y + 15, Ui.alpha(sourceColor(s), 0.85F));
         int rx = gx + gw - 12;
         if (Styles.favorite(this.coll, s)) {
            Ui.textRight(g, font, "★", rx, y + 8, Ui.GOLD);
            rx -= 11;
         }
         if (worn) {
            Ui.textRight(g, font, "✓ worn", rx, y + 8, Ui.CYAN);
         } else if (pick) {
            Ui.textRight(g, font, "● picked", rx, y + 8, Ui.SOFT);
         }
         int hy = Math.max(y, top);
         int hb = Math.min(y + rh, top + h);
         if (hb > hy) {
            this.hits.add(new Hit(gx, hy, gw - 6, hb - hy, () -> this.wear(s), () -> this.zoom = s));
         }
      }
      g.method_44380();
      this.emptyAndScrollbar(g, font, list.isEmpty(), gx, top, gw - 4, h, contentH);
   }

   private void setScrollArea(int x, int y, int w, int h, int contentH) {
      this.maxScroll = Math.max(0, contentH - h);
      this.scroll = Math.max(0.0F, Math.min(this.scroll, this.maxScroll));
      this.gridX = x;
      this.gridY = y;
      this.gridW = w;
      this.gridH = h;
   }

   private void emptyAndScrollbar(class_332 g, class_327 font, boolean empty, int gx, int top, int gw, int h, int contentH) {
      if (empty) {
         String text = this.favOnly ? "No favourites yet. Hover a style and press F." : "No style matches \"" + this.search + "\".";
         Ui.textCenter(g, font, Ui.fit(font, text, gw - 10), gx + gw / 2, top + 24, Ui.FAINT);
      }
      if (this.maxScroll > 0) {
         int barH = Math.max(14, h * h / Math.max(1, contentH));
         int barY = top + Math.round((h - barH) * (this.scroll / this.maxScroll));
         Ui.rect(g, gx + gw + 3, top, 2, h, Ui.LINE);
         Ui.gradientV(g, gx + gw + 3, barY, 2, barH, Ui.VIOLET, Ui.CYAN);
      }
   }

   private void badges(class_332 g, class_327 font, Style s, int x, int y, int cw, int ch, boolean worn, boolean pick) {
      if (worn) {
         Ui.round(g, x + 2, y + 2, 11, 10, Ui.light ? 0xFFE0F2FE : 0xE00B2533);
         Ui.text(g, font, "✓", x + 4, y + 3, Ui.CYAN);
      } else if (pick) {
         Ui.round(g, x + 2, y + 2, 11, 10, Ui.light ? 0xFFEDE8FF : 0xE01B1533);
         Ui.text(g, font, "●", x + 4, y + 3, Ui.light ? Ui.VIOLET : Ui.SOFT);
      }
      if (Styles.favorite(this.coll, s)) {
         Ui.text(g, font, "★", x + cw - 10, y + 3, Ui.GOLD);
      }
      if (s.custom()) {
         Ui.rect(g, x + 4, y + ch - 3, cw - 8, 1, Ui.TAG);
      }
   }

   // ------------------------------------------------------------------ inspectors

   private void inspector(class_332 g, class_327 font, int x, int y, int w, int h, int mx, int my) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      Style s = this.hovered != null ? this.hovered : picked;
      Ui.small(g, font, "INSPECTOR", x + 2, y + 1, Ui.FAINT);
      int box = Math.min(w - 20, Math.max(48, h - 150));
      box = Math.min(box, 104);
      int bx = x + (w - box) / 2;
      int by = y + 12;
      Ui.round(g, bx - 4, by - 4, box + 8, box + 8, Ui.CARD);
      Ui.checker(g, bx, by, box, box, 6);
      class_2960 id = Previews.get(this.coll, s);
      int[] size = Previews.size(id);
      Ui.blitFit(g, id, bx, by, box, size[0], size[1]);
      int ty = by + box + 9;
      Ui.textCenter(g, font, Ui.fit(font, s.name, w - 6), x + w / 2, ty, Ui.FG);
      ty += 11;
      Ui.smallCenter(g, font, source(s), x + w / 2, ty, sourceColor(s));
      ty += 10;
      boolean worn = s.id.equals(applied.id);
      boolean pick = s.id.equals(picked.id) && !worn;
      String status = worn ? "● Worn" : pick ? "● Picked - press Apply" : "Click to pick";
      Ui.smallCenter(g, font, status, x + w / 2, ty, worn ? Ui.CYAN : pick ? Ui.SOFT : Ui.MUTED);
      ty += 12;
      ty = this.favButton(g, font, s, x + w / 2, ty, Math.min(w - 10, 96), mx, my) + 6;
      if (this.coll == Coll.CRYSTAL || this.coll == Coll.ANCHOR || this.coll == Coll.FIRE) {
         Ui.rect(g, x + 4, ty, w - 8, 1, Ui.LINE);
         ty += 6;
         this.renderExtras(g, font, x + 4, ty, w - 8, y + h - ty, mx, my, true);
      }
   }

   /** Showcase: the focused look big, with pick / favourite / zoom buttons. */
   private void showcasePanel(class_332 g, class_327 font, int x, int y, int w, int h, int mx, int my) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      if (this.focus == null || !this.containsStyle(this.focus)) {
         this.focus = picked;
      }
      Style s = this.focus;
      int box = Math.max(40, Math.min(w - 36, h - 92));
      int bx = x + (w - box) / 2;
      int by = y + 12;
      Ui.round2(g, bx - 6, by - 6, box + 12, box + 12, Ui.CARD);
      Ui.checker(g, bx, by, box, box, 8);
      class_2960 id = Previews.get(this.coll, s);
      int[] size = Previews.size(id);
      Ui.blitFit(g, id, bx, by, box, size[0], size[1]);
      Ui.gradientH(g, bx - 6, by + box + 6, box + 12, 1, Ui.VIOLET, Ui.CYAN);
      int ty = by + box + 12;
      Ui.scaled(g, font, Ui.fit(font, s.name, Math.round((w - 12) / 1.25F)), x + w / 2 - Math.round(Math.min(Ui.width(font, s.name), (w - 12) / 1.25F) * 1.25F / 2.0F), ty, Ui.FG, 1.25F);
      ty += 14;
      boolean worn = s.id.equals(applied.id);
      boolean pick = s.id.equals(picked.id) && !worn;
      Ui.smallCenter(g, font, source(s) + "  ·  " + (worn ? "worn" : pick ? "picked - press Apply" : "not worn"), x + w / 2, ty, worn ? Ui.CYAN : pick ? Ui.SOFT : Ui.MUTED);
      ty += 12;
      // buttons: pick, favourite, zoom
      String pickText = worn ? "✓ Worn" : pick ? "● Picked" : "Pick";
      int pw = Ui.width(font, pickText) + 18;
      boolean fav = Styles.favorite(this.coll, s);
      String favText = fav ? "★" : "☆";
      int fw = 22;
      int zw = Ui.width(font, "Zoom") + 14;
      int total = pw + fw + zw + 8;
      int px = x + (w - total) / 2;
      if (ty + 15 <= y + h) {
         boolean ph = mx >= px && my >= ty && mx < px + pw && my < ty + 15;
         if (worn) {
            Ui.round(g, px, ty, pw, 15, Ui.buttonFill(ph));
            Ui.roundOutline(g, px, ty, pw, 15, Ui.CYAN);
         } else {
            Ui.gradientH(g, px, ty, pw, 15, ph ? Col.lerp(Ui.VIOLET, 0xFFFFFFFF, 0.2F) : Ui.VIOLET, ph ? Col.lerp(Ui.CYAN, 0xFFFFFFFF, 0.2F) : Ui.CYAN);
         }
         Ui.textCenter(g, font, pickText, px + pw / 2, ty + 4, worn ? Ui.CYAN : Ui.ON_ACCENT);
         this.hits.add(new Hit(px, ty, pw, 15, () -> this.wear(s), null));
         int fx = px + pw + 4;
         boolean fh = mx >= fx && my >= ty && mx < fx + fw && my < ty + 15;
         Ui.round(g, fx, ty, fw, 15, Ui.buttonFill(fh));
         Ui.roundOutline(g, fx, ty, fw, 15, fav ? Ui.GOLD : Ui.LINE2);
         Ui.textCenter(g, font, favText, fx + fw / 2, ty + 4, fav ? Ui.GOLD : Ui.MUTED);
         this.hits.add(new Hit(fx, ty, fw, 15, () -> Styles.toggleFavorite(this.coll, s), null));
         int zx = fx + fw + 4;
         boolean zh = mx >= zx && my >= ty && mx < zx + zw && my < ty + 15;
         Ui.round(g, zx, ty, zw, 15, Ui.buttonFill(zh));
         Ui.roundOutline(g, zx, ty, zw, 15, zh ? Ui.VIOLET : Ui.LINE2);
         Ui.textCenter(g, font, "Zoom", zx + zw / 2, ty + 4, zh ? Ui.FG : Ui.MUTED);
         this.hits.add(new Hit(zx, ty, zw, 15, () -> this.zoom = s, null));
      }
   }

   private boolean containsStyle(Style s) {
      for (Style t : Styles.all(this.coll)) {
         if (t.id.equals(s.id)) {
            return true;
         }
      }
      return false;
   }

   /** Dock: one line with the hovered / picked look above the grid. Returns the new content top. */
   private int dockInspector(class_332 g, class_327 font, int x, int y, int w, int mx, int my) {
      Style applied = Styles.selected(this.coll);
      Style picked = effective(this.coll);
      Style s = this.hovered != null ? this.hovered : picked;
      Ui.round(g, x, y, w, 34, Ui.PANEL2);
      Ui.roundOutline(g, x, y, w, 34, Ui.LINE);
      Ui.checker(g, x + 3, y + 3, 28, 28, 4);
      class_2960 id = Previews.get(this.coll, s);
      int[] size = Previews.size(id);
      Ui.blitFit(g, id, x + 3, y + 3, 28, size[0], size[1]);
      Ui.text(g, font, Ui.fit(font, s.name, w - 64), x + 36, y + 6, Ui.FG);
      boolean worn = s.id.equals(applied.id);
      boolean pick = s.id.equals(picked.id) && !worn;
      Ui.small(g, font, source(s) + "  ·  " + (worn ? "worn" : pick ? "picked" : "click to pick"), x + 36, y + 19, worn ? Ui.CYAN : pick ? Ui.SOFT : Ui.FAINT);
      boolean fav = Styles.favorite(this.coll, s);
      int fx = x + w - 20;
      boolean fh = mx >= fx && my >= y + 9 && mx < fx + 16 && my < y + 25;
      Ui.round(g, fx, y + 9, 16, 16, Ui.buttonFill(fh));
      Ui.textCenter(g, font, fav ? "★" : "☆", fx + 8, y + 13, fav ? Ui.GOLD : Ui.MUTED);
      Style target = s;
      this.hits.add(new Hit(fx, y + 9, 16, 16, () -> Styles.toggleFavorite(this.coll, target), null));
      return y + 40;
   }

   private int favButton(class_332 g, class_327 font, Style s, int centerX, int ty, int fw, int mx, int my) {
      boolean fav = Styles.favorite(this.coll, s);
      int fx = centerX - fw / 2;
      boolean fHover = mx >= fx && my >= ty && mx < fx + fw && my < ty + 14;
      Ui.round(g, fx, ty, fw, 14, Ui.buttonFill(fHover));
      Ui.roundOutline(g, fx, ty, fw, 14, fav ? Ui.GOLD : Ui.LINE2);
      Ui.textCenter(g, font, (fav ? "★" : "☆") + " Favourite", centerX, ty + 3, fav ? Ui.GOLD : Ui.MUTED);
      Style target = s;
      this.hits.add(new Hit(fx, ty, fw, 14, () -> Styles.toggleFavorite(this.coll, target), null));
      return ty + 14;
   }

   // ------------------------------------------------------------------ extras

   /** Draws the extras rows; compact = only the rows of the current collection (inspector). */
   private void renderExtras(class_332 g, class_327 font, int x, int y, int w, int h, int mx, int my, boolean compact) {
      Config cfg = Config.get();
      int ry = y;
      if (!compact) {
         Ui.text(g, font, "Extras", x, ry, Ui.FG);
         Ui.small(g, font, Ui.fitSmall(font, "Purely visual. Nothing changes for the server or other players.", w - Ui.width(font, "Extras") - 10), x + Ui.width(font, "Extras") + 8, ry + 2, Ui.FAINT);
         ry += 18;
         // menu design picker
         int rowH = 34;
         if (ry + rowH <= y + h) {
            this.rowBack(g, x, ry, w, rowH, false);
            Ui.text(g, font, "Menu design", x + 8, ry + 6, Ui.FG);
            Ui.small(g, font, Ui.fitSmall(font, "Same functions, different look", w / 3), x + 8, ry + 17, Ui.FAINT);
            int bx = x + w - 8;
            Layout[] all = Layout.values();
            for (int i = all.length - 1; i >= 0; i--) {
               Layout l = all[i];
               int bw = Ui.width(font, l.title) + 12;
               bx -= bw;
               boolean on = layout() == l;
               boolean hover = mx >= bx && my >= ry + 8 && mx < bx + bw && my < ry + 22;
               if (on) {
                  Ui.gradientH(g, bx, ry + 8, bw, 14, Ui.VIOLET, Ui.CYAN);
               } else {
                  Ui.round(g, bx, ry + 8, bw, 14, Ui.buttonFill(hover));
                  Ui.roundOutline(g, bx, ry + 8, bw, 14, hover ? Ui.VIOLET : Ui.LINE2);
               }
               Ui.textCenter(g, font, l.title, bx + bw / 2, ry + 11, on ? Ui.ON_ACCENT : hover ? Ui.FG : Ui.MUTED);
               this.hits.add(new Hit(bx, ry + 8, bw, 14, () -> this.setLayout(l), null));
               bx -= 4;
            }
            ry += rowH;
         }
      }
      int rowH = compact ? 27 : 34;
      boolean crystal = !compact || this.coll == Coll.CRYSTAL;
      boolean anchor = !compact || this.coll == Coll.ANCHOR;
      boolean fire = !compact || this.coll == Coll.FIRE;

      if (crystal && ry + rowH <= y + h) {
         this.rowBack(g, x, ry, w, rowH, compact);
         this.sliderRow(g, font, x, ry, w, compact, "Crystal spin", "How fast end crystals turn and bob",
            SLIDER_SPIN, cfg.crystalSpin / 3.0F, Math.round(cfg.crystalSpin * 100.0F) + "%", mx, my);
         ry += rowH;
      }
      if (crystal && ry + rowH <= y + h) {
         this.rowBack(g, x, ry, w, rowH, compact);
         this.colourRow(g, font, x, ry, w, compact, "Core colour", "Colours the inner cube of every crystal",
            HEX_CORE, cfg.crystalCoreOn, cfg.crystalCore, mx, my);
         ry += rowH;
      }
      if (anchor && ry + rowH <= y + h) {
         this.rowBack(g, x, ry, w, rowH, compact);
         this.colourRow(g, font, x, ry, w, compact, "Glow colour", "Colour of the respawn anchor's charge lights",
            HEX_GLOW, cfg.anchorGlowOn, cfg.anchorGlow, mx, my);
         ry += rowH;
      }
      if (fire && ry + rowH <= y + h) {
         this.rowBack(g, x, ry, w, rowH, compact);
         this.sliderRow(g, font, x, ry, w, compact, "Low fire", "Moves the burning overlay down while you are on fire",
            SLIDER_FIRE, cfg.lowFire, cfg.lowFire <= 0.0F ? "Off" : Math.round(cfg.lowFire * 100.0F) + "%", mx, my);
         ry += rowH;
      }
      if (!compact && ry + rowH <= y + h) {
         this.rowBack(g, x, ry, w, rowH, false);
         this.toggleRow(g, font, x, ry, w, "Light inventory titles", "Readable titles while a dark inventory style is worn",
            cfg.darkLabels, () -> {
               cfg.darkLabels = !cfg.darkLabels;
               cfg.save();
            }, mx, my);
         ry += rowH;
      }
      if (!compact && ry + 14 <= y + h) {
         ry += 4;
         Ui.small(g, font, Ui.fitSmall(font, "Styles key: K (change it in Options > Controls)  ·  Own textures: Folder button below", w), x, ry, Ui.FAINT);
      }
   }

   private void rowBack(class_332 g, int x, int y, int w, int rowH, boolean compact) {
      if (!compact) {
         Ui.round(g, x, y, w, rowH - 4, Ui.light ? 0xFFFFFFFF : Col.lerp(Ui.PANEL2, 0xFF000000, 0.25F));
         Ui.roundOutline(g, x, y, w, rowH - 4, Ui.LINE);
      }
   }

   private void label(class_332 g, class_327 font, int x, int y, int w, boolean compact, String title, String desc) {
      if (compact) {
         Ui.text(g, font, title, x, y, Ui.FG);
      } else {
         Ui.text(g, font, title, x + 8, y + 6, Ui.FG);
         Ui.small(g, font, Ui.fitSmall(font, desc, w - 8), x + 8, y + 17, Ui.FAINT);
      }
   }

   private void sliderRow(class_332 g, class_327 font, int x, int y, int w, boolean compact, String title, String desc,
                          int which, float value, String valueText, int mx, int my) {
      int trackW = compact ? w - 34 : Math.min(170, w / 3);
      int trackX = compact ? x : x + w - trackW - 44;
      int trackY = compact ? y + 14 : y + 12;
      this.label(g, font, x, y, compact ? w : trackX - x - 12, compact, title, desc);
      Ui.rect(g, trackX, trackY, trackW, 4, Ui.FIELD);
      Ui.outline(g, trackX - 1, trackY - 1, trackW + 2, 6, Ui.LINE2);
      int fill = Math.round(trackW * Math.max(0.0F, Math.min(1.0F, value)));
      Ui.gradientH(g, trackX, trackY, fill, 4, Ui.VIOLET, Ui.CYAN);
      int knobX = trackX + fill - 3;
      boolean hover = mx >= trackX - 3 && my >= trackY - 5 && mx < trackX + trackW + 3 && my < trackY + 9;
      Ui.round(g, knobX, trackY - 3, 6, 10, hover || this.dragSlider == which ? Ui.CYAN : Ui.FG);
      Ui.text(g, font, valueText, trackX + trackW + 7, trackY - 2, Ui.MUTED);
      this.sliderRects.put(which, new int[]{trackX, trackW});
      this.hits.add(new Hit(trackX - 3, trackY - 5, trackW + 6, 14, () -> this.dragSlider = which, null));
   }

   private void colourRow(class_332 g, class_327 font, int x, int y, int w, boolean compact, String title, String desc,
                          int which, boolean on, String hex, int mx, int my) {
      int boxW = 60;
      int toggleX = compact ? x : x + w - boxW - 40;
      int ctrlY = compact ? y + 11 : y + 9;
      this.label(g, font, x, y, compact ? w : toggleX - x - 12, compact, title, desc);
      this.toggle(g, toggleX, ctrlY + 1, on);
      Config cfg = Config.get();
      this.hits.add(new Hit(toggleX, ctrlY, 24, 12, () -> {
         if (which == HEX_CORE) {
            cfg.crystalCoreOn = !cfg.crystalCoreOn;
         } else {
            cfg.anchorGlowOn = !cfg.anchorGlowOn;
         }
         this.applyTextureExtras();
      }, null));
      int bx = toggleX + 30;
      boolean editing = this.editHex == which;
      int colour = Col.parseHex(editing ? this.hexText : hex, 0xFF808080);
      Ui.round(g, bx, ctrlY, boxW, 13, Ui.FIELD);
      if (editing) {
         Ui.accentOutline(g, bx, ctrlY, boxW, 13);
      } else {
         Ui.roundOutline(g, bx, ctrlY, boxW, 13, Ui.LINE2);
      }
      Ui.rect(g, bx + 3, ctrlY + 3, 7, 7, colour);
      boolean caret = editing && System.currentTimeMillis() / 500L % 2L == 0L;
      Ui.text(g, font, "#" + (editing ? this.hexText + (caret ? "_" : "") : hex), bx + 13, ctrlY + 3, editing ? Ui.FG : Ui.MUTED);
      this.hits.add(new Hit(bx, ctrlY, boxW, 13, () -> this.startHex(which), null));
   }

   private void toggleRow(class_332 g, class_327 font, int x, int y, int w, String title, String desc, boolean on, Runnable action, int mx, int my) {
      int toggleX = x + w - 32;
      this.label(g, font, x, y, toggleX - x - 12, false, title, desc);
      this.toggle(g, toggleX, y + 10, on);
      this.hits.add(new Hit(toggleX, y + 9, 24, 12, action, null));
   }

   private void toggle(class_332 g, int x, int y, boolean on) {
      if (on) {
         Ui.gradientH(g, x, y, 22, 10, Ui.VIOLET, Ui.CYAN);
      } else {
         Ui.rect(g, x, y, 22, 10, Ui.LINE2);
      }
      Ui.rect(g, on ? x + 13 : x + 1, y + 1, 8, 8, on ? 0xFFFFFFFF : Ui.light ? 0xFFFFFFFF : Ui.FG);
   }

   // ------------------------------------------------------------------ zoom

   private void renderZoom(class_332 g, class_327 font, int sw, int sh) {
      Ui.rect(g, 0, 0, sw, sh, Ui.light ? 0xD0E9ECF3 : 0xD8050609);
      int box = Math.max(32, Math.min(Math.min(sh - 80, sw - 40), 232));
      int bx = (sw - box) / 2;
      int by = (sh - box) / 2 - 12;
      Ui.round(g, bx - 6, by - 6, box + 12, box + 12, Ui.CARD);
      Ui.checker(g, bx, by, box, box, 8);
      Ui.accentOutline(g, bx - 6, by - 6, box + 12, box + 12);
      class_2960 id = Previews.get(this.coll, this.zoom);
      int[] size = Previews.size(id);
      Ui.blitFit(g, id, bx, by, box, size[0], size[1]);
      Ui.textCenter(g, font, this.zoom.name, sw / 2, by + box + 12, Ui.FG);
      Ui.smallCenter(g, font, this.coll.title + "  ·  " + source(this.zoom), sw / 2, by + box + 24, sourceColor(this.zoom));
      Ui.smallCenter(g, font, "Click or Esc to close", sw / 2, by + box + 35, Ui.FAINT);
   }

   // ================================================================== input (1.21.9+ events)

   @Override
   public boolean method_25402(class_11909 e, boolean doubleClick) {
      double mx = e.comp_4798();
      double my = e.comp_4799();
      int button = e.method_74245();
      if (this.zoom != null) {
         this.zoom = null;
         return true;
      }
      this.shiftClick = e.method_74239();
      boolean wasSearch = this.searchFocus;
      if (this.editHex >= 0) {
         this.commitHex();
      }
      this.searchFocus = false;
      for (int i = this.hits.size() - 1; i >= 0; i--) {
         Hit hit = this.hits.get(i);
         if (!hit.in(mx, my)) {
            continue;
         }
         if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hit.left() != null) {
            hit.left().run();
            if (this.dragSlider >= 0) {
               this.setSlider(this.dragSlider, mx);
            }
            return true;
         }
         if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && hit.right() != null) {
            hit.right().run();
            return true;
         }
         return true;
      }
      return super.method_25402(e, doubleClick) || wasSearch;
   }

   @Override
   public boolean method_25403(class_11909 e, double dx, double dy) {
      if (this.dragSlider >= 0) {
         this.setSlider(this.dragSlider, e.comp_4798());
         return true;
      }
      return super.method_25403(e, dx, dy);
   }

   @Override
   public boolean method_25406(class_11909 e) {
      if (this.dragSlider >= 0) {
         this.dragSlider = -1;
         Config.get().save();
         return true;
      }
      return super.method_25406(e);
   }

   @Override
   public boolean method_25401(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.tab == Tab.STYLES && mouseX >= this.gridX && mouseX < this.gridX + this.gridW + 6 && mouseY >= this.gridY && mouseY < this.gridY + this.gridH) {
         this.scroll = (float)Math.max(0.0, Math.min(this.maxScroll, this.scroll - scrollY * 26.0));
         return true;
      }
      return super.method_25401(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public boolean method_25404(class_11908 e) {
      int key = e.comp_4795();
      if (this.zoom != null) {
         this.zoom = null;
         return true;
      }
      if (this.editHex >= 0) {
         if (key == GLFW.GLFW_KEY_ESCAPE) {
            this.editHex = -1;
         } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            this.commitHex();
         } else if (key == GLFW.GLFW_KEY_BACKSPACE && !this.hexText.isEmpty()) {
            this.hexText = this.hexText.substring(0, this.hexText.length() - 1);
         }
         return true;
      }
      if (this.searchFocus) {
         if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!this.search.isEmpty()) {
               this.search = "";
            } else {
               this.searchFocus = false;
            }
         } else if (key == GLFW.GLFW_KEY_BACKSPACE && !this.search.isEmpty()) {
            this.search = this.search.substring(0, this.search.length() - 1);
            this.scroll = 0.0F;
         } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            this.searchFocus = false;
         }
         return true;
      }
      if (this.tab == Tab.STYLES) {
         if (key == GLFW.GLFW_KEY_SLASH || key == GLFW.GLFW_KEY_F && e.method_74240()) {
            this.searchFocus = true;
            this.swallowChar = true;
            return true;
         }
         if (key == GLFW.GLFW_KEY_DELETE && this.hovered != null && this.hovered.imported()) {
            this.deleteImported(this.hovered);
            return true;
         }
         if (key == GLFW.GLFW_KEY_F && this.hovered != null) {
            Styles.toggleFavorite(this.coll, this.hovered);
            return true;
         }
         if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            Coll[] all = Coll.values();
            int i = (this.coll.ordinal() + (key == GLFW.GLFW_KEY_UP ? all.length - 1 : 1)) % all.length;
            this.selectColl(all[i]);
            return true;
         }
      }
      if (key == GLFW.GLFW_KEY_TAB) {
         this.tab = this.tab == Tab.STYLES ? Tab.EXTRAS : Tab.STYLES;
         return true;
      }
      return super.method_25404(e);
   }

   @Override
   public boolean method_25400(class_11905 e) {
      String typed = e.method_74226();
      if (this.swallowChar) {
         this.swallowChar = false;
         return true;
      }
      if (typed == null || typed.isEmpty()) {
         return false;
      }
      if (this.editHex >= 0) {
         for (char c : typed.toCharArray()) {
            if (Character.digit(c, 16) >= 0 && this.hexText.length() < 6) {
               this.hexText += Character.toUpperCase(c);
            }
         }
         return true;
      }
      if (this.searchFocus) {
         for (char c : typed.toCharArray()) {
            if (c >= 32 && c != 127 && this.search.length() < 32) {
               this.search += c;
            }
         }
         this.scroll = 0.0F;
         return true;
      }
      return false;
   }

   private static final String README = "Visual Styles - your own textures\n"
      + "==================================\n\n"
      + "Every collection has its own folder here (crystal, anchor, glowstone, obsidian, armor, sword, pickaxe, axe,\n"
      + "totem, mace_wind_pearl, fire, stone, deepslate, snow, inventory, glint, crosshair). Whatever you put there shows up in the\n"
      + "Styles menu (key K) as a style of that collection.\n\n"
      + "1) One PNG file = one style. It replaces the main texture of the collection:\n"
      + "   crystal: end_crystal.png (64x32)       sword: netherite + diamond sword\n"
      + "   anchor: all five side textures          pickaxe: netherite + diamond pickaxe\n"
      + "   armor: worn netherite + diamond armor   totem, glowstone, stone, deepslate, snow, crosshair: that texture\n"
      + "   fire: fire_0 + fire_1 (animated strip)  inventory: inventory.png   glint: item + armor glint\n"
      + "   axe: netherite + diamond axe             mace_wind_pearl: mace.png, wind_charge.png or ender_pearl.png\n"
      + "                                            (named like that it changes only that item, else all three)\n\n"
      + "2) A folder = one style with several textures. Name the files like Minecraft does, e.g.\n"
      + "   netherite_sword.png, diamond_sword.png, respawn_anchor_top.png, netherite_helmet.png.\n"
      + "   Worn leggings go into a subfolder: humanoid_leggings/netherite.png\n\n"
      + "Animated textures can bring their own name.png.mcmeta next to the PNG.\n\n"
      + "3) Whole texture packs: press \"Import ZIP\" in the menu, drag .zip files onto the menu, or put them into\n"
      + "   config/visualstyles/zips/. Every pack then shows up in each category it has textures for (with its\n"
      + "   own picture) and under Whole packs. Hover an imported pack and press Delete to remove it.\n"
      + "The menu picks up new files after about two seconds.\n";
}
