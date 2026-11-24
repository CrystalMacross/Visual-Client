package net.visualstyles.gui;

import net.minecraft.class_10799;
import net.minecraft.class_2960;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.visualstyles.style.Col;
import org.joml.Matrix3x2fStack;

/**
 * Drawing helpers on GuiGraphics (class_332) for 1.21.11. The colours are switched per menu design with
 * use(Palette) at the start of every frame, so every helper draws in the design's colours.
 */
final class Ui {
   /** Colours of one menu design. */
   record Palette(int panel, int panel2, int rail, int card, int cardHover, int field, int line, int line2,
                  int fg, int muted, int faint, int accentA, int accentB, int soft, int gold, int cyanTag,
                  int checkA, int checkB, int dim, int onAccent) {
   }

   static final Palette STUDIO = new Palette(0xF40C0E15, 0xFF141722, 0xFF0F121A, 0xFF131620, 0xFF1B1F2D, 0xFF0A0C12,
      0xFF222636, 0xFF323850, 0xFFEEF0F7, 0xFFA3A9BF, 0xFF6F7690, 0xFF8B5CF6, 0xFF38BDF8, 0xFFC4B1FF, 0xFFFFC83D,
      0xFF38BDF8, 0xFF181B26, 0xFF1F2331, 0x70000000, 0xFFFFFFFF);
   static final Palette GLASS = new Palette(0xE8F5F6FA, 0xFFFFFFFF, 0xC8E9ECF3, 0xFFFFFFFF, 0xFFF1EEFF, 0xFFFFFFFF,
      0xFFDCE0EA, 0xFFC6CCDA, 0xFF151923, 0xFF4B5265, 0xFF868DA1, 0xFF6D4AFF, 0xFF0EA5E9, 0xFF6D4AFF, 0xFFC98A00,
      0xFF0284C7, 0xFFE8EBF2, 0xFFF6F7FB, 0x38000000, 0xFFFFFFFF);
   static final Palette NEON = new Palette(0xF6050509, 0xFF0F0D19, 0xFF08070E, 0xFF0B0A12, 0xFF161126, 0xFF06050A,
      0xFF1F1A30, 0xFF3A2357, 0xFFF7F3FF, 0xFFBBADDD, 0xFF6E6690, 0xFFFF2BD6, 0xFF00F0FF, 0xFFFF7BE8, 0xFFFFE14D,
      0xFF00F0FF, 0xFF0C0B14, 0xFF14121F, 0x88000000, 0xFF07060C);
   static final Palette SHOWCASE = new Palette(0xF40A0F1E, 0xFF131A30, 0xFF0D1326, 0xFF111829, 0xFF1A2440, 0xFF090E1A,
      0xFF1E2843, 0xFF2D3A5D, 0xFFF2F5FF, 0xFFAAB5D8, 0xFF6B769A, 0xFFFFB547, 0xFFFF6B8B, 0xFFFFD38F, 0xFFFFC83D,
      0xFF7DD3FC, 0xFF151C30, 0xFF1C2540, 0x80000000, 0xFF1A1206);
   static final Palette DOCK = new Palette(0xF00E1216, 0xFF172027, 0xFF0B0F13, 0xFF141A20, 0xFF1C252D, 0xFF0A0E12,
      0xFF212B34, 0xFF33414D, 0xFFE9F2F7, 0xFF9DAFBC, 0xFF677885, 0xFF34D399, 0xFF22D3EE, 0xFF86EFAC, 0xFFFACC15,
      0xFF22D3EE, 0xFF151C22, 0xFF1C252C, 0x00000000, 0xFF06130E);

   static int PANEL;
   static int PANEL2;
   static int RAIL;
   static int CARD;
   static int CARD_HOVER;
   static int FIELD;
   static int LINE;
   static int LINE2;
   static int FG;
   static int MUTED;
   static int FAINT;
   static int VIOLET;
   static int CYAN;
   static int SOFT;
   static int GOLD;
   static int TAG;
   static int CHECK_A;
   static int CHECK_B;
   static int DIM;
   static int ON_ACCENT;
   static boolean light;

   static {
      use(STUDIO);
   }

   private Ui() {
   }

   static void use(Palette p) {
      PANEL = p.panel();
      PANEL2 = p.panel2();
      RAIL = p.rail();
      CARD = p.card();
      CARD_HOVER = p.cardHover();
      FIELD = p.field();
      LINE = p.line();
      LINE2 = p.line2();
      FG = p.fg();
      MUTED = p.muted();
      FAINT = p.faint();
      VIOLET = p.accentA();
      CYAN = p.accentB();
      SOFT = p.soft();
      GOLD = p.gold();
      TAG = p.cyanTag();
      CHECK_A = p.checkA();
      CHECK_B = p.checkB();
      DIM = p.dim();
      ON_ACCENT = p.onAccent();
      light = Col.lum(p.panel2()) > 0.6F;
   }

