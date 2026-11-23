package net.visualclient.gui;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_3298;
import net.minecraft.class_3300;
import net.minecraft.class_332;
import net.minecraft.class_437;
import net.minecraft.class_7922;
import net.minecraft.class_7923;
import net.visualclient.compat.Compat;
import net.visualclient.compat.CompatScreen;
import net.visualclient.font.Fonts;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;

/**
 * Pixel editor for item / block textures. Saves into the "Visual Client Texture Edits" resource pack.
 *
 * 1.20.5 fixes: right click now picks colours (the grid used to register a drag-only hit last, which swallowed
 * right clicks), dragging in the colour picker changes the colour instead of painting on the canvas, strokes
 * are drawn as lines (no gaps when moving fast), block items load their textures/block/... texture, animated
 * textures keep their .mcmeta and are edited frame by frame, the pack.mcmeta matches the running Minecraft
 * version and the pack stays enabled after a restart. New: fill tool, recent colours, alpha slider and undo.
 */
public final class TexturePainterScreen extends CompatScreen {
   private static final int ROW = 20;
   private static final int MAX_UNDO = 64;
   private static final int TOOL_PENCIL = 0;
   private static final int TOOL_ERASER = 1;
   private static final int TOOL_FILL = 2;
   private static final int CHECKER_A = -14012614;
   private static final int CHECKER_B = -13354425;
   private static final List<Integer> RECENT = new ArrayList<>();
   private static List<TexturePainterScreen.ItemEntry> ALL_ITEMS;
   private final class_437 parent;
   private final Hits hits = new Hits();
   private final TextInput hexInput = new TextInput();
   private final TextInput searchInput = new TextInput();
   private final float[] hsva = new float[3];
   private final ArrayDeque<int[]> undo = new ArrayDeque<>();
   private List<TexturePainterScreen.ItemEntry> filtered = List.of();
   private TexturePainterScreen.ItemEntry selected;
   private PixelCanvas canvas;
   private PixelCanvas original;
   private boolean textureFound;
   private String texNamespace = "minecraft";
   private String texPath;
   private byte[] texMeta;
   private String loadMessage = "";
   private String saveMessage = "";
   private int currentColor = -1;
   private int tool = TOOL_PENCIL;
   private boolean editingHex;
   private boolean editingSearch;
   private boolean pickerOpen;
   private Hits.Action dragAction;
   private boolean stroke;
   private int lastPx = Integer.MIN_VALUE;
   private int lastPy;
   private int frame;
   private int frames = 1;
   private int frameH = 16;
   private int listScroll;
   private int listTop;
   private int listBottom;
   private int listX;
   private int listW;
   private int gridX;
   private int gridY;
   private int gridW;
   private int gridH;
   private int cellSize = 8;

   public TexturePainterScreen(class_437 var1) {
      super(class_2561.method_43470("Texture Painter"));
      this.parent = var1;
   }

   private static List<TexturePainterScreen.ItemEntry> allItems() {
      if (ALL_ITEMS == null) {
         ArrayList<TexturePainterScreen.ItemEntry> list = new ArrayList<>();
         class_7922<class_1792> reg = class_7923.field_41178;

         for (class_1792 item : reg) {
            if (item != class_1802.field_8162) {
               class_2960 id = reg.method_10221(item);
               if (id != null) {
                  list.add(new TexturePainterScreen.ItemEntry(item, id.method_12836() + ":" + id.method_12832(), id, new class_1799(item), false));
                  vc$addExtra(list, item, id);
               }
            }
         }

         list.sort((a, b) -> a.id().compareTo(b.id()));
         ALL_ITEMS = list;
      }

      return ALL_ITEMS;
   }

   private static void vc$addExtra(List<TexturePainterScreen.ItemEntry> list, class_1792 item, class_2960 id) {
      String[] extra = TexturePainterExtras.extraEntry(id.method_12836(), id.method_12832());
      if (extra != null) {
         list.add(new TexturePainterScreen.ItemEntry(item, extra[0], class_2960.method_60655(id.method_12836(), extra[1]), new class_1799(item), true));
      }
   }

   private void refilter() {
      String q = this.searchInput.text().trim().toLowerCase(Locale.ROOT);
      if (q.isEmpty()) {
         this.filtered = allItems();
      } else {
         ArrayList<TexturePainterScreen.ItemEntry> list = new ArrayList<>();

         for (TexturePainterScreen.ItemEntry e : allItems()) {
            if (e.id().contains(q)) {
               list.add(e);
            }
         }

         this.filtered = list;
      }

      this.listScroll = 0;
   }

