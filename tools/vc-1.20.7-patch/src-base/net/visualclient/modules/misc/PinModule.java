package net.visualclient.modules.misc;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.class_1297;
import net.minecraft.class_1569;
import net.minecraft.class_1657;
import net.minecraft.class_1675;
import net.minecraft.class_238;
import net.minecraft.class_239;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_3966;
import net.minecraft.class_4184;
import net.visualclient.compat.Compat;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.font.Fonts;
import net.visualclient.hud.Overlay;
import net.visualclient.modules.extra.SoundChoice;
import net.visualclient.util.ColorUtil;
import net.visualclient.util.Log;

/**
 * Fortnite-style pins: the pin key drops a flat 2D marker on the spot under the crosshair. Pins are drawn on the
 * HUD (not as 3D objects), show the distance, stay at the screen edge with an arrow when they are off screen and
 * fade out after a while. Double tap = danger pin, pressing the key on an existing pin removes it.
 */
public final class PinModule extends ClientModule implements Overlay {
   public static final PinModule INSTANCE = new PinModule();
   /** Field of view of the last rendered world frame, written by ZoomMixin (includes zoom and FOV effects). */
   public static volatile float worldFov = -1.0F;
   public static class_304 key;
   private static final long DOUBLE_TAP_MS = 350L;
   private static final int OUTLINE = 0xFF0E1117;
   private static Method cameraPosition;
   private static boolean cameraPositionSearched;
   private final ColorSetting pinColor = this.add(new ColorSetting("Pin color", 0xFFFFC83D, false));
   private final ColorSetting dangerColor = this.add(new ColorSetting("Danger color", 0xFFFF3B4E, false));
   private final BoolSetting doubleTap = this.add(new BoolSetting("Double tap = danger", true));
   private final BoolSetting enemyDanger = this.add(new BoolSetting("Pinned enemies = danger", true));
   private final NumberSetting duration = this.add(new NumberSetting("Duration", 30.0, 0.0, 300.0, 5.0).suffix(" s"));
   private final NumberSetting maxPins = this.add(new NumberSetting("Max pins", 5.0, 1.0, 20.0, 1.0));
   private final NumberSetting range = this.add(new NumberSetting("Range", 256.0, 16.0, 512.0, 16.0).suffix(" blocks"));
   private final NumberSetting size = this.add(new NumberSetting("Size", 1.0, 0.5, 2.0, 0.1).suffix("x"));
   private final BoolSetting showDistance = this.add(new BoolSetting("Show distance", true));
   private final BoolSetting edgePins = this.add(new BoolSetting("Show off-screen pins", true));
   private final BoolSetting sound = this.add(new BoolSetting("Sound", true));
   private final ActionSetting clear = this.add(new ActionSetting("Clear all pins", this::clearPins));
   private final List<PinModule.Pin> pins = new ArrayList<>();
   private Object lastLevel;
   private PinModule.Pin lastPlaced;
   private long lastPress;
   private int lastW = 1;
   private int lastH = 1;

   private PinModule() {
      super("Pins", "Press the pin key (default J, change it in Controls) to drop a 2D marker where you look. Double tap = danger, press on a pin to remove it.", Category.MISC);
      this.defaultEnabled(true);
      this.doubleTap.describe("Press the pin key twice quickly to turn the new pin into a red danger pin.");
      this.enemyDanger.describe("Pinning a player or a hostile mob creates a danger pin right away.");
      this.duration.describe("How long a pin stays. 0 = until you remove it.");
      this.maxPins.describe("The oldest pin disappears when you place more than this.");
      this.range.describe("How far away a pin can be placed.");
      this.edgePins.describe("Pins outside the screen stay at the screen edge with an arrow.");
      this.clear.describe("Removes every pin.");

      try {
         // J: M is "mute microphone" in Simple Voice Chat, K / O are Iris, B is F3+B
         key = KeyBindingHelper.registerKeyBinding(Compat.keyMapping("key.visualclient.pin", 74));
      } catch (Throwable var2) {
         Log.error("Could not register the pin key", var2);
      }
   }

   @Override
   protected void onEnable() {
      if (key != null) {
         while (key.method_1436()) {
         }
      }
   }

   @Override
   protected void onDisable() {
      this.clearPins();
   }

