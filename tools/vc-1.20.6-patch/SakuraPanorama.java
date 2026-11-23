import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Makes the Visual Client sakura title panorama prettier. Works in direction space, so the six cube faces stay
 * seamless: every output pixel is a view direction, the old panorama is sampled along it, then
 *  - the night sky is replaced by a deep indigo -> violet gradient with a milky way, more stars and a moon halo,
 *  - the scene is colour graded (richer sakura pinks, cool blue shadows, warm lantern light),
 *  - bright lights (lanterns, moon) get a soft bloom,
 *  - falling sakura petals and warm fireflies are added.
 * Face layout and orientation are the ones Minecraft's CubeMap uses (y = -1 is up).
 * Usage: SakuraPanorama <inDir> <outDir> [size]
 */
public class SakuraPanorama {
   static int N;
   static float[][][] src = new float[6][][];
   static int SRC;
   static float[][] bright;
   static int BR = 256;

   public static void main(String[] a) throws Exception {
      N = a.length > 2 ? Integer.parseInt(a[2]) : 1024;
      for (int f = 0; f < 6; f++) {
         BufferedImage img = ImageIO.read(new File(a[0], "panorama_" + f + ".png"));
         SRC = img.getWidth();
         src[f] = new float[3][SRC * SRC];
         for (int y = 0; y < SRC; y++) {
            for (int x = 0; x < SRC; x++) {
               int c = img.getRGB(x, y);
               src[f][0][y * SRC + x] = lin((c >> 16 & 255) / 255f);
               src[f][1][y * SRC + x] = lin((c >> 8 & 255) / 255f);
               src[f][2][y * SRC + x] = lin((c & 255) / 255f);
            }
         }
      }
      // the moon: brightest spot well above the horizon
      float best = -1;
      for (int f = 0; f < 6; f++) {
         for (int y = 0; y < SRC; y += 2) {
            for (int x = 0; x < SRC; x += 2) {
               float[] d = dir(f, (x + 0.5f) / SRC, (y + 0.5f) / SRC);
               if (-d[1] < 0.15f) continue;
               int i = y * SRC + x;
               float l = 0.2126f * src[f][0][i] + 0.7152f * src[f][1][i] + 0.0722f * src[f][2][i];
               if (l > best) { best = l; MOON = d; }
            }
         }
      }
      System.out.println("moon at " + MOON[0] + " " + MOON[1] + " " + MOON[2] + " lum " + best);
      buildBrightMap();
      Petal[] petals = petals(1100, 7L);
      Petal[] flies = fireflies(90, 11L);
      for (int f = 0; f < 6; f++) {
         float[][] out = new float[3][N * N];
         for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
               float[] d = dir(f, (x + 0.5f) / N, (y + 0.5f) / N);
               float[] c = shade(d);
               int i = y * N + x;
               out[0][i] = c[0];
               out[1][i] = c[1];
               out[2][i] = c[2];
            }
         }
         for (Petal p : flies) {
            drawFirefly(out, f, p);
         }
         for (Petal p : petals) {
            drawPetal(out, f, p);
         }
         BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
         for (int i = 0; i < N * N; i++) {
            int r = toByte(out[0][i]);
            int g = toByte(out[1][i]);
            int b = toByte(out[2][i]);
            img.setRGB(i % N, i / N, r << 16 | g << 8 | b);
         }
         new File(a[1]).mkdirs();
         ImageIO.write(img, "png", new File(a[1], "panorama_" + f + ".png"));
         System.out.println("face " + f + " done");
      }
   }

   // ---------------------------------------------------------------- cube mapping (Minecraft CubeMap layout)

   static float[] dir(int f, float u, float v) {
      float x;
      float y;
      float z;
      switch (f) {
         case 0 -> { x = -1 + 2 * u; y = -1 + 2 * v; z = 1; }
         case 1 -> { x = 1; y = -1 + 2 * v; z = 1 - 2 * u; }
         case 2 -> { x = 1 - 2 * u; y = -1 + 2 * v; z = -1; }
         case 3 -> { x = -1; y = -1 + 2 * v; z = -1 + 2 * u; }
         case 4 -> { x = -1 + 2 * u; y = -1; z = -1 + 2 * v; }
         default -> { x = -1 + 2 * u; y = 1; z = 1 - 2 * v; }
      }
      float l = (float)Math.sqrt(x * x + y * y + z * z);
      return new float[]{x / l, y / l, z / l};
   }

   /** direction -> {face, u, v} */
   static float[] face(float x, float y, float z) {
      float ax = Math.abs(x);
      float ay = Math.abs(y);
      float az = Math.abs(z);
      if (az >= ax && az >= ay) {
         return z > 0 ? new float[]{0, (x / az + 1) / 2, (y / az + 1) / 2} : new float[]{2, (1 - x / az) / 2, (y / az + 1) / 2};
      } else if (ax >= ay) {
         return x > 0 ? new float[]{1, (1 - z / ax) / 2, (y / ax + 1) / 2} : new float[]{3, (z / ax + 1) / 2, (y / ax + 1) / 2};
      } else {
         return y < 0 ? new float[]{4, (x / ay + 1) / 2, (z / ay + 1) / 2} : new float[]{5, (x / ay + 1) / 2, (1 - z / ay) / 2};
      }
   }

   static float[] sample(float[][][] cube, int res, float[] d) {
      float[] fu = face(d[0], d[1], d[2]);
      float[][] img = cube[(int)fu[0]];
      float px = fu[1] * res - 0.5f;
      float py = fu[2] * res - 0.5f;
      int x0 = clamp((int)Math.floor(px), 0, res - 1);
      int y0 = clamp((int)Math.floor(py), 0, res - 1);
      int x1 = Math.min(x0 + 1, res - 1);
      int y1 = Math.min(y0 + 1, res - 1);
      float tx = Math.max(0, Math.min(1, px - (float)Math.floor(px)));
      float ty = Math.max(0, Math.min(1, py - (float)Math.floor(py)));
      float[] c = new float[img.length];
      for (int k = 0; k < img.length; k++) {
         float a0 = img[k][y0 * res + x0] * (1 - tx) + img[k][y0 * res + x1] * tx;
         float a1 = img[k][y1 * res + x0] * (1 - tx) + img[k][y1 * res + x1] * tx;
         c[k] = a0 * (1 - ty) + a1 * ty;
      }
      return c;
   }

   // ---------------------------------------------------------------- bloom source: bright lights only

   static float[][][] brightCube = new float[6][][];

   static void buildBrightMap() {
      for (int f = 0; f < 6; f++) {
         brightCube[f] = new float[3][BR * BR];
         for (int y = 0; y < BR; y++) {
            for (int x = 0; x < BR; x++) {
               float[] d = dir(f, (x + 0.5f) / BR, (y + 0.5f) / BR);
               float[] c = sample(src, SRC, d);
               float l = lum(c);
               float k = smooth(0.45f, 0.9f, l);
               int i = y * BR + x;
               brightCube[f][0][i] = c[0] * k;
               brightCube[f][1][i] = c[1] * k;
               brightCube[f][2][i] = c[2] * k;
            }
         }
      }
   }

   static float[] bloom(float[] d) {
      float[] t1 = tangent(d);
      float[] t2 = cross(d, t1);
      float[] acc = new float[3];
      float wsum = 0;
      float[] radii = {0.006f, 0.016f, 0.034f, 0.06f};
      float[] weights = {1.0f, 0.7f, 0.42f, 0.2f};
      for (int r = 0; r < radii.length; r++) {
         for (int k = 0; k < 10; k++) {
            double ang = (k + r * 0.37) * Math.PI * 2 / 10;
            float ox = (float)Math.cos(ang) * radii[r];
            float oy = (float)Math.sin(ang) * radii[r];
            float[] q = norm(new float[]{d[0] + t1[0] * ox + t2[0] * oy, d[1] + t1[1] * ox + t2[1] * oy, d[2] + t1[2] * ox + t2[2] * oy});
            float[] c = sample(brightCube, BR, q);
            acc[0] += c[0] * weights[r];
            acc[1] += c[1] * weights[r];
            acc[2] += c[2] * weights[r];
            wsum += weights[r];
         }
      }
      return new float[]{acc[0] / wsum, acc[1] / wsum, acc[2] / wsum};
   }

   // ---------------------------------------------------------------- shading

   static float[] MOON = {0, -1, 0};

   static float[] shade(float[] d) {
      float[] c = sample(src, SRC, d);
      float elev = -d[1];
      // sky mask: dark blue pixels above the horizon
      float l = lum(c);
      float blue = c[2] - Math.max(c[0], c[1]) * 0.92f;
      float sky = smooth(0.0f, 0.012f, blue) * (1 - smooth(0.16f, 0.32f, l)) * smooth(0.02f, 0.12f, elev);
      float[] sk = sky(d);
      // keep the old moon and its stars as highlights
      float moonOld = smooth(0.5f, 0.85f, l);
      float[] s = mix(sk, add(sk, scale(c, 1.4f)), moonOld);
      float[] out = mix(c, s, sky);
      out = grade(out, sky);
      // bloom on lanterns and moon
      float[] b = bloom(d);
      float[] lightTint = {1.0f, 0.62f, 0.36f};
      out = add(out, new float[]{b[0] * 0.62f * lightTint[0] + b[0] * 0.1f, b[1] * 0.55f * lightTint[1] + b[1] * 0.1f, b[2] * 0.5f * lightTint[2] + b[2] * 0.16f});
      // moon halo
      float md = dot(d, MOON);
      float halo = (float)Math.pow(Math.max(0, md), 3500) * 0.35f + (float)Math.pow(Math.max(0, md), 160) * 0.05f;
      out = add(out, new float[]{halo * 0.75f, halo * 0.8f, halo * 1.0f});
      return out;
   }

   static float[] sky(float[] d) {
      float elev = Math.max(0, -d[1]);
      float[] horizon = {0.20f, 0.085f, 0.24f};
      float[] mid = {0.035f, 0.032f, 0.13f};
      float[] zenith = {0.006f, 0.008f, 0.045f};
      float[] c = elev < 0.35f ? mix(horizon, mid, smooth(0, 0.35f, elev)) : mix(mid, zenith, smooth(0.35f, 1, elev));
      // milky way: a soft band along a tilted great circle
      float[] axis = norm(new float[]{0.35f, -0.25f, 0.9f});
      float band = Math.abs(dot(d, axis));
      float mw = (float)Math.exp(-band * band * 42) * (0.55f + 0.45f * fbm(d[0] * 6, d[1] * 6, d[2] * 6));
      c = add(c, new float[]{mw * 0.055f, mw * 0.045f, mw * 0.09f});
      // stars: one random star per small direction cell
      float density = 170;
      int cx = (int)Math.floor(d[0] * density);
      int cy = (int)Math.floor(d[1] * density);
      int cz = (int)Math.floor(d[2] * density);
      long h = hash(cx, cy, cz);
      float r1 = (h & 0xFFFF) / 65535f;
      if (r1 > 0.982f) {
         float sx = ((h >> 16 & 0xFF) / 255f + cx) / density;
         float sy = ((h >> 24 & 0xFF) / 255f + cy) / density;
         float sz = ((h >> 32 & 0xFF) / 255f + cz) / density;
         float[] sp = norm(new float[]{sx, sy, sz});
         float dd = 1 - dot(d, sp);
         float t = (r1 - 0.982f) / 0.018f; float size = 0.7e-6f + t * t * 3.2e-6f;
         float star = (float)Math.exp(-dd / size) * (0.12f + 0.75f * t * t + 0.13f * (h >> 40 & 0xFF) / 255f) * smooth(0.02f, 0.2f, elev);
         float tint = (h >> 48 & 0xFF) / 255f;
         c = add(c, new float[]{star * (0.85f + 0.15f * tint), star * 0.88f, star * (1.0f - 0.2f * tint + 0.2f)});
      }
      return c;
   }

   /** Night colour grade: richer sakura pinks, cool shadows, a bit more contrast. */
   static float[] grade(float[] c, float sky) {
      float l = lum(c);
      float pink = smooth(0.0f, 0.06f, c[0] - c[1]) * smooth(0.0f, 0.05f, c[2] - c[1] * 0.9f);
      float[] out = c.clone();
      // saturate and lift pinks
      float sat = 1 + 0.55f * pink;
      out[0] = l + (out[0] - l) * sat;
      out[1] = l + (out[1] - l) * sat;
      out[2] = l + (out[2] - l) * sat;
      out = scale(out, 1 + 0.25f * pink);
      out = add(out, new float[]{0.010f * pink, 0.0f, 0.006f * pink});
      // cool blue-violet shadows (not on the sky)
      float shadow = (1 - smooth(0.0f, 0.12f, l)) * (1 - sky);
      out = add(out, new float[]{0.004f * shadow, 0.006f * shadow, 0.022f * shadow});
      // gentle contrast curve
      for (int k = 0; k < 3; k++) {
         float v = Math.max(0, out[k]);
         out[k] = v * (1.0f + 0.25f * smooth(0.02f, 0.5f, v));
      }
      return out;
   }

   // ---------------------------------------------------------------- petals and fireflies

   record Petal(float[] d, float[] e1, float[] e2, float len, float wid, float[] col, float alpha, boolean glow) {
   }

   static Petal[] petals(int n, long seed) {
      Random r = new Random(seed);
      Petal[] out = new Petal[n];
      for (int i = 0; i < n; i++) {
         // directions mostly between a bit below the horizon and high up
         float[] d;
         do {
            d = norm(new float[]{(float)r.nextGaussian(), (float)r.nextGaussian(), (float)r.nextGaussian()});
         } while (-d[1] < -0.35f || -d[1] > 0.75f);
         float[] t1 = tangent(d);
         float[] t2 = cross(d, t1);
         double rot = r.nextDouble() * Math.PI * 2;
         float[] e1 = norm(add(scale(t1, (float)Math.cos(rot)), scale(t2, (float)Math.sin(rot))));
         float[] e2 = cross(d, e1);
         float near = (float)Math.pow(r.nextDouble(), 3.2);
         float len = 0.0018f + near * 0.0105f;
         float wid = len * (0.55f + r.nextFloat() * 0.15f);
         float tint = r.nextFloat();
         float[] col = mix(new float[]{1.0f, 0.62f, 0.78f}, new float[]{1.0f, 0.86f, 0.92f}, tint);
         float alpha = 0.55f + 0.4f * r.nextFloat() - near * 0.15f;
         out[i] = new Petal(d, e1, e2, len, wid, scale(lin3(col), 0.55f + 0.35f * r.nextFloat()), alpha, false);
      }
      return out;
   }

   static Petal[] fireflies(int n, long seed) {
      Random r = new Random(seed);
      Petal[] out = new Petal[n];
      for (int i = 0; i < n; i++) {
         float[] d;
         do {
            d = norm(new float[]{(float)r.nextGaussian(), (float)r.nextGaussian(), (float)r.nextGaussian()});
         } while (-d[1] < -0.22f || -d[1] > 0.12f);
         float[] t1 = tangent(d);
         float size = 0.0012f + r.nextFloat() * 0.0016f;
         out[i] = new Petal(d, t1, cross(d, t1), size, size, lin3(new float[]{1.0f, 0.82f, 0.45f}), 0.7f + 0.3f * r.nextFloat(), true);
      }
      return out;
   }

   static void drawPetal(float[][] img, int f, Petal p) {
      raster(img, f, p, p.len * 1.3f, (a, b) -> {
         // sakura petal: rounded oval with a small notch at the outer tip
         float x = a / p.len;
         float y = b / p.wid;
         float e = x * x + y * y;
         if (e > 1) {
            return 0f;
         }
         float notch = (x > 0.55f && Math.abs(y) < 0.22f * (x - 0.45f) / 0.55f + 0.02f) ? 1f : 0f;
         if (notch > 0 && x > 0.72f) {
            return 0f;
         }
         return smooth(1.0f, 0.78f, e);
      }, (a, b) -> {
         float x = a / p.len;
         float k = 0.5f + 0.5f * x;
         return mix(scale(p.col, 1.18f), scale(p.col, 0.82f), k);
      });
   }

   static void drawFirefly(float[][] img, int f, Petal p) {
      raster(img, f, p, p.len * 6, (a, b) -> {
         float r = (float)Math.sqrt(a * a + b * b) / p.len;
         return (float)(Math.exp(-r * r * 1.6) + 0.22 * Math.exp(-r * 0.9));
      }, (a, b) -> p.col);
   }

   interface Shape { float at(float a, float b); }
   interface Paint { float[] at(float a, float b); }

   /** Rasterises a small sprite around direction p.d on face f; every pixel is tested in direction space. */
   static void raster(float[][] img, int f, Petal p, float radius, Shape shape, Paint paint) {
      // only faces that can see this direction
      float[] fu = face(p.d[0], p.d[1], p.d[2]);
      float[] center = null;
      if ((int)fu[0] == f) {
         center = new float[]{fu[1], fu[2]};
      } else {
         // the sprite may spill over the edge of a neighbouring face: test the face's centre direction
         float[] fc = dir(f, 0.5f, 0.5f);
         if (dot(fc, p.d) < 0.55f) {
            return;
         }
         float[] proj = project(f, p.d);
         if (proj == null) {
            return;
         }
         center = proj;
      }
      int cx = (int)(center[0] * N);
      int cy = (int)(center[1] * N);
      int rp = (int)Math.ceil(radius * N * 0.75f) + 2;
      for (int y = Math.max(0, cy - rp); y < Math.min(N, cy + rp); y++) {
         for (int x = Math.max(0, cx - rp); x < Math.min(N, cx + rp); x++) {
            float[] d = dir(f, (x + 0.5f) / N, (y + 0.5f) / N);
            if (dot(d, p.d) < 0.98f) {
               continue;
            }
            float a = dot(d, p.e1);
            float b = dot(d, p.e2);
            float k = shape.at(a, b) * p.alpha;
            if (k <= 0.001f) {
               continue;
            }
            float[] c = paint.at(a, b);
            int i = y * N + x;
            if (p.glow) {
               img[0][i] += c[0] * k;
               img[1][i] += c[1] * k;
               img[2][i] += c[2] * k;
            } else {
               k = Math.min(1, k);
               img[0][i] = img[0][i] * (1 - k) + c[0] * k;
               img[1][i] = img[1][i] * (1 - k) + c[1] * k;
               img[2][i] = img[2][i] * (1 - k) + c[2] * k;
            }
         }
      }
   }

   /** Where direction d meets the plane of face f, as {u, v} (may be slightly outside 0..1). */
   static float[] project(int f, float[] d) {
      float x = d[0];
      float y = d[1];
      float z = d[2];
      switch (f) {
         case 0: if (z <= 0) return null; return new float[]{(x / z + 1) / 2, (y / z + 1) / 2};
         case 2: if (z >= 0) return null; return new float[]{(1 - x / -z) / 2, (y / -z + 1) / 2};
         case 1: if (x <= 0) return null; return new float[]{(1 - z / x) / 2, (y / x + 1) / 2};
         case 3: if (x >= 0) return null; return new float[]{(z / -x + 1) / 2, (y / -x + 1) / 2};
         case 4: if (y >= 0) return null; return new float[]{(x / -y + 1) / 2, (z / -y + 1) / 2};
         default: if (y <= 0) return null; return new float[]{(x / y + 1) / 2, (1 - z / y) / 2};
      }
   }

   // ---------------------------------------------------------------- small maths

   static float lin(float v) { return (float)Math.pow(v, 2.2); }
   static float[] lin3(float[] c) { return new float[]{lin(c[0]), lin(c[1]), lin(c[2])}; }
   static int toByte(float v) {
      // soft shoulder so bright lights do not clip hard
      float t = v / (1 + v * 0.18f);
      return clamp(Math.round((float)Math.pow(Math.max(0, t), 1 / 2.2) * 255), 0, 255);
   }
   static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
   static float lum(float[] c) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; }
   static float smooth(float a, float b, float x) {
      float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
      return t * t * (3 - 2 * t);
   }
   static float[] mix(float[] a, float[] b, float t) { return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}; }
   static float[] add(float[] a, float[] b) { return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
   static float[] scale(float[] a, float k) { return new float[]{a[0] * k, a[1] * k, a[2] * k}; }
   static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
   static float[] cross(float[] a, float[] b) { return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
   static float[] norm(float[] a) { float l = (float)Math.sqrt(dot(a, a)); return new float[]{a[0] / l, a[1] / l, a[2] / l}; }
   static float[] tangent(float[] d) { return norm(cross(Math.abs(d[1]) < 0.9f ? new float[]{0, 1, 0} : new float[]{1, 0, 0}, d)); }
   static long hash(int x, int y, int z) {
      long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
      h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
      return h;
   }
   static float noise(float x, float y, float z) {
      int xi = (int)Math.floor(x), yi = (int)Math.floor(y), zi = (int)Math.floor(z);
      float xf = x - xi, yf = y - yi, zf = z - zi;
      float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
      float r = 0;
      for (int k = 0; k < 8; k++) {
         int dx = k & 1, dy = k >> 1 & 1, dz = k >> 2 & 1;
         float val = (hash(xi + dx, yi + dy, zi + dz) & 0xFFFF) / 65535f;
         r += val * (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
      }
      return r;
   }
   static float fbm(float x, float y, float z) {
      return 0.55f * noise(x, y, z) + 0.3f * noise(x * 2.1f, y * 2.1f, z * 2.1f) + 0.15f * noise(x * 4.3f, y * 4.3f, z * 4.3f);
   }
}
