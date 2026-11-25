package net.visualclient.modules.extra;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.hud.Overlay;

/**
 * Aurora Sky: glowing aurora curtains over the night sky, in the colours of the Visual Client website
 * (violet and cyan), as northern lights (green and violet) or in your own two colours.
 *
 * The curtains hang at fixed directions in the sky (like the sun and the moon) and are projected onto the
 * screen every frame with the camera's direction and field of view (the same projection as the Pins module),
 * so they stay in place when you look around. They are drawn as soft vertical gradients from the HUD layer:
 * terrain, trees and buildings within 160 blocks hide the lower part of a curtain (the heightmap of the
 * loaded chunks gives the terrain's horizon in every direction), and the aurora fades out under a roof,
 * underwater, in rain, during the day ("Only at night") and outside the Overworld.
 */
public final class AuroraSkyModule extends ClientModule implements Overlay {

	public static final AuroraSkyModule INSTANCE = new AuroraSkyModule();

	private final ModeSetting colors = this.add(new ModeSetting("Colors", "Visual", "Visual", "Northern Lights", "Custom"));
	private final ColorSetting lower = this.add(new ColorSetting("Lower color", 0xFF38BDF8, false));
	private final ColorSetting upper = this.add(new ColorSetting("Upper color", 0xFF8B5CF6, false));
	private final NumberSetting brightness = this.add(new NumberSetting("Brightness", 75.0, 10.0, 100.0, 5.0).suffix("%"));
	private final NumberSetting height = this.add(new NumberSetting("Curtain height", 30.0, 12.0, 60.0, 2.0).suffix("°"));
	private final NumberSetting speed = this.add(new NumberSetting("Speed", 1.0, 0.2, 3.0, 0.1).suffix("x"));
	private final BoolSetting nightOnly = this.add(new BoolSetting("Only at night", true));
	private final BoolSetting terrain = this.add(new BoolSetting("Hide behind terrain", true));

	/** Curtain columns all around the sky (0.5 degrees each). */
	private static final int COLUMNS = 720;
	private static final double STEP = Math.PI * 2.0 / COLUMNS;
	/** Terrain horizon in 240 directions (radians above the eye), refreshed twice a second or after moving. */
	private static final int HORIZON = 240;
	private final double[] horizon = new double[HORIZON];
	/** The two layers: base offset (degrees), height factor, phase, intensity. */
	private static final double[][] LAYERS = {{0.0, 1.0, 0.0, 1.0}, {7.0, 0.75, 2.1, 0.55}};
	private long horizonAt;
	private double hx = Double.NaN, hy, hz;
	private float visible;
	private long lastFrame;

	private AuroraSkyModule() {
		super("Aurora Sky", "Glowing aurora curtains in the night sky, in the website's violet and cyan or as northern lights.", Category.EXTRA);
	}

	@Override
	public void renderOverlay(GG g, Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		long now = System.nanoTime();
		float dt = this.lastFrame == 0L ? 0.0F : Math.min(0.25F, (now - this.lastFrame) / 1.0E9F);
		this.lastFrame = now;
		if (p == null || level == null) {
			this.visible = 0.0F;
			return;
		}
		double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();

		// how visible the aurora should be right now
		float target = 1.0F;
		if (level.dimension() != Level.OVERWORLD) target = 0.0F;
		else {
			if (this.nightOnly.on()) target *= night(TIME);
			try {
				target *= 1.0F - Math.min(1.0F, level.getRainLevel(1.0F));
			} catch (Throwable ignored) {
			}
			if (p.isUnderWater()) target = 0.0F;
			else if (this.terrain.on()) {
				int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(ex), (int) Math.floor(ez));
				if (roof > ey + 0.2) target = 0.0F;
			}
		}
		this.visible += (target - this.visible) * Math.min(1.0F, dt * 1.5F);
		if (this.visible < 0.01F) return;

		if (this.terrain.on()) this.updateHorizon(level, ex, ey, ez);

		int w = g.guiWidth();
		int h = g.guiHeight();
		var cam = mc.gameRenderer.CAMERA;
		double yaw = Math.toRadians(cam.YAW);
		double pitch = Math.toRadians(cam.PITCH);
		double fov = net.visualclient.modules.misc.PinModule.worldFov;
		if (!(fov > 1.0) || !(fov < 179.0)) fov = optionsFov(mc);
		double tan = Math.tan(Math.toRadians(fov) * 0.5);
		double fbw = net.visualclient.compat.Compat.framebufferWidth();
		double fbh = net.visualclient.compat.Compat.framebufferHeight();
		double aspect = fbw > 0.0 && fbh > 0.0 ? fbw / fbh : (double) w / Math.max(1, h);