   private void clearPins() {
      this.pins.clear();
      this.lastPlaced = null;
   }

   private long lifeMs() {
      return (long)(this.duration.asDouble() * 1000.0);
   }

   @Override
   protected void tick() {
      class_310 mc = class_310.method_1551();
      Object level = mc.field_1687;
      if (level != this.lastLevel) {
         this.clearPins();
         this.lastLevel = level;
      }

      long now = System.currentTimeMillis();
      long life = this.lifeMs();
      if (life > 0L) {
         this.pins.removeIf(p -> now - p.born > life);
      }

      if (key != null) {
         while (key.method_1436()) {
            if (mc.field_1724 != null && level != null && Compat.screen(mc) == null) {
               this.press(mc, now);
            }
         }
      }
   }

   private void press(class_310 mc, long now) {
      if (this.doubleTap.on() && this.lastPlaced != null && now - this.lastPress <= DOUBLE_TAP_MS && this.pins.contains(this.lastPlaced)) {
         if (!this.lastPlaced.danger) {
            this.lastPlaced.danger = true;
            this.lastPlaced.born = now;
            this.lastPlaced.pulse = now;
            this.play(mc, "Bell", 0.5F, 1.35F);
         }

         this.lastPress = 0L;
      } else {
         this.lastPress = now;
         PinModule.Pin aimed = this.aimed();
         if (aimed != null) {
            this.pins.remove(aimed);
            if (aimed == this.lastPlaced) {
               this.lastPlaced = null;
            }

            this.play(mc, "Note Harp", 0.4F, 0.7F);
         } else {
            PinModule.Target t = this.target(mc);
            if (t == null) {
               this.lastPlaced = null;
               mc.field_1724.method_7353(class_2561.method_43470("Nothing to pin in range"), true);
            } else {
               while (!this.pins.isEmpty() && this.pins.size() >= this.maxPins.asInt()) {
                  this.pins.remove(0);
               }

               PinModule.Pin p = new PinModule.Pin(t.x(), t.y(), t.z(), t.danger(), now);
               this.pins.add(p);
               this.lastPlaced = p;
               if (t.danger()) {
                  this.play(mc, "Bell", 0.5F, 1.35F);
               } else {
                  this.play(mc, "Pling", 0.6F, 1.7F);
               }
            }
         }
      }
   }

   private PinModule.Pin aimed() {
      double cx = this.lastW / 2.0;
      double cy = this.lastH / 2.0;
      double best = 12.0 * this.size.asDouble();
      best *= best;
      PinModule.Pin found = null;

      for (PinModule.Pin p : this.pins) {
         if (p.onScreen) {
            double d = Math.min(sq(p.sx - cx) + sq(p.sy - cy), sq(p.ix - cx) + sq(p.iy - cy));
            if (d <= best) {
               best = d;
               found = p;
            }
         }
      }

      return found;
   }

   private PinModule.Target target(class_310 mc) {
      class_1297 cam = mc.method_1560();
      if (cam == null) {
         cam = mc.field_1724;
      }

      if (cam == null) {
         return null;
      } else {
         float pt = partialTick(mc);
         double reach = this.range.asDouble();
         class_243 eye = cam.method_5836(pt);
         class_243 look = cam.method_5828(pt);
         class_243 end = eye.method_1019(look.method_1021(reach));
         class_243 hit = null;
         double best = reach * reach;
         class_239 block = cam.method_5745(reach, pt, false);
         if (block != null && block.method_17783() != class_239.class_240.field_1333) {
            hit = block.method_17784();
            best = eye.method_1025(hit);
         }

         boolean danger = false;

         try {
            class_1297 self = cam;
            class_238 box = cam.method_5829().method_18804(look.method_1021(reach)).method_1014(1.0);
            class_3966 entityHit = class_1675.method_18075(cam, eye, end, box, e -> e != self && !e.method_7325() && e.method_5863(), best);
            if (entityHit != null) {
               class_1297 e = entityHit.method_17782();
               class_238 b = e.method_5829();
               hit = new class_243((b.field_1323 + b.field_1320) * 0.5, b.field_1325, (b.field_1321 + b.field_1324) * 0.5);
               danger = this.enemyDanger.on() && (e instanceof class_1657 || e instanceof class_1569);
            }
         } catch (Throwable var17) {
         }

         return hit == null ? null : new PinModule.Target(hit.field_1352, hit.field_1351, hit.field_1350, danger);
      }
   }

