package net.visualstyles.style;

/** Colour math on 0xAARRGGBB ints. */
public final class Col {
   private Col() {
   }

   public static int a(int c) {
      return c >>> 24;
   }

   public static int r(int c) {
      return c >> 16 & 255;
   }

   public static int g(int c) {
      return c >> 8 & 255;
   }

   public static int b(int c) {
      return c & 255;
   }

   public static int argb(int a, int r, int g, int b) {
      return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
   }

   public static int clamp(int v) {
      return v < 0 ? 0 : Math.min(v, 255);
   }

   public static int withAlpha(int c, int a) {
      return clamp(a) << 24 | c & 0xFFFFFF;
   }

   /** Relative luminance 0..1. */
   public static float lum(int c) {
      return (0.299F * r(c) + 0.587F * g(c) + 0.114F * b(c)) / 255.0F;
   }

   /** Linear mix of the RGB channels; keeps the alpha of a. */
   public static int lerp(int a, int b, float t) {
      t = Math.max(0.0F, Math.min(1.0F, t));
      return argb(
         a(a),
         Math.round(r(a) + (r(b) - r(a)) * t),
         Math.round(g(a) + (g(b) - g(a)) * t),
         Math.round(b(a) + (b(b) - b(a)) * t)
      );
   }

   /** Mix that also blends alpha (for UI colours). */
   public static int lerpArgb(int a, int b, float t) {
      t = Math.max(0.0F, Math.min(1.0F, t));
      return argb(
         Math.round(a(a) + (a(b) - a(a)) * t),
         Math.round(r(a) + (r(b) - r(a)) * t),
         Math.round(g(a) + (g(b) - g(a)) * t),
         Math.round(b(a) + (b(b) - b(a)) * t)
      );
   }

   /** Source-over alpha blend of src onto dst. */
   public static int over(int src, int dst) {
      float sa = a(src) / 255.0F;
      float da = a(dst) / 255.0F;
      float oa = sa + da * (1.0F - sa);
      if (oa <= 0.0F) {
         return 0;
      }
      int r = Math.round((r(src) * sa + r(dst) * da * (1.0F - sa)) / oa);
      int g = Math.round((g(src) * sa + g(dst) * da * (1.0F - sa)) / oa);
      int b = Math.round((b(src) * sa + b(dst) * da * (1.0F - sa)) / oa);
      return argb(Math.round(oa * 255.0F), r, g, b);
   }

   /** Photoshop-style colourize: dark parts go toward black, light parts toward white, mid-tones are the tint. */
   public static int colorize(int alpha, int tint, float light) {
      light = Math.max(0.0F, Math.min(1.0F, light));
      int tr = r(tint);
      int tg = g(tint);
      int tb = b(tint);
      if (light < 0.5F) {
         float k = light * 2.0F;
         return argb(alpha, Math.round(tr * k), Math.round(tg * k), Math.round(tb * k));
      }
      float k = light * 2.0F - 1.0F;
      return argb(alpha, Math.round(tr + (255 - tr) * k), Math.round(tg + (255 - tg) * k), Math.round(tb + (255 - tb) * k));
   }

   /** Hue 0..1, saturation 0..1, value 0..1. */
   public static float[] hsv(int c) {
      float r = r(c) / 255.0F;
      float g = g(c) / 255.0F;
      float b = b(c) / 255.0F;
      float max = Math.max(r, Math.max(g, b));
      float min = Math.min(r, Math.min(g, b));
      float d = max - min;
      float h = 0.0F;
      if (d > 0.0F) {
         if (max == r) {
            h = (g - b) / d % 6.0F;
         } else if (max == g) {
            h = (b - r) / d + 2.0F;
         } else {
            h = (r - g) / d + 4.0F;
         }
         h /= 6.0F;
         if (h < 0.0F) {
            h += 1.0F;
         }
      }
      return new float[]{h, max <= 0.0F ? 0.0F : d / max, max};
   }

   public static int fromHsv(float h, float s, float v, int alpha) {
      h = (h % 1.0F + 1.0F) % 1.0F * 6.0F;
      int i = (int)Math.floor(h);
      float f = h - i;
      float p = v * (1.0F - s);
      float q = v * (1.0F - s * f);
      float t = v * (1.0F - s * (1.0F - f));
      float r;
      float g;
      float b;
      switch (i) {
         case 0 -> { r = v; g = t; b = p; }
         case 1 -> { r = q; g = v; b = p; }
         case 2 -> { r = p; g = v; b = t; }
         case 3 -> { r = p; g = q; b = v; }
         case 4 -> { r = t; g = p; b = v; }
         default -> { r = v; g = p; b = q; }
      }
      return argb(alpha, Math.round(r * 255.0F), Math.round(g * 255.0F), Math.round(b * 255.0F));
   }

   /** Parses RRGGBB or #RRGGBB; returns fallback on bad input. */
   public static int parseHex(String s, int fallback) {
      if (s == null) {
         return fallback;
      }
      String t = s.trim();
      if (t.startsWith("#")) {
         t = t.substring(1);
      }
      if (t.length() != 6) {
         return fallback;
      }
      try {
         return 0xFF000000 | Integer.parseInt(t, 16);
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   public static String hex(int c) {
      return String.format("%06X", c & 0xFFFFFF);
   }
}
