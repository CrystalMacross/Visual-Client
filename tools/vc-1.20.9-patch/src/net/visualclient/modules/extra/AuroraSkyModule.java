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
 * so they stay in place when you look around. They are drawn as soft vertical gradients from the HUD layer, in
 * real screen pixels (not GUI pixels) with a nanosecond clock and the interpolated camera, so they move smoothly at
 * any frame rate. Blocks always hide them (1.20.12): every frame a ray per direction runs through the cached
 * heightmap from the camera on, so walls right next to you, trees, buildings and mountains up to 384 blocks
 * cover the curtains and the cubes. Under a roof, underwater, in rain, during the day ("Only at night") and outside
 * the Overworld the aurora is gone.
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
	/**
	 * Blocks always hide the aurora. The terrain heights (MOTION_BLOCKING heightmap) around the camera are cached in a
	 * square of 2 * NEAR + 1 blocks; every frame a ray is traced through that grid in each of the 720 column
	 * directions, cell by cell from the camera on (walls right next to you count too), and gives the steepest terrain
	 * slope. Beyond that, up to FAR blocks, the highest point per direction is sampled a few directions per frame and
	 * kept as a world position, so it stays right while you move. horizon = terrain elevation (radians) per column.
	 */
	private static final int HORIZON = COLUMNS;
	private static final int NEAR = 56, SIDE = NEAR * 2 + 1, NEAR_RANGE = NEAR - 9, FAR = 384;
	private final int[] tops = new int[SIDE * SIDE];
	private int tox = Integer.MIN_VALUE, toz, topRow;
	private Object topLevel;
	private final double[] nearSlope = new double[HORIZON];
	private final double[] farX = new double[HORIZON], farZ = new double[HORIZON], farTop = new double[HORIZON];
	private int farNext;
	private long horizonNs;
	private final double[] horizon = new double[HORIZON];
	private final double[] horizonNear = new double[HORIZON];
	private static final long T0 = System.nanoTime();
	/**
	 * The three ribbons of the website's sky (sky.js): lower edge (degrees), wave amplitude (degrees), height factor,
	 * wave scale k, speed, strength, phase.
	 */
	private static final double[][] RIBBONS = {{20.0, 4.5, 1.0, 1.0, 1.0, 0.62, 0.0}, {16.0, 6.0, 0.67, 1.45, -0.8, 0.46, 2.1}, {30.0, 3.5, 0.8, 0.7, 0.5, 0.34, 4.2}};
	private static final int TEAL = 0x2DD4BF, SKY = 0x38BDF8, LIME = 0xA3E635, MINT = 0x5EEAD4, INDIGO = 0x818CF8, VIOLET = 0xC084FC, AMBER = 0xFBBF24;
	/** Cube colours of the website. */
	private static final int[] CUBE_COLORS = {TEAL, SKY, LIME, INDIGO, MINT, AMBER, SKY, TEAL};
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
		var cam = mc.gameRenderer.CAMERA;
		// the camera's position is interpolated every frame (the player's position only changes 20 times a second)
		var camPos = cam.POS;
		double ex = camPos.x, ey = camPos.y, ez = camPos.z;

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
			else {
				int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(ex), (int) Math.floor(ez));
				if (roof > ey + 0.1) target = 0.0F;
			}
		}
		this.visible += (target - this.visible) * Math.min(1.0F, dt * 1.5F);
		if (target == 0.0F && this.visible < 0.35F) this.visible = 0.0F; // under a roof: gone quickly, not shining through
		if (this.visible < 0.01F) return;
		if (this.blocks.on()) this.moveBlocks(dt);

		this.updateHorizon(level, ex, ey, ez);

		// draw in real screen pixels instead of GUI pixels: smooth movement at any GUI scale
		int gw = g.guiWidth();
		int gh = g.guiHeight();
		double fbw = net.visualclient.compat.Compat.framebufferWidth();
		double fbh = net.visualclient.compat.Compat.framebufferHeight();
		boolean real = fbw >= gw && fbh >= gh && fbw > 0.0 && fbh > 0.0;
		int w = real ? (int) fbw : gw;
		int h = real ? (int) fbh : gh;
		float down = real ? (float) (gw / fbw) : 1.0F;
		double gs = real ? fbw / gw : 1.0;
		this.lw = Math.max(1, (int) Math.round(gs * 0.5));
		this.band = gs >= 3.0 ? 2 : 1;
		this.lim = Math.max(1.0, gs);
		g.pose().PUSH();
		if (real) g.pose().SCALE(down);
		try {
			this.draw(g, mc, cam, w, h);
		} finally {
			g.pose().POP();
		}
	}

	/** Line thickness, polygon row height and size limit factor for the current GUI scale. */
	private int lw = 1, band = 1;
	private double lim = 1.0;

	private void draw(GG g, Minecraft mc, net.minecraft.client.Camera cam, int w, int h) {
		double yaw = Math.toRadians(cam.YAW);
		double pitch = Math.toRadians(cam.PITCH);
		double fov = net.visualclient.modules.misc.PinModule.worldFov;
		if (!(fov > 1.0) || !(fov < 179.0)) fov = optionsFov(mc);
		double tan = Math.tan(Math.toRadians(fov) * 0.5);
		double aspect = (double) w / Math.max(1, h);

		// camera basis (yaw 0 looks to +Z, positive pitch looks down)
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		double cx = w / 2.0, cy = h / 2.0;
		double kx = cx / (tan * aspect), ky = cy / tan;
		int bottomLimit = h;

		// nanosecond clock: the animation moves on every frame (the millisecond clock jumps in 16 ms steps on Windows)
		double t = ((System.nanoTime() - T0) % 3_600_000_000_000L) / 1.0E9 * this.speed.asDouble();
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
				// the column (0.5 degrees wide) starts above the highest terrain of its own and both neighbouring directions
				double hor = Math.max(this.horizon[i], Math.max(this.horizon[(i + 1) % HORIZON], this.horizon[(i + HORIZON - 1) % HORIZON]));
				double low = Math.max(base, hor + Math.toRadians(0.4));
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
			// hidden as soon as any part of the cube could be behind terrain (over its whole width, lowest corner)
			double reach = c[4] * 0.87;
			double th = Math.atan2(-c[0], c[2]);
			double aw = Math.atan2(reach, dist) + STEP;
			int h0 = (int) Math.floor((th - aw) / STEP), h1 = (int) Math.ceil((th + aw) / STEP);
			double hor = -Math.PI / 2;
			for (int hi = h0; hi <= h1; hi++) hor = Math.max(hor, this.horizonNear[Math.floorMod(hi, HORIZON)]);
			double lowEl = Math.atan2(c[1] - reach, dist);
			if (lowEl < hor + Math.toRadians(0.5)) continue;
			float fade = (float) Math.min(1.0, Math.min((el - Math.toRadians(4.0)) / Math.toRadians(10.0), (72.0 - c[1]) / 10.0));
			fade *= (float) Math.min(1.0, (lowEl - hor - Math.toRadians(0.5)) / Math.toRadians(2.0));
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
				if (size > 22.0 * this.lim) { // 4 x 4 block pixels on the bigger cubes
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
	private void line(GG g, double x0, double y0, double x1, double y1, int color, int w, int bottomLimit) {
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
			if (rows > 400 * this.lim) return;
			for (int i = 0; i < rows; i++) {
				double a = (double) i / rows, b = (double) (i + 1) / rows;
				int y = (int) Math.floor(y0 + dy * (a + b) * 0.5);
				if (y < 0 || y >= bottomLimit) continue;
				int xa = Math.max(0, (int) Math.round(x0 + dx * a)), xb = Math.min(w, (int) Math.round(x0 + dx * b));
				if (xb <= xa) xb = xa + 1;
				g.fill(xa, y, xb, y + this.lw, color);
			}
		} else {
			if (y1 < y0) {
				double tx = x0; x0 = x1; x1 = tx;
				double ty = y0; y0 = y1; y1 = ty;
				dy = -dy;
				dx = -dx;
			}
			int cols = Math.max(1, (int) Math.round(Math.abs(dx)) + 1);
			if (cols > 400 * this.lim) return;
			for (int i = 0; i < cols; i++) {
				double a = (double) i / cols, b = (double) (i + 1) / cols;
				int x = (int) Math.floor(x0 + dx * (a + b) * 0.5);
				if (x < 0 || x >= w) continue;
				int ya = Math.max(0, (int) Math.round(y0 + dy * a)), yb = Math.min(bottomLimit, (int) Math.round(y0 + dy * b));
				if (yb <= ya) yb = ya + 1;
				if (ya >= bottomLimit) continue;
				g.fill(x, ya, x + this.lw, yb, color);
			}
		}
	}

	/** Fills a convex polygon with 1px rows (the GUI only has axis-aligned fills). */
	private void fillPoly(GG g, double[] xs, double[] ys, int color, int w, int bottomLimit) {
		if (color >>> 24 == 0) return;
		double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
		double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
		int y0 = Math.max(0, (int) Math.floor(minY));
		int y1 = Math.min(bottomLimit, (int) Math.ceil(maxY));
		if (y1 - y0 > 200 * this.lim) return;
		for (int row = y0; row < y1; row += this.band) {
			double yc = row + this.band * 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
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
				if (r > l) g.fill(l, row, r, Math.min(y1, row + this.band), color);
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

	/**
	 * Terrain horizon in all 720 column directions. Near (up to NEAR_RANGE blocks): a ray through the cached heightmap,
	 * every cell it crosses, from the camera on, every frame. Far (up to FAR blocks): the highest point per direction,
	 * 48 directions per frame, kept as a world position.
	 */
	private void updateHorizon(ClientLevel level, double ex, double ey, double ez) {
		int bx = (int) Math.floor(ex), bz = (int) Math.floor(ez);
		long nowNs = System.nanoTime();
		boolean paused = nowNs - this.horizonNs > 250_000_000L; // not drawn for a while (roof, day, menu): read everything again
		this.horizonNs = nowNs;
		boolean recenter = paused || level != this.topLevel || Math.abs(bx - this.tox) > 8 || Math.abs(bz - this.toz) > 8;
		if (recenter) {
			boolean farToo = paused || level != this.topLevel || Math.abs(bx - this.tox) > 48 || Math.abs(bz - this.toz) > 48;
			this.topLevel = level;
			this.tox = bx;
			this.toz = bz;
			for (int r = 0; r < SIDE; r++) this.fillRow(level, r);
			if (farToo) for (int i = 0; i < HORIZON; i++) this.sampleFar(level, i, ex, ey, ez);
		} else {
			// keep the cache fresh (placed and broken blocks): 1/8 of the rows per frame
			for (int k = 0; k < SIDE / 8 + 1; k++) {
				this.fillRow(level, this.topRow);
				this.topRow = (this.topRow + 1) % SIDE;
			}
		}
		for (int k = 0; k < 48; k++) {
			this.sampleFar(level, this.farNext, ex, ey, ez);
			this.farNext = (this.farNext + 1) % HORIZON;
		}
		// near: walk the grid along each direction (2D DDA), steepest slope of the cell tops
		double ox = ex - (this.tox - NEAR), oz = ez - (this.toz - NEAR);
		for (int i = 0; i < HORIZON; i++) {
			double th = i * STEP;
			double dx = -Math.sin(th), dz = Math.cos(th);
			int cx = (int) Math.floor(ox), cz = (int) Math.floor(oz);
			int sx = dx > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
			double tdx = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
			double tdz = Math.abs(dz) < 1e-9 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
			double tmx = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * tdx;
			double tmz = Math.abs(dz) < 1e-9 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - oz : oz - cz) * tdz;
			double best = -1e9;
			while (true) {
				double tEnter;
				if (tmx < tmz) {
					tEnter = tmx;
					tmx += tdx;
					cx += sx;
				} else {
					tEnter = tmz;
					tmz += tdz;
					cz += sz;
				}
				if (tEnter > NEAR_RANGE || cx < 0 || cz < 0 || cx >= SIDE || cz >= SIDE) break;
				double s = (this.tops[cz * SIDE + cx] - ey) / Math.max(0.05, tEnter);
				if (s > best) best = s;
			}
			this.nearSlope[i] = best;
		}
		for (int i = 0; i < HORIZON; i++) {
			double near = this.nearSlope[i] < -1e8 ? -Math.PI / 2 : Math.atan(this.nearSlope[i]);
			this.horizonNear[i] = near;
			double far = -Math.PI / 2;
			if (!Double.isNaN(this.farTop[i])) {
				double d = Math.sqrt(sq(this.farX[i] - ex) + sq(this.farZ[i] - ez));
				if (d > 1.0) far = Math.atan2(this.farTop[i] - ey, d);
			}
			this.horizon[i] = Math.max(near, far);
		}
	}

	private void fillRow(ClientLevel level, int r) {
		int z = this.toz - NEAR + r, x0 = this.tox - NEAR;
		for (int c = 0; c < SIDE; c++) this.tops[r * SIDE + c] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x0 + c, z);
	}

	/** Highest terrain point (by elevation) in direction i between NEAR_RANGE - 8 and FAR blocks. */
	private void sampleFar(ClientLevel level, int i, double ex, double ey, double ez) {
		double th = i * STEP;
		double dx = -Math.sin(th), dz = Math.cos(th);
		double best = -1e9, bxp = 0, bzp = 0, btop = Double.NaN;
		for (int d = NEAR_RANGE - 8; d <= FAR; d += d < 128 ? 2 : 4) {
			double x = ex + dx * d, z = ez + dz * d;
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
			// the near edge of the sampled area, so the point is never placed too far away
			double s = (top - ey) / (d - 1.5);
			if (s > best) {
				best = s;
				bxp = ex + dx * (d - 1.5);
				bzp = ez + dz * (d - 1.5);
				btop = top;
			}
		}
		this.farX[i] = bxp;
		this.farZ[i] = bzp;
		this.farTop[i] = btop;
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