   private void select(TexturePainterScreen.ItemEntry e) {
      this.selected = e;
      this.saveMessage = "";
      this.undo.clear();
      this.frame = 0;
      this.texMeta = null;
      this.dragAction = null;
      this.stroke = false;
      class_3300 rm = this.field_22787.method_1478();
      List<class_2960> candidates = e.explicit() ? List.of(e.key()) : TexturePainterExtras.textureCandidates(rm, e.key());
      BufferedImage img = null;
      class_2960 found = null;

      for (class_2960 c : candidates) {
         img = readImage(rm, c);
         if (img != null) {
            found = c;
            break;
         }
      }

      if (img == null) {
         class_2960 target = e.explicit() ? e.key() : class_2960.method_60655(e.key().method_12836(), "textures/item/" + e.key().method_12832() + ".png");
         this.canvas = PixelCanvas.blank(16, 16);
         this.textureFound = false;
         this.texNamespace = target.method_12836();
         this.texPath = target.method_12832();
         this.loadMessage = "No texture found - starting blank (saved as " + this.texPath + ").";
      } else {
         this.canvas = PixelCanvas.fromBufferedImage(img);
         this.textureFound = true;
         this.texNamespace = found.method_12836();
         this.texPath = found.method_12832();
         if (this.canvas.height > this.canvas.width) {
            this.texMeta = TexturePainterExtras.readBytes(rm, class_2960.method_60655(found.method_12836(), found.method_12832() + ".mcmeta"));
         }

         this.loadMessage = this.canvas.width + "x" + this.canvas.height;
      }

      this.frames = this.canvas.height > this.canvas.width && this.canvas.height % this.canvas.width == 0 ? this.canvas.height / this.canvas.width : 1;
      this.frameH = this.frames > 1 ? this.canvas.width : this.canvas.height;
      this.original = this.canvas.copy();
   }

   private static BufferedImage readImage(class_3300 rm, class_2960 id) {
      try {
         Optional<class_3298> r = rm.method_14486(id);
         if (r.isPresent()) {
            try (InputStream in = r.get().method_14482()) {
               return ImageIO.read(in);
            }
         }
      } catch (Exception var7) {
      }

      return null;
   }

   private boolean dirty() {
      return this.canvas != null && this.original != null && !Arrays.equals(this.canvas.pixels, this.original.pixels);
   }

   private void pushUndo() {
      if (this.canvas != null) {
         this.undo.push((int[])this.canvas.pixels.clone());

         while (this.undo.size() > MAX_UNDO) {
            this.undo.removeLast();
         }
      }
   }

   private void undo() {
      if (this.canvas != null && !this.undo.isEmpty()) {
         int[] px = this.undo.pop();
         if (px.length == this.canvas.pixels.length) {
            System.arraycopy(px, 0, this.canvas.pixels, 0, px.length);
         }

         this.saveMessage = "Undone (" + this.undo.size() + " more step" + (this.undo.size() == 1 ? "" : "s") + ").";
      } else {
         this.saveMessage = "Nothing to undo.";
      }
   }

   private void resetToOriginal() {
      if (this.original != null && this.canvas != null) {
         this.pushUndo();
         System.arraycopy(this.original.pixels, 0, this.canvas.pixels, 0, Math.min(this.original.pixels.length, this.canvas.pixels.length));
         this.saveMessage = "Reverted to the loaded texture (Ctrl+Z brings your changes back).";
      }
   }

   private void setFrame(int f) {
      if (this.frames > 1) {
         this.frame = Math.floorMod(f, this.frames);
      }
   }

   private void save() {
      if (this.selected != null && this.canvas != null && this.texPath != null) {
         try {
            Path pack = TexturePainterExtras.packDir();
            TexturePainterExtras.ensurePackMeta(pack);
            Path file = pack.resolve("assets").resolve(this.texNamespace).resolve(this.texPath);
            Files.createDirectories(file.getParent());
            ImageIO.write(this.canvas.toBufferedImage(), "png", file.toFile());
            if (this.texMeta != null && this.frames > 1) {
               Files.write(file.resolveSibling(file.getFileName() + ".mcmeta"), this.texMeta);
            }

            this.original = this.canvas.copy();
            this.textureFound = true;
            this.saveMessage = TexturePainterExtras.enableAndReload(this.field_22787);
         } catch (Exception var3) {
            this.saveMessage = "Could not save: " + var3.getMessage();
         }
      }
   }

