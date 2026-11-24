package net.visualstyles.style;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.class_1011;

/**
 * A small ARGB image (0xAARRGGBB per pixel). Decoding and encoding go through Minecraft's
 * NativeImage (class_1011), so the colours are exact for every PNG type.
 */
public final class Pic {
   public final int w;
   public final int h;
   public final int[] px;

   public Pic(int w, int h) {
      this(w, h, new int[w * h]);
   }

   public Pic(int w, int h, int[] px) {
      this.w = w;
      this.h = h;
      this.px = px;
   }

   /** NativeImage.read(InputStream) -> ARGB pixels. */
   public static Pic read(InputStream in) throws IOException {
      try (class_1011 img = class_1011.method_4309(in)) {
         int w = img.method_4307();
         int h = img.method_4323();
         int[] px = new int[w * h];
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               px[y * w + x] = img.method_61940(x, y);
            }
         }
         return new Pic(w, h, px);
      }
   }

   public static Pic read(Path file) throws IOException {
      try (InputStream in = Files.newInputStream(file)) {
         return read(in);
      }
   }

   /** New NativeImage with these pixels (the caller owns it). */
   public class_1011 toNative() {
      class_1011 img = new class_1011(this.w, this.h, false);
      for (int y = 0; y < this.h; y++) {
         for (int x = 0; x < this.w; x++) {
            img.method_61941(x, y, this.px[y * this.w + x]);
         }
      }
      return img;
   }

   public void write(Path file) throws IOException {
      Files.createDirectories(file.getParent());
      try (class_1011 img = this.toNative()) {
         img.method_4314(file);
      }
   }

   public Pic copy() {
      return new Pic(this.w, this.h, this.px.clone());
   }

   public int get(int x, int y) {
      return this.px[y * this.w + x];
   }

   public void set(int x, int y, int c) {
      this.px[y * this.w + x] = c;
   }

   /** Height of one animation frame: vertical strips (16x512 fire) hold square frames. */
   public int frameHeight() {
      return this.h > this.w && this.h % this.w == 0 ? this.w : this.h;
   }

   public Pic crop(int x, int y, int cw, int ch) {
      Pic out = new Pic(cw, ch);
      for (int yy = 0; yy < ch; yy++) {
         for (int xx = 0; xx < cw; xx++) {
            int sx = x + xx;
            int sy = y + yy;
            if (sx >= 0 && sy >= 0 && sx < this.w && sy < this.h) {
               out.px[yy * cw + xx] = this.px[sy * this.w + sx];
            }
         }
      }
      return out;
   }

   /** Nearest-neighbour resize (keeps the pixel-art look). */
   public Pic scaled(int nw, int nh) {
      Pic out = new Pic(nw, nh);
      for (int y = 0; y < nh; y++) {
         int sy = Math.min(this.h - 1, y * this.h / nh);
         for (int x = 0; x < nw; x++) {
            int sx = Math.min(this.w - 1, x * this.w / nw);
            out.px[y * nw + x] = this.px[sy * this.w + sx];
         }
      }
      return out;
   }

   /** Draws src over this image at (dx, dy) with alpha blending. */
   public void blend(Pic src, int dx, int dy) {
      for (int y = 0; y < src.h; y++) {
         int ty = dy + y;
         if (ty < 0 || ty >= this.h) {
            continue;
         }
         for (int x = 0; x < src.w; x++) {
            int tx = dx + x;
            if (tx < 0 || tx >= this.w) {
               continue;
            }
            int s = src.px[y * src.w + x];
            int sa = s >>> 24;
            if (sa == 0) {
               continue;
            }
            int i = ty * this.w + tx;
            this.px[i] = sa == 255 ? s : Col.over(s, this.px[i]);
         }
      }
   }

   /** Centers this image on a transparent square canvas (for previews). */
   public Pic padSquare(int size) {
      Pic out = new Pic(size, size);
      Pic src = this.w > size || this.h > size ? this.scaled(Math.min(size, this.w), Math.min(size, this.h)) : this;
      out.blend(src, (size - src.w) / 2, (size - src.h) / 2);
      return out;
   }
}