		// camera basis (yaw 0 looks to +Z, positive pitch looks down)
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		double cx = w / 2.0, cy = h / 2.0;
		double kx = cx / (tan * aspect), ky = cy / tan;
		int bottomLimit = h - 46; // keep the hotbar area free

		double t = (System.currentTimeMillis() % 3_600_000L) / 1000.0 * this.speed.asDouble();
		double hDeg = this.height.asDouble();
		float bright = (float) (this.brightness.asDouble() / 100.0) * this.visible;
		int cLow, cHigh;
		if (this.colors.is("Northern Lights")) {
			cLow = 0x4ADE80;
			cHigh = 0xA855F7;
		} else if (this.colors.is("Custom")) {
			cLow = this.lower.rgb();
			cHigh = this.upper.rgb();
		} else {
			cLow = 0x38BDF8;
			cHigh = 0x8B5CF6;
		}
		int cMid = mix(cLow, cHigh, 0.5F);

		double[] sx = new double[2];
		double[] sy = new double[2];
		for (int layer = 0; layer < LAYERS.length; layer++) {
			double off = LAYERS[layer][0], hMul = LAYERS[layer][1], ph = LAYERS[layer][2], lMul = LAYERS[layer][3];
			int lowC = layer == 0 ? cLow : cMid;
			int midC = mix(lowC, cHigh, 0.5F);
			int rimC = layer == 0 ? 0xFFFFFF : lowC;
			for (int i = 0; i < COLUMNS; i++) {
				double th = i * STEP;
				// skip directions behind the camera early (direction at 35 degrees elevation)
				double qx = -Math.sin(th) * 0.82, qz = Math.cos(th) * 0.82;
				if (qx * fx + 0.57 * fy + qz * fz < -0.35) continue;
				double base = Math.toRadians(18.0 + off + 5.0 * Math.sin(2.0 * th + 0.07 * t + ph) + 3.0 * Math.sin(5.0 * th - 0.11 * t + ph));
				double len = Math.toRadians(hDeg * hMul * (0.72 + 0.28 * Math.sin(3.0 * th + 0.05 * t + ph)));
				double fold = 0.5 + 0.5 * Math.sin(4.0 * th - 0.13 * t + ph);
				double rays = 0.62 + 0.38 * Math.sin(37.0 * th + 0.5 * t + ph) * Math.sin(23.0 * th - 0.3 * t);
				double band = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(th + 0.02 * t + ph));
				float inten = (float) (band * (0.35 + 0.65 * fold) * rays * lMul);
				if (inten < 0.04F) continue;
				double top = base + len;
				double low = base;
				if (this.terrain.on()) low = Math.max(low, this.horizon[i * HORIZON / COLUMNS] + Math.toRadians(0.4));
				if (low >= top - Math.toRadians(1.0)) continue;

				// x extents of the column at its lower edge
				if (!project(th - STEP * 0.5, low, fx, fy, fz, rx, rz, ux, uy, uz, cx, cy, kx, ky, sx, sy, 0)) continue;
				if (!project(th + STEP * 0.5, low, fx, fy, fz, rx, rz, ux, uy, uz, cx, cy, kx, ky, sx, sy, 1)) continue;
				int x0 = (int) Math.floor(Math.min(sx[0], sx[1]));
				int x1 = (int) Math.ceil(Math.max(sx[0], sx[1]));
				if (x1 <= 0 || x0 >= w || x1 - x0 > w / 3) continue;
				if (x1 == x0) x1 = x0 + 1;
				double yLow = (sy[0] + sy[1]) * 0.5;
				double ySplit = projectY(th, low + (top - low) * 0.3, fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				double yTop = projectY(th, top, fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				if (Double.isNaN(ySplit) || Double.isNaN(yTop)) continue;

				float a = bright * inten;
				// bright lower part, long fading upper part, thin glowing rim at the bottom
				fillV(g, x0, x1, ySplit, yLow, argb(midC, a * 0.7F), argb(lowC, a), bottomLimit);
				fillV(g, x0, x1, yTop, ySplit, argb(cHigh, 0.0F), argb(midC, a * 0.7F), bottomLimit);
				double rimTop = projectY(th, low + Math.toRadians(1.5), fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				if (!Double.isNaN(rimTop)) fillV(g, x0, x1, rimTop, yLow, argb(lowC, 0.0F), argb(rimC, Math.min(1.0F, a * 0.9F)), bottomLimit);
			}
		}
	}

	/** Vertical gradient between two screen heights (either order), kept above the hotbar. */
	private static void fillV(GG g, int x0, int x1, double ya, double yb, int ca, int cb, int bottomLimit) {
		int top, bottom, cTop, cBottom;
		if (ya <= yb) {
			top = (int) Math.round(ya); bottom = (int) Math.round(yb); cTop = ca; cBottom = cb;
		} else {
			top = (int) Math.round(yb); bottom = (int) Math.round(ya); cTop = cb; cBottom = ca;
		}
		if (bottom > bottomLimit) bottom = bottomLimit;
		if (top < -4000) top = -4000;
		if (bottom <= top || (cTop >>> 24) + (cBottom >>> 24) == 0) return;
		g.fillGradient(x0, top, x1, bottom, cTop, cBottom);
	}

	/** Screen position of the direction (azimuth th like the player's yaw, elevation el); false behind the camera. */
	private static boolean project(double th, double el, double fx, double fy, double fz, double rx, double rz,
			double ux, double uy, double uz, double cx, double cy, double kx, double ky, double[] sx, double[] sy, int k) {
		double ce = Math.cos(el);
		double dx = -Math.sin(th) * ce, dy = Math.sin(el), dz = Math.cos(th) * ce;
		double zc = dx * fx + dy * fy + dz * fz;
		if (zc < 0.05) return false;
		double xc = dx * rx + dz * rz;
		double yc = dx * ux + dy * uy + dz * uz;
		sx[k] = cx + xc / zc * kx;
		sy[k] = cy - yc / zc * ky;
		return true;
	}

	private static double projectY(double th, double el, double fx, double fy, double fz, double rx, double rz,
			double ux, double uy, double uz, double cy, double ky) {
		double ce = Math.cos(el);
		double dx = -Math.sin(th) * ce, dy = Math.sin(el), dz = Math.cos(th) * ce;
		double zc = dx * fx + dy * fy + dz * fz;
		if (zc < 0.02) return Double.NaN;
		double yc = dx * ux + dy * uy + dz * uz;
		return cy - yc / zc * ky;
	}

	/** Terrain horizon in every column direction from the heightmap of the loaded chunks (up to 160 blocks). */
	private void updateHorizon(ClientLevel level, double ex, double ey, double ez) {
		long now = System.currentTimeMillis();
		double moved = Double.isNaN(this.hx) ? 1e9 : sq(ex - this.hx) + sq(ey - this.hy) + sq(ez - this.hz);
		if (now - this.horizonAt < 500L && moved < 4.0) return;
		this.horizonAt = now;
		this.hx = ex;
		this.hy = ey;
		this.hz = ez;
		for (int i = 0; i < HORIZON; i++) {
			double th = i * Math.PI * 2.0 / HORIZON;
			double dx = -Math.sin(th), dz = Math.cos(th);
			double best = -Math.PI / 2;
			for (int d = 3; d <= 160; d += d < 32 ? 2 : 4) {
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(ex + dx * d), (int) Math.floor(ez + dz * d));
				double el = Math.atan2(top - ey, d);
				if (el > best) best = el;
			}
			this.horizon[i] = best;
		}
	}

	/** 0 by day, 1 at night, with a smooth dusk and dawn (ticks of the day, 0 = sunrise). */
	private static float night(long dayTime) {
		double t = Math.floorMod(dayTime, 24000L);
		double dusk = smooth((t - 12200.0) / 1400.0);
		double dawn = 1.0 - smooth((t - 22300.0) / 1300.0);
		return (float) Math.min(dusk, dawn);
	}

	private static double smooth(double x) {
		x = Math.max(0.0, Math.min(1.0, x));
		return x * x * (3.0 - 2.0 * x);
	}

	private static double sq(double v) {
		return v * v;
	}

	private static int argb(int rgb, float a) {
		int al = Math.max(0, Math.min(255, Math.round(a * 255.0F)));
		return al << 24 | (rgb & 0xFFFFFF);
	}

	private static int mix(int a, int b, float f) {
		int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
		int gg = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
		int bb = Math.round((a & 255) * (1 - f) + (b & 255) * f);
		return r << 16 | gg << 8 | bb;
	}

	private static double optionsFov(Minecraft mc) {
		try {
			Object v = mc.options.fov().get();
			if (v instanceof Number n) return n.doubleValue();
		} catch (Throwable ignored) {
		}
		return 70.0;
	}
}