   private void removeEdit() {
      if (this.selected != null && this.texPath != null) {
         try {
            Path file = TexturePainterExtras.packDir().resolve("assets").resolve(this.texNamespace).resolve(this.texPath);
            boolean deleted = Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".mcmeta"));
            if (!deleted) {
               this.saveMessage = "This texture has no saved edit.";
            } else {
               this.saveMessage = "Edit removed - reloading textures...";
               class_310 mc = this.field_22787;
               TexturePainterScreen.ItemEntry entry = this.selected;
               mc.method_1521().thenRunAsync(() -> {
                  if (Compat.screen(mc) == this && this.selected == entry) {
                     this.select(entry);
                     this.saveMessage = "Edit removed - this is the original texture again.";
                  }
               }, mc);
            }
         } catch (Exception var5) {
            this.saveMessage = "Could not remove the edit: " + var5.getMessage();
         }
      }
   }

   @Override
   protected void method_25426() {
      if (this.filtered.isEmpty() && this.searchInput.text().isEmpty()) {
         this.refilter();
      }

      if (this.selected == null && !this.filtered.isEmpty()) {
         this.select(this.filtered.get(0));
      }
   }

   @Override
   public boolean method_25421() {
      return false;
   }

   @Override
   public void method_25419() {
      Compat.setScreen(this.field_22787, this.parent);
   }

   @Override
   public void method_25394(class_332 var1, int var2, int var3, float var4) {
      this.hits.clear();
      class_327 font = this.field_22793;
      int w = this.field_22789;
      int h = this.field_22790;
      Render2D.text(var1, font, "Texture Painter", 10, 8, Theme.text());
      this.listX = 10;
      this.listW = 150;
      this.listTop = 24;
      this.listBottom = h - 30;
      this.drawSearchBox(var1, font);
      this.drawItemList(var1, font, var2, var3);
      int x = this.listX + this.listW + 14;
      int areaW = Math.max(60, w - x - 10);
      this.drawToolbar(var1, font, x, 24, areaW, var2, var3);
      if (this.pickerOpen) {
         this.drawPicker(var1, x, 46, Math.max(40, Math.min(140, areaW - 44)));
      }

      int gy = this.pickerOpen ? 106 : 48;
      this.drawGrid(var1, font, x, gy, areaW, Math.max(16, h - gy - 54), var2, var3);
      this.drawFooter(var1, font, w, h, var2, var3);
   }

   private void drawSearchBox(class_332 g, class_327 font) {
      int y = this.listTop - 16;
      Render2D.round(g, this.listX, y, this.listW, 14, 2, 1426063360);
      if (this.editingSearch) {
         Render2D.outline(g, this.listX, y, this.listW, 14, Theme.accent());
      }

      String s = this.editingSearch ? this.searchInput.text() + (this.caretOn() ? "|" : "") : this.searchInput.text();
      if (s.isEmpty()) {
         Render2D.text(g, font, "Search items...", this.listX + 4, y + 3, ColorUtil.mulAlpha(Theme.muted(), 0.6F));
      } else {
         Render2D.text(g, font, Render2D.fit(font, s, this.listW - 8), this.listX + 4, y + 3, Theme.text());
      }

      this.hits.add(this.listX, y, this.listW, 14).left = (a, b) -> {
         this.editingHex = false;
         this.editingSearch = true;
      };
   }

   private void drawItemList(class_332 g, class_327 font, int mx, int my) {
      Render2D.round(g, this.listX - 4, this.listTop - 4, this.listW + 8, this.listBottom - this.listTop + 8, Theme.radius(), Theme.background());
      int total = this.filtered.size() * ROW;
      int view = this.listBottom - this.listTop;
      this.listScroll = Math.max(0, Math.min(this.listScroll, Math.max(0, total - view)));
      g.method_44379(this.listX, this.listTop, this.listX + this.listW, this.listBottom);
      int y = this.listTop - this.listScroll;

      for (TexturePainterScreen.ItemEntry e : this.filtered) {
         if (y + ROW >= this.listTop && y <= this.listBottom) {
            boolean hover = Hits.inside(mx, my, this.listX, y, this.listW, ROW) && my >= this.listTop && my < this.listBottom;
            boolean sel = e == this.selected;
            if (sel || hover) {
               Render2D.round(g, this.listX, y, this.listW, 19, 2, sel ? Theme.rowHover() : Theme.row());
            }

            g.method_51427(e.stack(), this.listX + 2, y + 2);
            String label = e.id().startsWith("minecraft:") ? e.id().substring(10) : e.id();
            Render2D.text(g, font, Render2D.fit(font, label, this.listW - 24), this.listX + 22, y + 6, sel ? Theme.text() : Theme.muted());
            this.hits.add(this.listX, y, this.listW, 19).clip(this.listTop, this.listBottom).left = (a, b) -> this.select(e);
         }

         y += ROW;
      }

      g.method_44380();
      if (total > view) {
         int track = view - 4;
         int bar = Math.max(10, track * view / total);
         int by = this.listTop + 2 + Math.round((track - bar) * ((float)this.listScroll / Math.max(1, total - view)));
         Render2D.round(g, this.listX + this.listW - 3, by, 2, bar, 1, ColorUtil.withAlpha(16777215, 70));
      }
   }

   private static void checker(class_332 g, int x, int y, int w, int h, int cell) {
      g.method_25294(x, y, x + w, y + h, CHECKER_A);

      for (int yy = 0; yy < h; yy += cell) {
         for (int xx = (yy / cell & 1) * cell; xx < w; xx += cell * 2) {
            g.method_25294(x + xx, y + yy, x + Math.min(w, xx + cell), y + Math.min(h, yy + cell), CHECKER_B);
         }
      }
   }

   private void drawToolbar(class_332 g, class_327 font, int x, int y, int maxW, int mx, int my) {
      String hex = this.editingHex ? this.hexInput.text() : ColorUtil.hex(this.currentColor, true);
      int hw = 90;
      boolean valid = !this.editingHex || ColorUtil.isHex(hex);
      Render2D.round(g, x, y, hw, 14, 2, 1426063360);
      if (this.editingHex) {
         Render2D.outline(g, x, y, hw, 14, valid ? Theme.accent() : -38037);
      }

      String shown = this.editingHex && this.caretOn() ? hex + "|" : hex;
      Render2D.text(g, font, Render2D.fit(font, shown, hw - 6), x + 4, y + 3, Theme.text());
      this.hits.add(x, y, hw, 14).left = (a, b) -> {
         this.editingSearch = false;
         this.editingHex = true;
         this.hexInput.begin(ColorUtil.hex(this.currentColor, true), 9, c -> c == 35 || Character.digit(c, 16) >= 0);
      };
      int sx = x + hw + 6;
      checker(g, sx, y, 20, 14, 4);
      g.method_25294(sx, y, sx + 20, y + 14, this.currentColor);
      Render2D.outline(g, sx - 1, y - 1, 22, 16, this.pickerOpen ? Theme.accent() : ColorUtil.withAlpha(16777215, 50));
      this.hits.add(sx, y, 20, 14).left = (a, b) -> {
         this.pickerOpen = !this.pickerOpen;
         if (this.pickerOpen) {
            ColorUtil.toHsv(this.currentColor, this.hsva);
         }
      };
      int tx = sx + 26;
      tx = this.toolButton(g, font, tx, y, "Pencil", TOOL_PENCIL, mx, my);
      tx = this.toolButton(g, font, tx, y, "Eraser", TOOL_ERASER, mx, my);
      tx = this.toolButton(g, font, tx, y, "Fill", TOOL_FILL, mx, my);
      tx += 4;

      for (int i = 0; i < RECENT.size() && tx + 12 <= x + maxW; i++) {
         int c = RECENT.get(i);
         checker(g, tx, y + 1, 11, 12, 3);
         g.method_25294(tx, y + 1, tx + 11, y + 13, c);
         Render2D.outline(g, tx - 1, y, 13, 14, c == this.currentColor ? Theme.accent() : ColorUtil.withAlpha(16777215, 40));
         this.hits.add(tx - 1, y, 13, 14).left = (a, b) -> {
            this.currentColor = c;
            ColorUtil.toHsv(c, this.hsva);
            if (this.tool == TOOL_ERASER) {
               this.tool = TOOL_PENCIL;
            }
         };
         tx += 14;
      }
   }

   private int toolButton(class_332 g, class_327 font, int x, int y, String label, int which, int mx, int my) {
      int w = Math.max(34, Fonts.width(font, label) + 10);
      boolean on = this.tool == which;
      boolean hover = Hits.inside(mx, my, x, y, w, 14);
      Render2D.round(g, x, y, w, 14, 2, on ? Theme.accent() : (hover ? Theme.rowHover() : Theme.row()));
      Render2D.textCenter(g, font, label, x + w / 2, y + 3, on ? -15724520 : Theme.text());
      this.hits.add(x, y, w, 14).left = (a, b) -> this.tool = which;
      return x + w + 4;
   }

   private void applyHsv() {
      this.currentColor = ColorUtil.withAlpha(ColorUtil.hsv(this.hsva[0], this.hsva[1], this.hsva[2]), ColorUtil.alpha(this.currentColor));
      if (this.tool == TOOL_ERASER) {
         this.tool = TOOL_PENCIL;
      }
   }

   private void drawPicker(class_332 g, int x, int y, int w) {
      int h = 48;

      for (int i = 0; i < w; i++) {
         float s = (i + 0.5F) / w;
         int px = x + i;
         g.method_25296(px, y, px + 1, y + h, ColorUtil.hsv(this.hsva[0], s, 1.0F), ColorUtil.hsv(this.hsva[0], s, 0.0F));
      }

      int cx = x + Math.round(this.hsva[1] * (w - 1));
      int cy = y + Math.round((1.0F - this.hsva[2]) * (h - 1));
      Render2D.outline(g, cx - 2, cy - 2, 5, 5, -1);
      int hx = x + w + 6;

      for (int i = 0; i < h; i++) {
         g.method_25294(hx, y + i, hx + 8, y + i + 1, ColorUtil.hsv((float)i / (h - 1), 1.0F, 1.0F));
      }

      int hy = y + Math.round(this.hsva[0] * (h - 1));
      g.method_25294(hx - 2, hy, hx + 10, hy + 1, -1);
      int ax = hx + 14;
      checker(g, ax, y, 8, h, 4);
      int rgb = this.currentColor & 16777215;
      g.method_25296(ax, y, ax + 8, y + h, rgb | 0xFF000000, rgb);
      int ay = y + Math.round((1.0F - ColorUtil.alpha(this.currentColor) / 255.0F) * (h - 1));
      g.method_25294(ax - 2, ay, ax + 10, ay + 1, -1);
      Hits.Action sv = (mx, my) -> {
         this.hsva[1] = clamp01((float)((mx - x) / (w - 1.0)));
         this.hsva[2] = 1.0F - clamp01((float)((my - y) / (h - 1.0)));
         this.applyHsv();
      };
      Hits.Hit svHit = this.hits.add(x, y, w, h);
      svHit.left = sv;
      svHit.drag = sv;
      Hits.Action hue = (mx, my) -> {
         this.hsva[0] = clamp01((float)((my - y) / (h - 1.0)));
         this.applyHsv();
      };
      Hits.Hit hueHit = this.hits.add(hx - 2, y, 12, h);
      hueHit.left = hue;
      hueHit.drag = hue;
      Hits.Action alpha = (mx, my) -> {
         int a = Math.round((1.0F - clamp01((float)((my - y) / (h - 1.0)))) * 255.0F);
         this.currentColor = ColorUtil.withAlpha(this.currentColor, a);
         if (this.tool == TOOL_ERASER) {
            this.tool = TOOL_PENCIL;
         }
      };
      Hits.Hit alphaHit = this.hits.add(ax - 2, y, 12, h);
      alphaHit.left = alpha;
      alphaHit.drag = alpha;
   }

   private static float clamp01(float v) {
      return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
   }

   private void drawGrid(class_332 g, class_327 font, int x, int y, int maxW, int maxH, int mx, int my) {
      if (this.canvas != null) {
         int fh = this.frameH;
         this.cellSize = Math.max(1, Math.min(24, Math.min(maxW / Math.max(1, this.canvas.width), maxH / Math.max(1, fh))));
         this.gridX = x;
         this.gridY = y;
         int gw = this.canvas.width * this.cellSize;
         int gh = fh * this.cellSize;
         this.gridW = gw;
         this.gridH = gh;
         int base = this.frame * fh;
         Render2D.round(g, x - 4, y - 4, gw + 8, gh + 8, 2, -15065303);
         g.method_25294(x, y, x + gw, y + gh, CHECKER_A);

         for (int py = 0; py < fh; py++) {
            int ry = y + py * this.cellSize;
            int px = 0;

            while (px < this.canvas.width) {
               int c = this.canvas.get(px, base + py);
               if (c >>> 24 == 255) {
                  int end = px + 1;

                  while (end < this.canvas.width && this.canvas.get(end, base + py) == c) {
                     end++;
                  }

                  g.method_25294(x + px * this.cellSize, ry, x + end * this.cellSize, ry + this.cellSize, c);
                  px = end;
               } else {
                  int rx = x + px * this.cellSize;
                  if ((px + py & 1) == 1) {
                     g.method_25294(rx, ry, rx + this.cellSize, ry + this.cellSize, CHECKER_B);
                  }

                  if (c >>> 24 != 0) {
                     g.method_25294(rx, ry, rx + this.cellSize, ry + this.cellSize, c);
                  }

                  px++;
               }
            }
         }

         if (this.cellSize >= 10) {
            int line = ColorUtil.withAlpha(16777215, 22);

            for (int i = 1; i < this.canvas.width; i++) {
               g.method_25294(x + i * this.cellSize, y, x + i * this.cellSize + 1, y + gh, line);
            }

            for (int i = 1; i < fh; i++) {
               g.method_25294(x, y + i * this.cellSize, x + gw, y + i * this.cellSize + 1, line);
            }
         }

         Render2D.outline(g, x - 1, y - 1, gw + 2, gh + 2, ColorUtil.withAlpha(16777215, 60));
         int hx = -1;
         int hy = -1;
         if (Hits.inside(mx, my, x, y, gw, gh)) {
            hx = this.cellX(mx);
            hy = this.cellY(my);
            if (this.cellSize >= 3) {
               Render2D.outline(g, x + hx * this.cellSize, y + hy * this.cellSize, this.cellSize, this.cellSize, -1);
            }
         }

         Hits.Hit grid = this.hits.add(x, y, gw, gh);
         grid.left = this::strokeStart;
         grid.drag = this::strokeDrag;
         grid.right = this::pickAt;
         int iy = y + gh + 8;
         int ix = x;
         if (this.frames > 1) {
            ix = this.smallButton(g, font, ix, iy - 3, "<", mx, my, () -> this.setFrame(this.frame - 1));
            String ft = "Frame " + (this.frame + 1) + "/" + this.frames;
            Render2D.text(g, font, ft, ix, iy, Theme.text());
            ix += Fonts.width(font, ft) + 4;
            ix = this.smallButton(g, font, ix, iy - 3, ">", mx, my, () -> this.setFrame(this.frame + 1));
            ix += 4;
         }

         String info;
         if (!this.textureFound) {
            info = this.loadMessage;
         } else {
            info = this.texNamespace + ":" + this.texPath + "  " + this.loadMessage;
         }

         if (hx >= 0 && hy >= 0) {
            info = info + "  |  " + hx + ", " + hy;
         }

         if (this.dirty()) {
            info = "* " + info;
         }

         Render2D.text(g, font, Render2D.fit(font, info, Math.max(20, maxW - (ix - x))), ix, iy, this.dirty() ? Theme.text() : Theme.muted());
      }
   }

   private int smallButton(class_332 g, class_327 font, int x, int y, String label, int mx, int my, Runnable action) {
      int w = Fonts.width(font, label) + 10;
      boolean hover = Hits.inside(mx, my, x, y, w, 14);
      Render2D.round(g, x, y, w, 14, 2, hover ? Theme.rowHover() : Theme.row());
      Render2D.textCenter(g, font, label, x + w / 2, y + 3, Theme.text());
      this.hits.add(x, y, w, 14).left = (a, b) -> action.run();
      return x + w + 4;
   }

   private int cellX(double mx) {
      return (int)Math.floor((mx - this.gridX) / this.cellSize);
   }

   private int cellY(double my) {
      return (int)Math.floor((my - this.gridY) / this.cellSize);
   }

   private boolean insideFrame(int px, int py) {
      return this.canvas != null && px >= 0 && py >= 0 && px < this.canvas.width && py < this.frameH;
   }

   private void plot(int px, int py) {
      if (this.insideFrame(px, py)) {
         this.canvas.set(px, py + this.frame * this.frameH, this.tool == TOOL_ERASER ? 0 : this.currentColor);
      }
   }

   private void line(int x0, int y0, int x1, int y1) {
      int dx = Math.abs(x1 - x0);
      int dy = -Math.abs(y1 - y0);
      int sx = x0 < x1 ? 1 : -1;
      int sy = y0 < y1 ? 1 : -1;
      int err = dx + dy;

      for (int guard = 0; guard < 8192; guard++) {
         this.plot(x0, y0);
         if (x0 == x1 && y0 == y1) {
            break;
         }

         int e2 = 2 * err;
         if (e2 >= dy) {
            err += dy;
            x0 += sx;
         }

         if (e2 <= dx) {
            err += dx;
            y0 += sy;
         }
      }
   }

   private void floodFill(int px, int py) {
      if (this.insideFrame(px, py)) {
         int w = this.canvas.width;
         int fh = this.frameH;
         int base = this.frame * fh;
         int target = this.canvas.get(px, base + py);
         int repl = this.currentColor;
         if (target != repl) {
            int[] queue = new int[w * fh];
            int head = 0;
            int tail = 0;
            this.canvas.set(px, base + py, repl);
            queue[tail++] = py * w + px;

            while (head < tail) {
               int idx = queue[head++];
               int x = idx % w;
               int y = idx / w;
               if (x > 0 && this.canvas.get(x - 1, base + y) == target) {
                  this.canvas.set(x - 1, base + y, repl);
                  queue[tail++] = idx - 1;
               }

               if (x < w - 1 && this.canvas.get(x + 1, base + y) == target) {
                  this.canvas.set(x + 1, base + y, repl);
                  queue[tail++] = idx + 1;
               }

               if (y > 0 && this.canvas.get(x, base + y - 1) == target) {
                  this.canvas.set(x, base + y - 1, repl);
                  queue[tail++] = idx - w;
               }

               if (y < fh - 1 && this.canvas.get(x, base + y + 1) == target) {
                  this.canvas.set(x, base + y + 1, repl);
                  queue[tail++] = idx + w;
               }
            }
         }
      }
   }

   private static void remember(int c) {
      RECENT.remove(Integer.valueOf(c));
      RECENT.add(0, c);

      while (RECENT.size() > 10) {
         RECENT.remove(RECENT.size() - 1);
      }
   }

   private void strokeStart(double mx, double my) {
      if (this.canvas != null) {
         int px = this.cellX(mx);
         int py = this.cellY(my);
         this.pushUndo();
         this.stroke = true;
         if (this.tool == TOOL_FILL) {
            this.floodFill(px, py);
            remember(this.currentColor);
            this.lastPx = Integer.MIN_VALUE;
         } else {
            this.plot(px, py);
            if (this.tool == TOOL_PENCIL) {
               remember(this.currentColor);
            }

            this.lastPx = px;
            this.lastPy = py;
         }
      }
   }

   private void strokeDrag(double mx, double my) {
      if (this.canvas != null && this.lastPx != Integer.MIN_VALUE) {
         int px = this.cellX(mx);
         int py = this.cellY(my);
         if (px != this.lastPx || py != this.lastPy) {
            this.line(this.lastPx, this.lastPy, px, py);
            this.lastPx = px;
            this.lastPy = py;
         }
      }
   }

   private void strokeEnd() {
      if (this.stroke) {
         this.stroke = false;
         if (this.canvas != null && !this.undo.isEmpty() && Arrays.equals(this.undo.peek(), this.canvas.pixels)) {
            this.undo.pop();
         }
      }

      this.lastPx = Integer.MIN_VALUE;
   }

   private void pickAt(double mx, double my) {
      int px = this.cellX(mx);
      int py = this.cellY(my);
      if (this.insideFrame(px, py)) {
         int c = this.canvas.get(px, py + this.frame * this.frameH);
         if (c >>> 24 == 0) {
            this.saveMessage = "That pixel is transparent.";
         } else {
            this.currentColor = c;
            ColorUtil.toHsv(c, this.hsva);
            if (this.tool == TOOL_ERASER) {
               this.tool = TOOL_PENCIL;
            }

            this.saveMessage = "Picked " + ColorUtil.hex(c, true) + ".";
         }
      }
   }

   private void drawFooter(class_332 g, class_327 font, int w, int h, int mx, int my) {
      int y = h - 22;
      int x = 10;
      x = this.button(g, font, x, y, "Save", mx, my, this::save);
      x = this.button(g, font, x, y, "Export ZIP", mx, my, () -> TexturePainterExtras.exportZip(this));
      x = this.button(g, font, x, y, "Undo", mx, my, this::undo);
      x = this.button(g, font, x, y, "Reset", mx, my, this::resetToOriginal);
      x = this.button(g, font, x, y, "Remove edit", mx, my, this::removeEdit);
      x = this.button(g, font, x, y, "Close", mx, my, this::method_25419);
      String msg = this.saveMessage.isEmpty() ? "Left click paints, right click picks a color, Ctrl+Z undoes." : this.saveMessage;
      Render2D.text(g, font, Render2D.fit(font, msg, Math.max(20, w - x - 14)), x + 6, y + 4, this.saveMessage.isEmpty() ? Theme.muted() : Theme.accent());
   }

   private int button(class_332 g, class_327 font, int x, int y, String label, int mx, int my, Runnable action) {
      int bw = Fonts.width(font, label) + 16;
      boolean hover = Hits.inside(mx, my, x, y, bw, 16);
      Render2D.round(g, x, y, bw, 16, 2, hover ? Theme.rowHover() : Theme.row());
      Render2D.textCenter(g, font, label, x + bw / 2, y + 4, Theme.text());
      this.hits.add(x, y, bw, 16).left = (a, b) -> action.run();
      return x + bw + 6;
   }

   private boolean caretOn() {
      return System.currentTimeMillis() / 450L % 2L == 0L;
   }

   private void endEdit(boolean commit) {
      if (this.editingHex) {
         if (commit && ColorUtil.isHex(this.hexInput.text())) {
            int digits = ColorUtil.hexDigits(this.hexInput.text());
            int c = ColorUtil.parseHex(this.hexInput.text(), this.currentColor);
            this.currentColor = digits == 8 ? c : ColorUtil.withAlpha(c, ColorUtil.alpha(this.currentColor));
            ColorUtil.toHsv(this.currentColor, this.hsva);
            if (this.tool == TOOL_ERASER) {
               this.tool = TOOL_PENCIL;
            }
         }

         this.editingHex = false;
      }

      this.editingSearch = false;
   }

   @Override
   protected boolean onKey(int var1, int var2, int var3) {
      if (this.editingHex || this.editingSearch) {
         if (var1 == 256) {
            this.endEdit(false);
         } else if (var1 == 257 || var1 == 335) {
            this.endEdit(true);
         } else if (var1 == 259) {
            (this.editingHex ? this.hexInput : this.searchInput).backspace();
            if (this.editingSearch) {
               this.refilter();
            }
         }

         return true;
      } else if (var1 == 256) {
         this.method_25419();
         return true;
      } else {
         // GLFW_MOD_CONTROL, or SDL's left / right control bits on 26.3+
         boolean ctrl = (var3 & 2) != 0 || (var3 & 192) != 0;
         if (ctrl && var1 == 90) {
            this.undo();
            return true;
         } else if (ctrl && var1 == 83) {
            this.save();
            return true;
         } else if (this.frames > 1 && (var1 == 262 || var1 == 263)) {
            this.setFrame(this.frame + (var1 == 262 ? 1 : -1));
            return true;
         } else {
            return false;
         }
      }
   }

   @Override
   protected boolean onChar(String var1) {
      if (this.editingHex) {
         this.hexInput.append(var1);
         return true;
      } else if (this.editingSearch) {
         this.searchInput.append(var1);
         this.refilter();
         return true;
      } else {
         return false;
      }
   }

   @Override
   protected boolean onMouseDown(double var1, double var3, int var5) {
      boolean wasEditing = this.editingHex || this.editingSearch;
      this.endEdit(true);
      Hits.Hit hit = this.hits.find(var1, var3);
      this.dragAction = null;
      if (hit == null) {
         return wasEditing;
      } else {
         if (var5 == 0) {
            if (hit.left != null) {
               hit.left.run(var1, var3);
            }

            this.dragAction = hit.drag;
         } else if (var5 == 1 && hit.right != null) {
            hit.right.run(var1, var3);
         }

         return true;
      }
   }

   @Override
   protected boolean onMouseDrag(double var1, double var3, int var5, double var6, double var8) {
      if (this.dragAction != null) {
         this.dragAction.run(var1, var3);
         return true;
      } else {
         return false;
      }
   }

   @Override
   protected boolean onMouseUp(double var1, double var3, int var5) {
      boolean had = this.dragAction != null || this.stroke;
      this.dragAction = null;
      this.strokeEnd();
      return had;
   }

   @Override
   public boolean method_25401(double var1, double var3, double var5, double var7) {
      if (Hits.inside(var1, var3, this.listX, this.listTop, this.listW, this.listBottom - this.listTop)) {
         this.listScroll -= (int)(var7 * 16.0);
         return true;
      } else if (this.frames > 1 && Hits.inside(var1, var3, this.gridX, this.gridY, this.gridW, this.gridH)) {
         this.setFrame(this.frame + (var7 < 0.0 ? 1 : -1));
         return true;
      } else {
         return false;
      }
   }

   private record ItemEntry(class_1792 item, String id, class_2960 key, class_1799 stack, boolean explicit) {
   }
}