   private void play(class_310 mc, String sound, float volume, float pitch) {
      if (this.sound.on() && mc.field_1724 != null) {
         try {
            SoundChoice.play(sound, "", volume, pitch, mc.field_1724.method_23317(), mc.field_1724.method_23318(), mc.field_1724.method_23321());
         } catch (Throwable var6) {
         }
      }
   }

   @Override
   public void renderOverlay(class_332 g, class_310 mc) {
      int w = g.method_51421();
      int h = g.method_51443();
      this.lastW = w;
      this.lastH = h;
      if (!this.pins.isEmpty() && mc.field_1724 != null && !mc.field_1690.field_1842) {
         class_4184 cam = mc.field_1773.method_19418();
         class_243 cp = cameraPos(cam);
         if (cp == null) {
            cp = mc.field_1724.method_5836(partialTick(mc));
         }

         double yaw = Math.toRadians(cam.method_19330());
         double pitch = Math.toRadians(cam.method_19329());
         double fov = worldFov;
         if (!(fov > 1.0) || !(fov < 179.0)) {
            fov = optionsFov(mc);
         }

         double tan = Math.tan(Math.toRadians(fov) * 0.5);
         double fbw = Compat.framebufferWidth();
         double fbh = Compat.framebufferHeight();
         double aspect = fbw > 0.0 && fbh > 0.0 ? fbw / fbh : (double)w / Math.max(1, h);
         // camera basis: yaw 0 looks to +Z, positive pitch looks down; up = right x forward
         double fx = -Math.sin(yaw) * Math.cos(pitch);
         double fy = -Math.sin(pitch);
         double fz = Math.cos(yaw) * Math.cos(pitch);
         double rx = -Math.cos(yaw);
         double rz = -Math.sin(yaw);
         double ux = -rz * fy;
         double uy = rz * fx - rx * fz;
         double uz = rx * fy;
         double cx = w / 2.0;
         double cy = h / 2.0;
         long now = System.currentTimeMillis();
         long life = this.lifeMs();
         float scale = this.size.asFloat();
         double removeRadius = sq(12.0 * scale);
         class_327 font = mc.field_1772;

         for (PinModule.Pin p : this.pins) {
            p.onScreen = false;
            float alpha = alpha(p, now, life);
            if (!(alpha <= 0.01F)) {
               double dx = p.x - cp.field_1352;
               double dy = p.y - cp.field_1351;
               double dz = p.z - cp.field_1350;
               double xc = dx * rx + dz * rz;
               double yc = dx * ux + dy * uy + dz * uz;
               double zc = dx * fx + dy * fy + dz * fz;
               double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
               double pop = 1.0 + 0.6 * (1.0 - Math.min(1.0, (now - p.born) / 180.0));
               double r = 6.0 * scale * pop;
               if (p.danger) {
                  r *= 1.0 + 0.07 * Math.sin((now - p.pulse) / 110.0);
               }

               int color = p.danger ? this.dangerColor.argb() : this.pinColor.argb();
               boolean drawn = false;
               if (zc > 0.05) {
                  double sx = cx + xc / (zc * tan * aspect) * cx;
                  double sy = cy - yc / (zc * tan) * cy;
                  if (sx >= 0.0 && sx <= w && sy >= 0.0 && sy <= h) {
                     double stem = 5.0 * scale;
                     double ix = sx;
                     double iy = sy - r - stem - 1.0;
                     p.onScreen = true;
                     p.sx = sx;
                     p.sy = sy;
                     p.ix = ix;
                     p.iy = iy;
                     boolean aimed = Math.min(sq(sx - cx) + sq(sy - cy), sq(ix - cx) + sq(iy - cy)) <= removeRadius;
                     int ax = (int)Math.round(sx);
                     int ay = (int)Math.round(sy);
                     int top = (int)Math.round(iy + r);
                     if (ay - top > 1) {
                        g.method_25294(ax - 1, top, ax + 2, ay, ColorUtil.mulAlpha(OUTLINE, alpha * 0.6F));
                        g.method_25294(ax, top, ax + 1, ay, ColorUtil.mulAlpha(color, alpha * 0.9F));
                     }

                     g.method_25294(ax - 2, ay - 2, ax + 3, ay + 3, ColorUtil.mulAlpha(OUTLINE, alpha * 0.85F));
                     g.method_25294(ax - 1, ay - 1, ax + 2, ay + 2, ColorUtil.mulAlpha(color, alpha));
                     this.icon(g, p, ix, iy, r, color, alpha, aimed);
                     this.distance(g, font, dist, sx, sy + 4.0, w, h, alpha);
                     drawn = true;
                  }
               }

               if (!drawn && this.edgePins.on()) {
                  double ex = xc;
                  double ey = -yc;
                  double len = Math.sqrt(ex * ex + ey * ey);
                  if (len < 1.0E-6) {
                     ex = 0.0;
                     ey = 1.0;
                  } else {
                     ex /= len;
                     ey /= len;
                  }

                  double m = 16.0 * scale;
                  double tx = Math.abs(ex) < 1.0E-9 ? Double.MAX_VALUE : (ex > 0.0 ? w - m - cx : m - cx) / ex;
                  double ty = Math.abs(ey) < 1.0E-9 ? Double.MAX_VALUE : (ey > 0.0 ? h - m - cy : m - cy) / ey;
                  double t = Math.max(0.0, Math.min(tx, ty));
                  double ix = cx + ex * t;
                  double iy = cy + ey * t;
                  double tipD = r + 7.0 * scale;
                  double baseD = r + 2.0 * scale;
                  double half = 3.5 * scale;
                  double[] ax = new double[]{ix + ex * tipD, ix + ex * baseD - ey * half, ix + ex * baseD + ey * half};
                  double[] ay = new double[]{iy + ey * tipD, iy + ey * baseD + ex * half, iy + ey * baseD - ex * half};
                  double[] ox = new double[]{ix + ex * (tipD + 1.5), ix + ex * (baseD - 1.0) - ey * (half + 1.5), ix + ex * (baseD - 1.0) + ey * (half + 1.5)};
                  double[] oy = new double[]{iy + ey * (tipD + 1.5), iy + ey * (baseD - 1.0) + ex * (half + 1.5), iy + ey * (baseD - 1.0) - ex * (half + 1.5)};
                  fillPoly(g, ox, oy, ColorUtil.mulAlpha(OUTLINE, alpha * 0.85F));
                  fillPoly(g, ax, ay, ColorUtil.mulAlpha(color, alpha));
                  this.icon(g, p, ix, iy, r, color, alpha * 0.9F, false);
                  this.distance(g, font, dist, ix, iy + r + 3.0, w, h, alpha);
               }
            }
         }
      }
   }

