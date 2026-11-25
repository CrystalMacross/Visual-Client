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
 * (teal, lime, sky blue and indigo), as northern lights (green and violet) or in your own two colours.
 *
 * The curtains hang at fixed directions in the sky (like the sun and the moon) and are projected onto the
 * screen every frame with the camera's direction and field of view (the same projection as the Pins module),
 * so they stay in place when you look around. They are drawn as soft vertical gradients from the HUD layer:
 * terrain, trees and buildings within 160 blocks hide the lower part of a curtain (the heightmap of the
 * loaded chunks gives the terrain's horizon in every direction), and the aurora fades out under a roof,
 * underwater, in rain, during the day ("Only at night") and outside the Overworld.
 *
 * "Falling blocks" (own setting, on by default) adds the voxel cubes of the website's sky: see-through cubes with
 * glowing edges, light-shaded faces, a 4 x 4 block-pixel grid and a soft glow, tumbling down through the sky in the
 * distance (24 - 64 blocks) with the same projection. The "Visual" colours are the website's sky colours.
 */
public final class AuroraSkyModule extends ClientModule implements Overlay {

	public static final AuroraSkyModule INSTANCE = new AuroraSkyModule();

	private final ModeSetting colors = this.add(new ModeSetting("Colors", "Visual", "Visual", "Northern Lights", "Custom"));
	private final ColorSetting lower = this.add(new ColorSetting("Lower color", 0xFF38BDF8, false));
	private final ColorSetting upper = this.add(new ColorSetting("Upper color", 0xFF8B5CF6, false));
	private final NumberSetting brightness = this.add(new NumberSetting("Brightness", 85.0, 10.0, 100.0, 5.0).suffix("%"));
	private final NumberSetting height = this.add(new NumberSetting("Curtain height", 30.0, 12.0, 60.0, 2.0).suffix("°"));
	private final NumberSetting speed = this.add(new NumberSetting("Speed", 1.0, 0.2, 3.0, 0.1).suffix("x"));
	private final BoolSetting nightOnly = this.add(new BoolSetting("Only at night", true));
	private final BoolSetting terrain = this.add(new BoolSetting("Hide behind terrain", true));
	private final BoolSetting blocks = this.add(new BoolSetting("Falling blocks", true));
	private final NumberSetting blockCount = this.add(new NumberSetting("Block amount", 20.0, 4.0, 40.0, 1.0));

	/** Falling 3D blocks: position relative to the player's eye (blocks), fall speed, size, spin. */
	private static final int MAX_BLOCKS = 40;
	private final double[][] cubes = new double[MAX_BLOCKS][];
	private final java.util.Random rng = new java.util.Random();
	/** Cube corners and faces (corner indices, counter-clockwise seen from outside). */
	private static final double[][] CORNERS = {{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
	private static final int[][] FACES = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3}, {1, 2, 6, 5}, {3, 7, 6, 2}, {0, 1, 5, 4}};

	/** Curtain columns all around the sky (0.5 degrees each). */
	private static final int COLUMNS = 720;
	private static final double STEP = Math.PI * 2.0 / COLUMNS;
	/** Terrain horizon in 240 directions (radians above the eye), refreshed twice a second or after moving. */
	private static final int HORIZON = 240;
	private final double[] horizon = new double[HORIZON];
	/**
	 * The three ribbons of the website's sky (sky.js): lower edge (degrees), wave amplitude (degrees), height factor,
	 * wave scale k, speed, strength, phase.
	 */
	private static final double[][] RIBBONS = {{20.0, 4.5, 1.0, 1.0, 1.0, 0.62, 0.0}, {16.0, 6.0, 0.67, 1.45, -0.8, 0.46, 2.1}, {30.0, 3.5, 0.8, 0.7, 0.5, 0.34, 4.2}};
	private static final int TEAL = 0x2DD4BF, SKY = 0x38BDF8, LIME = 0xA3E635, MINT = 0x5EEAD4, INDIGO = 0x818CF8, VIOLET = 0xC084FC, AMBER = 0xFBBF24;
	/** Cube colours of the website. */
	private static final int[] CUBE_COLORS = {TEAL, SKY, LIME, INDIGO, MINT, AMBER, SKY, TEAL};
	private long horizonAt;
	private double hx = Double.NaN, hy, hz;
	private float visible;
	private long lastFrame;

	private AuroraSkyModule() {
		super("Aurora Sky", "Glowing aurora curtains and falling voxel cubes in the night sky, like on the website.", Category.EXTRA);
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
		if (this.blocks.on()) this.moveBlocks(dt);

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
		int bottomLimit = h;

		double t = (System.currentTimeMillis() % 3_600_000L) / 1000.0 * this.speed.asDouble();
		double hDeg = this.height.asDouble();
		float bright = (float) (this.brightness.asDouble() / 100.0) * this.visible;
		// per ribbon: curtain colour and lower edge colour (the website's home sky for "Visual")
		int cLow, cHigh;
		int[][] rib;
		if (this.colors.is("Northern Lights")) {
			cLow = LIME;
			cHigh = VIOLET;
			rib = new int[][]{{TEAL, LIME}, {MINT, TEAL}, {VIOLET, TEAL}};
		} else if (this.colors.is("Custom")) {
			cLow = this.lower.rgb();
			cHigh = this.upper.rgb();
			int mid = mix(cLow, cHigh, 0.5F);
			rib = new int[][]{{cHigh, cLow}, {mid, cLow}, {cHigh, mid}};
		} else {
			cLow = TEAL;
			cHigh = INDIGO;
			rib = new int[][]{{TEAL, LIME}, {SKY, TEAL}, {INDIGO, SKY}};
		}

		double[] sx = new double[2];
		double[] sy = new double[2];
		for (int ri = 0; ri < RIBBONS.length; ri++) {
			double[] rb = RIBBONS[ri];
			double k = rb[3], sp = rb[4], ph = rb[6];
			int curtain = rib[ri][0], edge = rib[ri][1];
			for (int i = 0; i < COLUMNS; i++) {
				double th = i * STEP;
				// skip directions behind the camera early (direction at 35 degrees elevation)
				double qx = -Math.sin(th) * 0.82, qz = Math.cos(th) * 0.82;
				if (qx * fx + 0.57 * fy + qz * fz < -0.35) continue;
				double u = th / (Math.PI * 2.0) * 6.0;
				double env = Math.max(0.0, Math.min(1.0, 0.3 + 0.7 * Math.sin(u * Math.PI * 2.0 * 0.55 + ph + t * 0.05)));
				double rays = 0.55 + 0.25 * Math.sin(i * 0.31 * k + t * 1.3 * sp + ph) + 0.2 * Math.sin(i * 0.097 - t * 0.6 + ph * 2.0);
				float a = (float) (rb[5] * rays * env) * bright;
				if (a < 0.01F) continue;
				double base = Math.toRadians(rb[0] + rb[1] * (Math.sin(u * Math.PI * 2.0 * 1.1 * k + t * 0.21 * sp + ph)
					+ 0.45 * Math.sin(u * Math.PI * 2.0 * 2.9 * k - t * 0.33 + ph * 1.7)));
				double len = Math.toRadians(hDeg * rb[2] * (0.72 + 0.28 * Math.sin(u * 19.0 * k + t * 0.5 + ph)));
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
				// the website's curtain gradient from the bottom up: edge colour fading in (10 %),
				// edge colour into the curtain colour (to 38 %), curtain colour fading out at the top
				double yEdge = projectY(th, base + len * 0.1 > low ? base + len * 0.1 : low + Math.toRadians(0.6), fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				double yMid = projectY(th, Math.max(low + Math.toRadians(1.0), base + len * 0.38), fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				double yTop = projectY(th, top, fx, fy, fz, rx, rz, ux, uy, uz, cy, ky);
				if (Double.isNaN(yEdge) || Double.isNaN(yMid) || Double.isNaN(yTop)) continue;
				float edgeIn = low > base + len * 0.1 ? 0.95F : 0.0F; // hidden by terrain: start bright at the horizon
				fillV(g, x0, x1, yEdge, yLow, argb(edge, a * 0.95F), argb(edge, a * edgeIn), bottomLimit);
				fillV(g, x0, x1, yMid, yEdge, argb(curtain, a * 0.3F), argb(edge, a * 0.95F), bottomLimit);
				fillV(g, x0, x1, yTop, yMid, argb(curtain, 0.0F), argb(curtain, a * 0.3F), bottomLimit);
			}
		}
		if (this.blocks.on()) this.drawBlocks(g, fx, fy, fz, rx, rz, ux, uy, uz, cx, cy, kx, ky, w, bottomLimit, bright, cLow, cHigh, this.colors.is("Visual"));
	}

	/** Lets the blocks fall; a block that left the sky starts again high up in a random direction. */
	private void moveBlocks(float dt) {
		int n = Math.min(MAX_BLOCKS, this.blockCount.asInt());
		double spd = this.speed.asDouble();
		for (int i = 0; i < MAX_BLOCKS; i++) {
			double[] c = this.cubes[i];
			if (i >= n) {
				this.cubes[i] = null;
				continue;
			}
			if (c == null) {
				c = this.cubes[i] = this.spawn(true);
			}
			c[1] -= c[3] * dt * spd;
			c[5] += c[6] * dt * spd;
			c[7] += c[8] * dt * spd;
			double dist = Math.sqrt(c[0] * c[0] + c[2] * c[2]);
			if (Math.atan2(c[1], dist) < Math.toRadians(4.0)) this.cubes[i] = this.spawn(false);
		}
	}

	/** {x, y, z, fall speed, size, spin a, spin speed a, spin b, spin speed b, color mix}. */
	private double[] spawn(boolean anywhere) {
		double th = this.rng.nextDouble() * Math.PI * 2.0;
		double d = 24.0 + this.rng.nextDouble() * 40.0;
		double y = anywhere ? 10.0 + this.rng.nextDouble() * 55.0 : 50.0 + this.rng.nextDouble() * 20.0;
		return new double[]{-Math.sin(th) * d, y, Math.cos(th) * d, 2.0 + this.rng.nextDouble() * 2.5, 2.0 + this.rng.nextDouble() * 2.4,
			this.rng.nextDouble() * 6.3, (this.rng.nextDouble() - 0.5) * 1.6, this.rng.nextDouble() * 6.3, (this.rng.nextDouble() - 0.5) * 1.2,
			this.rng.nextDouble()};
	}

	/** Light direction for the cube faces (from above, like the website's light from the top left). */
	private static final double LX = -0.42, LY = 0.82, LZ = -0.38;

	/**
	 * Draws the blocks like the voxel cubes of the website's sky: see-through faces shaded by the light, glowing
	 * edges, a 4 x 4 block-pixel grid on the bigger cubes and a soft glow around the near ones. Faces that point
	 * away from the camera are skipped; near cubes are more opaque than far ones.
	 */
	private void drawBlocks(GG g, double fx, double fy, double fz, double rx, double rz, double ux, double uy, double uz,
			double cx, double cy, double kx, double ky, int w, int bottomLimit, float bright, int cLow, int cHigh, boolean sitePalette) {
		double[] px = new double[8], py = new double[8], vx = new double[8], vy = new double[8], vz = new double[8];
		double[] nxs = new double[6], nys = new double[6], nzs = new double[6];
		double[] fxs = new double[4], fys = new double[4];
		double ln = Math.sqrt(LX * LX + LY * LY + LZ * LZ);
		for (double[] c : this.cubes) {
			if (c == null) continue;
			double dist = Math.sqrt(c[0] * c[0] + c[2] * c[2]);
			double el = Math.atan2(c[1], dist);
			if (this.terrain.on()) {
				double th = Math.atan2(-c[0], c[2]);
				int hi = (int) Math.floorMod(Math.round(th / (Math.PI * 2.0) * HORIZON), (long) HORIZON);
				if (el < this.horizon[hi] + Math.toRadians(0.5)) continue;
			}
			float fade = (float) Math.min(1.0, Math.min((el - Math.toRadians(4.0)) / Math.toRadians(10.0), (72.0 - c[1]) / 10.0));
			if (fade <= 0.02F) continue;
			double ca = Math.cos(c[5]), sa = Math.sin(c[5]), cb = Math.cos(c[7]), sb = Math.sin(c[7]);
			double half = c[4] * 0.5;
			boolean ok = true;
			double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
			for (int k = 0; k < 8; k++) {
				double x = CORNERS[k][0] * half, y = CORNERS[k][1] * half, z = CORNERS[k][2] * half;
				double x1 = x * ca - z * sa, z1 = x * sa + z * ca; // spin around Y
				double y1 = y * cb - z1 * sb, z2 = y * sb + z1 * cb; // tilt around X
				vx[k] = c[0] + x1;
				vy[k] = c[1] + y1;
				vz[k] = c[2] + z2;
				double zc = vx[k] * fx + vy[k] * fy + vz[k] * fz;
				if (zc < 0.5) {
					ok = false;
					break;
				}
				px[k] = cx + (vx[k] * rx + vz[k] * rz) / zc * kx;
				py[k] = cy - (vx[k] * ux + vy[k] * uy + vz[k] * uz) / zc * ky;
				minX = Math.min(minX, px[k]);
				maxX = Math.max(maxX, px[k]);
				minY = Math.min(minY, py[k]);
				maxY = Math.max(maxY, py[k]);
			}
			if (!ok || maxX < 0 || minX > w || maxY < 0 || minY > bottomLimit) continue;
			double size = Math.max(maxX - minX, maxY - minY);
			// depth like the website: 1 = near, 0 = far
			double d = Math.max(0.0, Math.min(1.0, 1.0 - (Math.sqrt(dist * dist + c[1] * c[1]) - 26.0) / 44.0));
			float alpha = (float) (0.2 + 0.7 * d) * fade * Math.min(1.0F, bright * 1.25F);
			int col = sitePalette ? CUBE_COLORS[(int) (c[9] * CUBE_COLORS.length) % CUBE_COLORS.length] : mix(cLow, cHigh, (float) c[9]);
			double mx = (minX + maxX) * 0.5, my = (minY + maxY) * 0.5;
			if (d > 0.45) { // soft light around the near cubes: stacked squares, fading outwards
				for (int ring = 8; ring >= 1; ring--) {
					double rr = size * (0.4 + 0.1 * ring);
					fillClip(g, mx - rr, my - rr, mx + rr, my + rr, argb(col, (float) (0.026 * d) * fade), w, bottomLimit);
				}
			}
			for (int f = 0; f < 6; f++) {
				int[] q = FACES[f];
				double ax = vx[q[1]] - vx[q[0]], ay = vy[q[1]] - vy[q[0]], az = vz[q[1]] - vz[q[0]];
				double bx = vx[q[3]] - vx[q[0]], by = vy[q[3]] - vy[q[0]], bz = vz[q[3]] - vz[q[0]];
				double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
				double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
				nxs[f] = nx / nl;
				nys[f] = ny / nl;
				nzs[f] = nz / nl;
			}
			for (int f = 0; f < 6; f++) {
				int[] q = FACES[f];
				// only faces turned to the camera
				if (nxs[f] * vx[q[0]] + nys[f] * vy[q[0]] + nzs[f] * vz[q[0]] >= 0.0) continue;
				float lit = (float) Math.max(0.0, Math.min(1.0, (nxs[f] * LX + nys[f] * LY + nzs[f] * LZ) / ln));
				float sh = 0.5F + 0.5F * lit;
				for (int k = 0; k < 4; k++) {
					fxs[k] = px[q[k]];
					fys[k] = py[q[k]];
				}
				fillPoly(g, fxs, fys, argb(scale(col, sh), alpha * (0.38F + 0.42F * lit)), w, bottomLimit);
				int line = argb(col, alpha * (0.45F + 0.5F * lit));
				for (int k = 0; k < 4; k++) line(g, fxs[k], fys[k], fxs[(k + 1) % 4], fys[(k + 1) % 4], line, w, bottomLimit);
				if (size > 22.0) { // 4 x 4 block pixels on the bigger cubes
					int grid = argb(col, alpha * 0.2F);
					for (int n = 1; n < 4; n++) {
						double t = n / 4.0;
						line(g, fxs[0] + (fxs[1] - fxs[0]) * t, fys[0] + (fys[1] - fys[0]) * t, fxs[3] + (fxs[2] - fxs[3]) * t, fys[3] + (fys[2] - fys[3]) * t, grid, w, bottomLimit);
						line(g, fxs[0] + (fxs[3] - fxs[0]) * t, fys[0] + (fys[3] - fys[0]) * t, fxs[1] + (fxs[2] - fxs[1]) * t, fys[1] + (fys[2] - fys[1]) * t, grid, w, bottomLimit);
					}
				}
			}
		}
	}

	private static int scale(int rgb, float f) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 255) * f));
		int gg = Math.min(255, Math.round(((rgb >> 8) & 255) * f));
		int b = Math.min(255, Math.round((rgb & 255) * f));
		return r << 16 | gg << 8 | b;
	}

	private static void fillClip(GG g, double x0, double y0, double x1, double y1, int color, int w, int bottomLimit) {
		int l = Math.max(0, (int) Math.round(x0)), t = Math.max(0, (int) Math.round(y0));
		int r = Math.min(w, (int) Math.round(x1)), b = Math.min(bottomLimit, (int) Math.round(y1));
		if (r > l && b > t && color >>> 24 != 0) g.fill(l, t, r, b, color);
	}

	/** A 1px line from runs of fills: one fill per row (steep lines) or per column (flat lines). */
	private static void line(GG g, double x0, double y0, double x1, double y1, int color, int w, int bottomLimit) {
		if (color >>> 24 == 0) return;
		double dx = x1 - x0, dy = y1 - y0;
		if (Math.abs(dx) >= Math.abs(dy)) {
			if (x1 < x0) {
				double tx = x0; x0 = x1; x1 = tx;
				double ty = y0; y0 = y1; y1 = ty;
				dy = -dy;
				dx = -dx;
			}
			int rows = Math.max(1, (int) Math.round(Math.abs(dy)) + 1);
			if (rows > 400) return;
			for (int i = 0; i < rows; i++) {
				double a = (double) i / rows, b = (double) (i + 1) / rows;
				int y = (int) Math.floor(y0 + dy * (a + b) * 0.5);
				if (y < 0 || y >= bottomLimit) continue;
				int xa = Math.max(0, (int) Math.round(x0 + dx * a)), xb = Math.min(w, (int) Math.round(x0 + dx * b));
				if (xb <= xa) xb = xa + 1;
				g.fill(xa, y, xb, y + 1, color);
			}
		} else {
			if (y1 < y0) {
				double tx = x0; x0 = x1; x1 = tx;
				double ty = y0; y0 = y1; y1 = ty;
				dy = -dy;
				dx = -dx;
			}
			int cols = Math.max(1, (int) Math.round(Math.abs(dx)) + 1);
			if (cols > 400) return;
			for (int i = 0; i < cols; i++) {
				double a = (double) i / cols, b = (double) (i + 1) / cols;
				int x = (int) Math.floor(x0 + dx * (a + b) * 0.5);
				if (x < 0 || x >= w) continue;
				int ya = Math.max(0, (int) Math.round(y0 + dy * a)), yb = Math.min(bottomLimit, (int) Math.round(y0 + dy * b));
				if (yb <= ya) yb = ya + 1;
				if (ya >= bottomLimit) continue;
				g.fill(x, ya, x + 1, yb, color);
			}
		}
	}

	/** Fills a convex polygon with 1px rows (the GUI only has axis-aligned fills). */
	private static void fillPoly(GG g, double[] xs, double[] ys, int color, int w, int bottomLimit) {
		if (color >>> 24 == 0) return;
		double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
		double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
		int y0 = Math.max(0, (int) Math.floor(minY));
		int y1 = Math.min(bottomLimit, (int) Math.ceil(maxY));
		if (y1 - y0 > 200) return;
		for (int row = y0; row < y1; row++) {
			double yc = row + 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				double ax = xs[i], ay = ys[i], bx = xs[(i + 1) % 4], by = ys[(i + 1) % 4];
				if (yc >= ay && yc < by || yc >= by && yc < ay) {
					double ix = ax + (yc - ay) * (bx - ax) / (by - ay);
					lo = Math.min(lo, ix);
					hi = Math.max(hi, ix);
				}
			}
			if (lo <= hi) {
				int l = Math.max(0, (int) Math.round(lo)), r = Math.min(w, (int) Math.round(hi));
				if (r > l) g.fill(l, row, r, row + 1, color);
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