   /** Hover / pressed shade that works on light and dark designs. */
   static int hoverFill() {
      return light ? 0xFFEDEAFF : CARD_HOVER;
   }

   static int buttonFill(boolean hover) {
      return hover ? (light ? 0xFFEFEAFF : 0xFF1B1F2E) : (light ? 0xFFFFFFFF : 0xFF10131B);
   }

   static void rect(class_332 g, int x, int y, int w, int h, int color) {
      if (w > 0 && h > 0 && color >>> 24 != 0) {
         g.method_25294(x, y, x + w, y + h, color);
      }
   }

   /** Rectangle with the four corner pixels cut off (looks rounded at GUI scale). */
   static void round(class_332 g, int x, int y, int w, int h, int color) {
      if (w > 2 && h > 2) {
         rect(g, x + 1, y, w - 2, 1, color);
         rect(g, x, y + 1, w, h - 2, color);
         rect(g, x + 1, y + h - 1, w - 2, 1, color);
      } else {
         rect(g, x, y, w, h, color);
      }
   }

   /** Softer rounding: two pixels cut at every corner. */
   static void round2(class_332 g, int x, int y, int w, int h, int color) {
      if (w > 4 && h > 4) {
         rect(g, x + 2, y, w - 4, 1, color);
         rect(g, x + 1, y + 1, w - 2, 1, color);
         rect(g, x, y + 2, w, h - 4, color);
         rect(g, x + 1, y + h - 2, w - 2, 1, color);
         rect(g, x + 2, y + h - 1, w - 4, 1, color);
      } else {
         round(g, x, y, w, h, color);
      }
   }

   static void outline(class_332 g, int x, int y, int w, int h, int color) {
      if (w > 1 && h > 1) {
         rect(g, x, y, w, 1, color);
         rect(g, x, y + h - 1, w, 1, color);
         rect(g, x, y + 1, 1, h - 2, color);
         rect(g, x + w - 1, y + 1, 1, h - 2, color);
      }
   }

   /** Outline without the corner pixels, matching round(). */
   static void roundOutline(class_332 g, int x, int y, int w, int h, int color) {
      if (w > 2 && h > 2) {
         rect(g, x + 1, y, w - 2, 1, color);
         rect(g, x + 1, y + h - 1, w - 2, 1, color);
         rect(g, x, y + 1, 1, h - 2, color);
         rect(g, x + w - 1, y + 1, 1, h - 2, color);
      }
   }

   /** Soft dark shadow around a panel. */
   static void shadow(class_332 g, int x, int y, int w, int h) {
      for (int i = 1; i <= 4; i++) {
         int a = (light ? 0x1C : 0x30) - i * (light ? 0x05 : 0x09);
         roundOutline(g, x - i, y - i + 1, w + i * 2, h + i * 2, a << 24);
      }
   }

   /** Coloured glow around a box (neon design). */
   static void glow(class_332 g, int x, int y, int w, int h, int color, int size) {
      for (int i = 1; i <= size; i++) {
         float k = 1.0F - (float)i / (size + 1);
         roundOutline(g, x - i, y - i, w + i * 2, h + i * 2, alpha(color, 0.22F * k * k));
      }
   }

   /** Thin horizontal lines over an area (neon design). */
   static void scanlines(class_332 g, int x, int y, int w, int h) {
      for (int yy = y; yy < y + h; yy += 3) {
         rect(g, x, yy, w, 1, 0x0CFFFFFF);
      }
   }

   /** Horizontal gradient made of thin strips. */
   static void gradientH(class_332 g, int x, int y, int w, int h, int left, int right) {
      if (w <= 0 || h <= 0) {
         return;
      }
      int strips = Math.max(1, Math.min(w, 48));
      for (int i = 0; i < strips; i++) {
         int a = x + w * i / strips;
         int b = x + w * (i + 1) / strips;
         rect(g, a, y, b - a, h, Col.lerpArgb(left, right, (i + 0.5F) / strips));
      }
   }

   static void gradientV(class_332 g, int x, int y, int w, int h, int top, int bottom) {
      if (w > 0 && h > 0) {
         g.method_25296(x, y, x + w, y + h, top, bottom);
      }
   }

