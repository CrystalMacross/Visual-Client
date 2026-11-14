package net.visualclient.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.visualclient.util.ColorUtil;

/** 2D drawing helpers built only from fill calls, so they work the same on every GPU and GUI scale. */
public final class Render2D {
	private static final int MAX_RADIUS = 7;
	private static final int[][] INSETS = new int[MAX_RADIUS + 1][];

	static {
		for (int r = 1; r <= MAX_RADIUS; r++) {
			INSETS[r] = new int[r];
			for (int i = 0; i < r; i++) {
				double dy = r - i - 0.5;
				double dx = Math.sqrt(r * r - dy * dy);
				INSETS[r][i] = (int) Math.round(r - dx);
			}
		}
	}

	private Render2D() {
	}

	public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
			return;
		}
		g.fill(x, y, x + w, y + h, color);
	}

	/** Filled rectangle with rounded corners (radius 0..7). Rows never overlap, so translucency stays even. */
	public static void round(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
			return;
		}
		int r = Math.min(Math.min(radius, MAX_RADIUS), Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, color);
		int[] inset = INSETS[r];
		for (int i = 0; i < r; i++) {
			int in = inset[i];
			g.fill(x + in, y + i, x + w - in, y + i + 1, color);
			g.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, color);
		}
	}

	/** Like {@link #round} but only the top corners are rounded (panel headers). */
	public static void roundTop(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
			return;
		}
		int r = Math.min(Math.min(radius, MAX_RADIUS), Math.min(w, h));
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x, y + r, x + w, y + h, color);
		int[] inset = INSETS[r];
		for (int i = 0; i < r; i++) {
			g.fill(x + inset[i], y + i, x + w - inset[i], y + i + 1, color);
		}
	}

	public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
		if (w <= 1 || h <= 1 || (color >>> 24) == 0) {
			return;
		}
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** Left-to-right gradient drawn as vertical strips. */
	public static void gradientH(GuiGraphics g, int x, int y, int w, int h, int left, int right, int strips) {
		if (w <= 0 || h <= 0) {
			return;
		}
		strips = Math.max(1, Math.min(strips, w));
		for (int i = 0; i < strips; i++) {
			int x0 = x + w * i / strips;
			int x1 = x + w * (i + 1) / strips;
			g.fill(x0, y, x1, y + h, ColorUtil.lerp(left, right, (i + 0.5f) / strips));
		}
	}

	public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, false);
	}

	public static void textShadow(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, true);
	}

	public static void textRight(GuiGraphics g, Font font, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, false);
	}

	public static void textCenter(GuiGraphics g, Font font, String s, int centerX, int y, int color) {
		g.drawString(font, s, centerX - font.width(s) / 2, y, color, false);
	}

	/** Shortens text with ".." so it fits into maxWidth pixels. */
	public static String fit(Font font, String s, int maxWidth) {
		if (s == null || s.isEmpty() || font.width(s) <= maxWidth) {
			return s == null ? "" : s;
		}
		int end = s.length();
		while (end > 1 && font.width(s.substring(0, end) + "..") > maxWidth) {
			end--;
		}
		return s.substring(0, Math.max(0, end)) + "..";
	}

	/** Small triangle, 7 px wide. */
	public static void chevron(GuiGraphics g, int centerX, int centerY, boolean down, int color) {
		for (int i = 0; i < 4; i++) {
			int row = down ? i : 3 - i;
			g.fill(centerX - (3 - i), centerY - 2 + row, centerX + (3 - i) + 1, centerY - 1 + row, color);
		}
	}

	public static void cross(GuiGraphics g, int x, int y, int size, int color) {
		for (int i = 0; i < size; i++) {
			g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
			g.fill(x + size - 1 - i, y + i, x + size - i, y + i + 1, color);
		}
	}
}