   private void icon(class_332 g, PinModule.Pin p, double x, double y, double r, int color, float alpha, boolean aimed) {
      if (aimed) {
         diamond(g, x, y, r + 3.0, ColorUtil.mulAlpha(-1, alpha * 0.9F));
      }

      diamond(g, x, y, r + 1.6, ColorUtil.mulAlpha(OUTLINE, alpha * 0.9F));
      diamond(g, x, y, r, ColorUtil.mulAlpha(color, alpha));
      if (p.danger) {
         int ix = (int)Math.round(x);
         int barTop = (int)Math.round(y - r * 0.58);
         int barBottom = (int)Math.round(y + r * 0.14);
         int dotTop = (int)Math.round(y + r * 0.3);
         int dotBottom = Math.max(dotTop + 1, (int)Math.round(y + r * 0.56));
         int c = ColorUtil.mulAlpha(OUTLINE, alpha);
         g.method_25294(ix - 1, barTop, ix + 1, Math.max(barTop + 1, barBottom), c);
         g.method_25294(ix - 1, dotTop, ix + 1, dotBottom, c);
      } else {
         diamond(g, x, y, r * 0.52, ColorUtil.mulAlpha(OUTLINE, alpha * 0.8F));
         diamond(g, x, y, r * 0.26, ColorUtil.mulAlpha(-1, alpha));
      }
   }

