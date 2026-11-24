package net.visualstyles.style;

/** Image operations that turn a vanilla texture into a style. All of them return a new image. */
public final class Ops {
   private Ops() {
   }

   /**
    * How lightness is mapped before colourizing. lo..hi is the target range, stretch says how far the
    * image's own lightness range is spread over it (0 = keep the original lightness).
    */
   public record Tone(float lo, float hi, float stretch) {
      public static final Tone NORMAL = new Tone(0.14F, 0.96F, 0.65F);
      public static final Tone DARK = new Tone(0.05F, 0.62F, 0.7F);
      public static final Tone LIGHT = new Tone(0.45F, 1.0F, 0.6F);
      public static final Tone FIRE = new Tone(0.08F, 1.0F, 0.45F);
      public static final Tone KEEP = new Tone(0.0F, 1.0F, 0.0F);
   }

   /** Tint for a pixel; fx/fy are the coordinates inside the current animation frame. */
   public interface TintAt {
      int tint(int fx, int fy, int fw, int fh);
   }

   /** Pixels a style leaves alone (wooden handles, the anchor's glowstone). */
   public interface Keep {
      boolean keep(int argb);
   }

   public static final Keep NONE = c -> false;

   public static final Keep WOOD = c -> {
      float[] h = Col.hsv(c);
      float deg = h[0] * 360.0F;
      return deg >= 12.0F && deg <= 50.0F && h[1] >= 0.28F && h[2] <= 0.88F;
   };

   public static final Keep GLOW = Ops::isGlow;

   /** Glowstone-like orange/yellow light (the respawn anchor's charge lights). */
   public static boolean isGlow(int c) {
      float[] h = Col.hsv(c);
      float deg = h[0] * 360.0F;
      return deg >= 22.0F && deg <= 68.0F && h[1] >= 0.35F && h[2] >= 0.5F;
   }

   public static TintAt solid(int color) {
      return (fx, fy, fw, fh) -> color;
   }

   public static TintAt rainbow() {
      return (fx, fy, fw, fh) -> Col.fromHsv(fx / (float)Math.max(1, fw) + fy / (float)Math.max(1, fh) * 0.45F, 0.82F, 1.0F, 255);
   }

   public static TintAt vertical(int top, int bottom) {
      return (fx, fy, fw, fh) -> Col.lerp(top, bottom, fy / (float)Math.max(1, fh - 1));
   }

   /** Colourizes every pixel that is not kept: the detail stays, the colour comes from the tint. */
   public static Pic paint(Pic src, TintAt tint, Tone tone, Keep keep) {
      Pic out = src.copy();
      float min = 1.0F;
      float max = 0.0F;
      for (int c : src.px) {
         if (Col.a(c) > 0 && !keep.keep(c)) {
            float l = Col.lum(c);
            min = Math.min(min, l);
            max = Math.max(max, l);
         }
      }
      float range = Math.max(0.0001F, max - min);
      int fh = src.frameHeight();
      for (int y = 0; y < src.h; y++) {
         for (int x = 0; x < src.w; x++) {
            int i = y * src.w + x;
            int c = src.px[i];
            int a = Col.a(c);
            if (a == 0 || keep.keep(c)) {
               continue;
            }
            float l = Col.lum(c);
            float target = tone.lo() + (l - min) / range * (tone.hi() - tone.lo());
            float light = l + (target - l) * tone.stretch();
            out.px[i] = Col.colorize(a, tint.tint(x, y % fh, src.w, fh), light);
         }
      }
      return out;
   }

   public static Pic invisible(Pic src) {
      return new Pic(src.w, src.h);
   }

   /** Multiplies the RGB channels (k < 1 darkens). */
   public static Pic multiply(Pic src, float k) {
      Pic out = src.copy();
      for (int i = 0; i < out.px.length; i++) {
         int c = out.px[i];
         out.px[i] = Col.argb(Col.a(c), Math.round(Col.r(c) * k), Math.round(Col.g(c) * k), Math.round(Col.b(c) * k));
      }
      return out;
   }

   /** Moves every pixel toward a colour. */
   public static Pic toward(Pic src, int color, float t) {
      Pic out = src.copy();
      for (int i = 0; i < out.px.length; i++) {
         if (Col.a(out.px[i]) > 0) {
            out.px[i] = Col.lerp(out.px[i], color, t);
         }
      }
      return out;
   }

   /** Keeps the texture's lightness but shifts it toward the tint (for blocks: subtle). */
   public static Pic tintMix(Pic src, int tint, float mix) {
      Pic painted = paint(src, solid(tint), Tone.KEEP, NONE);
      Pic out = src.copy();
      for (int i = 0; i < out.px.length; i++) {
         if (Col.a(out.px[i]) > 0) {
            out.px[i] = Col.lerp(out.px[i], painted.px[i], mix);
         }
      }
      return out;
   }