   /** Border in the accent colours. */
   static void accentOutline(class_332 g, int x, int y, int w, int h) {
      gradientH(g, x, y, w, 1, VIOLET, CYAN);
      gradientH(g, x, y + h - 1, w, 1, VIOLET, CYAN);
      gradientV(g, x, y + 1, 1, h - 2, VIOLET, CYAN);
      gradientV(g, x + w - 1, y + 1, 1, h - 2, VIOLET, CYAN);
   }

   /** Accent border whose colours slowly move around (picked but not applied yet). */
   static void pulseOutline(class_332 g, int x, int y, int w, int h) {
      float t = pulse(1400L);
      int a = Col.lerpArgb(VIOLET, CYAN, t);
      int b = Col.lerpArgb(CYAN, VIOLET, t);
      gradientH(g, x + 1, y, w - 2, 1, a, b);
      gradientH(g, x + 1, y + h - 1, w - 2, 1, b, a);
      gradientV(g, x, y + 1, 1, h - 2, a, b);
      gradientV(g, x + w - 1, y + 1, 1, h - 2, b, a);
   }

   /** 0..1..0 over the given period. */
   static float pulse(long periodMs) {
      double t = (System.currentTimeMillis() % periodMs) / (double)periodMs;
      return (float)(0.5 - 0.5 * Math.cos(t * Math.PI * 2.0));
   }

   static int alpha(int color, float a) {
      return Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, a))) << 24 | color & 0xFFFFFF;
   }

   static int width(class_327 font, String s) {
      return font.method_1727(s);
   }

   static void text(class_332 g, class_327 font, String s, int x, int y, int color) {
      g.method_51433(font, s, x, y, color, false);
   }

   static void textCenter(class_332 g, class_327 font, String s, int centerX, int y, int color) {
      text(g, font, s, centerX - width(font, s) / 2, y, color);
   }

   static void textRight(class_332 g, class_327 font, String s, int right, int y, int color) {
      text(g, font, s, right - width(font, s), y, color);
   }

   static final float SMALL = 0.75F;

   static int smallWidth(class_327 font, String s) {
      return Math.round(width(font, s) * SMALL);
   }

   /** Text at 3/4 size (labels, tags). */
   static void small(class_332 g, class_327 font, String s, int x, int y, int color) {
      scaled(g, font, s, x, y, color, SMALL);
   }

   static void scaled(class_332 g, class_327 font, String s, int x, int y, int color, float scale) {
      Matrix3x2fStack m = g.method_51448();
      m.pushMatrix();
      m.translate(x, y);
      m.scale(scale, scale);
      g.method_51433(font, s, 0, 0, color, false);
      m.popMatrix();
   }

   static void smallCenter(class_332 g, class_327 font, String s, int centerX, int y, int color) {
      small(g, font, s, centerX - smallWidth(font, s) / 2, y, color);
   }

   /** Cuts text to maxWidth with "..". */
   static String fit(class_327 font, String s, int maxWidth) {
      if (s == null) {
         return "";
      }
      if (width(font, s) <= maxWidth) {
         return s;
      }
      int end = s.length();
      while (end > 1 && width(font, s.substring(0, end) + "..") > maxWidth) {
         end--;
      }
      return s.substring(0, Math.max(0, end)) + "..";
   }

   static String fitSmall(class_327 font, String s, int maxWidth) {
      return fit(font, s, Math.round(maxWidth / SMALL));
   }

   /** Draws a texture of texW x texH into a box x box square, keeping its aspect ratio. */
   static void blitFit(class_332 g, class_2960 id, int x, int y, int box, int texW, int texH) {
      int dw = box;
      int dh = box;
      if (texW > texH) {
         dh = Math.max(1, box * texH / texW);
      } else if (texH > texW) {
         dw = Math.max(1, box * texW / texH);
      }
      int dx = x + (box - dw) / 2;
      int dy = y + (box - dh) / 2;
      // blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight)
      g.method_25302(class_10799.field_56883, id, dx, dy, 0.0F, 0.0F, dw, dh, texW, texH, texW, texH);
   }

   /** Checkerboard behind transparent previews. */
   static void checker(class_332 g, int x, int y, int w, int h, int cell) {
      rect(g, x, y, w, h, CHECK_A);
      for (int yy = 0; yy < h; yy += cell) {
         for (int xx = (yy / cell & 1) * cell; xx < w; xx += cell * 2) {
            rect(g, x + xx, y + yy, Math.min(cell, w - xx), Math.min(cell, h - yy), CHECK_B);
         }
      }
   }
}