   private void distance(class_332 g, class_327 font, double dist, double x, double y, int w, int h, float alpha) {
      if (this.showDistance.on() && !(alpha < 0.04F)) {
         String s = Math.round(dist) + "m";
         int tw = Fonts.width(font, s);
         int tx = (int)Math.round(x - tw / 2.0);
         tx = Math.max(2, Math.min(w - tw - 2, tx));
         int ty = Math.max(2, Math.min(h - 10, (int)Math.round(y)));
         Fonts.draw(g, font, s, tx, ty, ColorUtil.mulAlpha(-1, alpha), true);
      }
   }

   private static void diamond(class_332 g, double x, double y, double r, int color) {
      fillPoly(g, new double[]{x, x + r, x, x - r}, new double[]{y - r, y, y + r, y}, color);
   }

   /** Fills a convex polygon with 1px scanlines (DrawContext only has axis-aligned fills). */
   private static void fillPoly(class_332 g, double[] xs, double[] ys, int color) {
      if (color >>> 24 != 0) {
         double minY = ys[0];
         double maxY = ys[0];

         for (double y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
         }

         int n = xs.length;
         int y0 = (int)Math.floor(minY);
         int y1 = (int)Math.ceil(maxY);

         for (int row = y0; row < y1; row++) {
            double cyl = row + 0.5;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;

            for (int i = 0; i < n; i++) {
               double ax = xs[i];
               double ay = ys[i];
               double bx = xs[(i + 1) % n];
               double by = ys[(i + 1) % n];
               if (cyl >= ay && cyl < by || cyl >= by && cyl < ay) {
                  double ix = ax + (cyl - ay) * (bx - ax) / (by - ay);
                  lo = Math.min(lo, ix);
                  hi = Math.max(hi, ix);
               }
            }

            if (lo <= hi) {
               int l = (int)Math.round(lo);
               int r = (int)Math.round(hi);
               if (r > l) {
                  g.method_25294(l, row, r, row + 1, color);
               }
            }
         }
      }
   }

   private static float alpha(PinModule.Pin p, long now, long life) {
      float a = Math.min(1.0F, (float)(now - p.born) / 120.0F);
      if (life > 0L) {
         long left = life - (now - p.born);
         if (left < 1000L) {
            a = Math.min(a, Math.max(0.0F, (float)left / 1000.0F));
         }
      }

      return Math.max(0.0F, a);
   }

   private static double sq(double v) {
      return v * v;
   }

   private static float partialTick(class_310 mc) {
      try {
         return mc.method_61966().method_60637(false);
      } catch (Throwable var2) {
         return 1.0F;
      }
   }

   private static double optionsFov(class_310 mc) {
      try {
         Object v = mc.field_1690.method_41808().method_41753();
         if (v instanceof Number n) {
            return n.doubleValue();
         }
      } catch (Throwable var3) {
      }

      return 70.0;
   }

   /** Camera.getPosition() changed its name between Minecraft versions, so it is looked up once by its type. */
   private static class_243 cameraPos(class_4184 cam) {
      if (!cameraPositionSearched) {
         cameraPositionSearched = true;

         try {
            for (String name : new String[]{"method_19326", "method_71156", "position", "getPosition"}) {
               try {
                  Method m = cam.getClass().getMethod(name);
                  if (m.getReturnType() == class_243.class) {
                     cameraPosition = m;
                     break;
                  }
               } catch (NoSuchMethodException var7) {
               }
            }

            if (cameraPosition == null) {
               for (Method m : cam.getClass().getMethods()) {
                  if (m.getParameterCount() == 0 && m.getReturnType() == class_243.class && !Modifier.isStatic(m.getModifiers())) {
                     cameraPosition = m;
                     break;
                  }
               }
            }
         } catch (Throwable var8) {
            cameraPosition = null;
         }
      }

      try {
         return cameraPosition == null ? null : (class_243)cameraPosition.invoke(cam);
      } catch (Throwable var6) {
         return null;
      }
   }

   private static final class Pin {
      final double x;
      final double y;
      final double z;
      long born;
      long pulse;
      boolean danger;
      boolean onScreen;
      double sx;
      double sy;
      double ix;
      double iy;

      Pin(double x, double y, double z, boolean danger, long now) {
         this.x = x;
         this.y = y;
         this.z = z;
         this.danger = danger;
         this.born = now;
         this.pulse = now;
      }
   }

   private record Target(double x, double y, double z, boolean danger) {
   }
}