   public static Pic gray(Pic src) {
      Pic out = src.copy();
      for (int i = 0; i < out.px.length; i++) {
         int c = out.px[i];
         int l = Math.round(Col.lum(c) * 255.0F);
         out.px[i] = Col.argb(Col.a(c), l, l, l);
      }
      return out;
   }

   /** 3x3 blur that wraps around the edges (tiling block textures stay seamless). */
   public static Pic smooth(Pic src, float mix) {
      Pic out = src.copy();
      int fh = src.frameHeight();
      for (int y = 0; y < src.h; y++) {
         int frame = y / fh * fh;
         for (int x = 0; x < src.w; x++) {
            int r = 0;
            int g = 0;
            int b = 0;
            for (int dy = -1; dy <= 1; dy++) {
               int yy = frame + Math.floorMod(y - frame + dy, fh);
               for (int dx = -1; dx <= 1; dx++) {
                  int c = src.px[yy * src.w + Math.floorMod(x + dx, src.w)];
                  r += Col.r(c);
                  g += Col.g(c);
                  b += Col.b(c);
               }
            }
            int i = y * src.w + x;
            int blurred = Col.argb(Col.a(src.px[i]), r / 9, g / 9, b / 9);
            out.px[i] = Col.lerp(src.px[i], blurred, mix);
         }
      }
      return out;
   }

   /** Pulls every pixel toward the average colour: a clean, flat look. */
   public static Pic flat(Pic src, float mix) {
      long r = 0;
      long g = 0;
      long b = 0;
      int n = 0;
      for (int c : src.px) {
         if (Col.a(c) > 0) {
            r += Col.r(c);
            g += Col.g(c);
            b += Col.b(c);
            n++;
         }
      }
      if (n == 0) {
         return src.copy();
      }
      return toward(src, Col.argb(255, (int)(r / n), (int)(g / n), (int)(b / n)), mix);
   }

   /** Dark container GUI: greys are mapped to a dark range (bevels keep their order), coloured icons stay. */
   public static Pic darkGui(Pic src, int tint, float mix) {
      Pic out = src.copy();
      for (int i = 0; i < out.px.length; i++) {
         int c = out.px[i];
         int a = Col.a(c);
         if (a == 0) {
            continue;
         }
         int r = Col.r(c);
         int g = Col.g(c);
         int b = Col.b(c);
         if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 12) {
            continue;
         }
         int l = (r + g + b) / 3;
         int d = l <= 200 ? Math.round(8.0F + l * 0.25F) : Math.round(58.0F + (l - 200) * 0.6F);
         int grey = Col.argb(a, d, d, d);
         out.px[i] = mix <= 0.0F ? grey : Col.lerp(grey, Col.colorize(a, tint, Math.min(1.0F, d / 255.0F * 1.25F)), mix);
      }
      return out;
   }

   /** Recolours the glowstone-like charge lights, keeping their brightness. */
   public static Pic recolorGlow(Pic src, int color) {
      Pic out = src.copy();
      float[] t = Col.hsv(color);
      for (int i = 0; i < out.px.length; i++) {
         int c = out.px[i];
         if (Col.a(c) > 0 && isGlow(c)) {
            float[] h = Col.hsv(c);
            out.px[i] = Col.fromHsv(t[0], Math.min(1.0F, t[1] * 0.9F + 0.1F), h[2], Col.a(c));
         }
      }
      return out;
   }

   // ------------------------------------------------------------------ end crystal (64x32 layout)
   // glass cube: texOffs(0,0) -> x 0..32, y 0..16 | core cube: texOffs(32,0) -> x 32..64, y 0..16 | base: y 16..32

   private static boolean inGlass(Pic p, int x, int y) {
      float s = p.w / 64.0F;
      return x < 32.0F * s && y < 16.0F * s;
   }

   private static boolean inCore(Pic p, int x, int y) {
      float s = p.w / 64.0F;
      return x >= 32.0F * s && y < 16.0F * s;
   }

   /**
    * See-through crystal: the end crystal is drawn with a cutout render type (a pixel is either fully opaque or
    * gone), and the vanilla glass is already mostly a frame, so hollowing only the glass looked like vanilla with
    * a solid core in the middle. Now glass AND core are thin solid frames: you look straight through the
    * crystal, the bright core frame still shows where it is.
    */
   public static Pic crystalSeeThrough(Pic src) {
      Pic out = src.copy();
      int s = Math.max(1, src.w / 64);
      int tile = 8 * s;
      for (int y = 0; y < src.h; y++) {
         for (int x = 0; x < src.w; x++) {
            boolean glass = inGlass(src, x, y);
            boolean core = inCore(src, x, y);
            if (!glass && !core) {
               continue;
            }
            int i = y * src.w + x;
            int lx = x % tile;
            int ly = y % tile;
            boolean border = lx < s || lx >= tile - s || ly < s || ly >= tile - s;
            if (!border) {
               out.px[i] = 0;
            } else if (core) {
               int c = Col.a(out.px[i]) == 0 ? 0xFFE879F9 : out.px[i];
               out.px[i] = Col.withAlpha(Col.lerp(c, 0xFFFFFFFF, 0.3F), 255);
            } else {
               int c = Col.a(out.px[i]) == 0 ? 0xFFD8CCFF : out.px[i];
               out.px[i] = Col.withAlpha(Col.lerp(c, 0xFFFFFFFF, 0.15F), 255);
            }
         }
      }
      return out;
   }

   /** Only the inner core is left: glass and base are invisible. */
   public static Pic crystalCoreOnly(Pic src) {
      Pic out = src.copy();
      for (int y = 0; y < src.h; y++) {
         for (int x = 0; x < src.w; x++) {
            if (!inCore(src, x, y)) {
               out.px[y * src.w + x] = 0;
            }
         }
      }
      return out;
   }

   /** Colours only the inner core cube. */
   public static Pic crystalCore(Pic src, int color) {
      Pic out = src.copy();
      float min = 1.0F;
      float max = 0.0F;
      for (int y = 0; y < src.h; y++) {
         for (int x = 0; x < src.w; x++) {
            int c = src.px[y * src.w + x];
            if (inCore(src, x, y) && Col.a(c) > 0) {
               float l = Col.lum(c);
               min = Math.min(min, l);
               max = Math.max(max, l);
            }
         }
      }
      float range = Math.max(0.0001F, max - min);
      for (int y = 0; y < src.h; y++) {
         for (int x = 0; x < src.w; x++) {
            int i = y * src.w + x;
            int c = src.px[i];
            if (inCore(src, x, y) && Col.a(c) > 0) {
               float l = 0.2F + (Col.lum(c) - min) / range * 0.72F;
               out.px[i] = Col.colorize(Col.a(c), color, l);
            }
         }
      }
      return out;
   }

   // ------------------------------------------------------------------ crosshair shapes (white, like vanilla)

   public static Pic crosshair(String shape, int n) {
      Pic p = new Pic(n, n);
      int c = n / 2;
      int w = 0xFFFFFFFF;
      switch (shape) {
         case "dot" -> {
            for (int y = c - 1; y <= c + 1; y++) {
               for (int x = c - 1; x <= c + 1; x++) {
                  p.set(x, y, w);
               }
            }
         }
         case "tiny" -> plus(p, c, 1, 0, w);
         case "small" -> plus(p, c, 3, 0, w);
         case "gap" -> {
            plus(p, c, c, 3, w);
            p.set(c, c, w);
         }
         case "circle" -> {
            float r = Math.max(2.0F, n / 3.0F);
            for (int y = 0; y < n; y++) {
               for (int x = 0; x < n; x++) {
                  double d = Math.hypot(x - c, y - c);
                  if (Math.abs(d - r) < 0.55) {
                     p.set(x, y, w);
                  }
               }
            }
            p.set(c, c, w);
         }
         case "square" -> {
            int r = Math.max(2, n / 4);
            for (int i = -r; i <= r; i++) {
               p.set(c + i, c - r, w);
               p.set(c + i, c + r, w);
               p.set(c - r, c + i, w);
               p.set(c + r, c + i, w);
            }
            p.set(c, c, w);
         }
         case "x" -> {
            int r = Math.max(2, n / 4);
            for (int i = -r; i <= r; i++) {
               p.set(c + i, c + i, w);
               p.set(c + i, c - i, w);
            }
         }
         default -> {
            plus(p, c, c, 0, w);
         }
      }
      return p;
   }

   /** Plus sign around c: arms from gap to len pixels away from the centre (gap 0 includes the centre). */
   private static void plus(Pic p, int c, int len, int gap, int color) {
      for (int i = -len; i <= len; i++) {
         if (Math.abs(i) < gap) {
            continue;
         }
         int x = c + i;
         if (x >= 0 && x < p.w) {
            p.set(x, c, color);
         }
         int y = c + i;
         if (y >= 0 && y < p.h) {
            p.set(c, y, color);
         }
      }
   }
}
