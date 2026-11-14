package net.visualclient.util;

/** ARGB colour helpers. All colours are 0xAARRGGBB ints. */
public final class ColorUtil {
	private ColorUtil() {
	}

	public static int argb(int a, int r, int g, int b) {
		return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
	}

	public static int alpha(int c) {
		return c >>> 24;
	}

	public static int red(int c) {
		return c >> 16 & 255;
	}

	public static int green(int c) {
		return c >> 8 & 255;
	}

	public static int blue(int c) {
		return c & 255;
	}

	public static int withAlpha(int c, int a) {
		return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
	}

	public static int opaque(int c) {
		return c | 0xFF000000;
	}

	public static float clamp01(float f) {
		return f < 0f ? 0f : Math.min(f, 1f);
	}

	/** Multiplies the alpha channel by f (0..1). */
	public static int mulAlpha(int c, float f) {
		return withAlpha(c, Math.round(alpha(c) * clamp01(f)));
	}

	/** Multiplies the RGB channels by f, keeps alpha. */
	public static int scaleRgb(int c, float f) {
		int r = Math.min(255, Math.round(red(c) * f));
		int g = Math.min(255, Math.round(green(c) * f));
		int b = Math.min(255, Math.round(blue(c) * f));
		return argb(alpha(c), r, g, b);
	}

	public static int lerp(int a, int b, float t) {
		t = clamp01(t);
		return argb(
				Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
				Math.round(red(a) + (red(b) - red(a)) * t),
				Math.round(green(a) + (green(b) - green(a)) * t),
				Math.round(blue(a) + (blue(b) - blue(a)) * t));
	}

	public static int brighten(int c, float amount) {
		return lerp(c, withAlpha(0xFFFFFF, alpha(c)), amount);
	}

	public static int darken(int c, float amount) {
		return lerp(c, withAlpha(0x000000, alpha(c)), amount);
	}

	public static float luma(int c) {
		return (0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)) / 255f;
	}

	/** h, s, v in 0..1; returns opaque colour. */
	public static int hsv(float h, float s, float v) {
		h = h - (float) Math.floor(h);
		s = clamp01(s);
		v = clamp01(v);
		float x = h * 6f;
		int i = (int) Math.floor(x);
		float f = x - i;
		float p = v * (1f - s);
		float q = v * (1f - s * f);
		float t = v * (1f - s * (1f - f));
		float r;
		float g;
		float b;
		switch (i % 6) {
			case 0 -> {
				r = v;
				g = t;
				b = p;
			}
			case 1 -> {
				r = q;
				g = v;
				b = p;
			}
			case 2 -> {
				r = p;
				g = v;
				b = t;
			}
			case 3 -> {
				r = p;
				g = q;
				b = v;
			}
			case 4 -> {
				r = t;
				g = p;
				b = v;
			}
			default -> {
				r = v;
				g = p;
				b = q;
			}
		}
		return argb(255, Math.round(r * 255f), Math.round(g * 255f), Math.round(b * 255f));
	}

	/** Writes h, s, v (0..1) of the colour into out[0..2]. */
	public static float[] toHsv(int c, float[] out) {
		float r = red(c) / 255f;
		float g = green(c) / 255f;
		float b = blue(c) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d == 0f) {
			h = 0f;
		} else if (max == r) {
			h = ((g - b) / d) % 6f;
		} else if (max == g) {
			h = (b - r) / d + 2f;
		} else {
			h = (r - g) / d + 4f;
		}
		h /= 6f;
		if (h < 0f) {
			h += 1f;
		}
		out[0] = h;
		out[1] = max == 0f ? 0f : d / max;
		out[2] = max;
		return out;
	}

	public static String hex(int argb, boolean withAlpha) {
		return withAlpha
				? String.format("#%08X", argb)
				: String.format("#%06X", argb & 0xFFFFFF);
	}

	/** Parses #RGB, #RRGGBB or #AARRGGBB (with or without '#'). */
	public static int parseHex(String text, int fallback) {
		if (text == null) {
			return fallback;
		}
		String s = text.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		try {
			if (s.length() == 3) {
				int r = Integer.parseInt(s.substring(0, 1), 16);
				int g = Integer.parseInt(s.substring(1, 2), 16);
				int b = Integer.parseInt(s.substring(2, 3), 16);
				return argb(255, r * 17, g * 17, b * 17);
			}
			if (s.length() == 6) {
				return 0xFF000000 | Integer.parseInt(s, 16);
			}
			if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return fallback;
	}
	/** True for #RGB, #RRGGBB or #AARRGGBB (the '#' is optional). */
	public static boolean isHex(String text) {
		if (text == null) {
			return false;
		}
		String s = text.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		if (s.length() != 3 && s.length() != 6 && s.length() != 8) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (Character.digit(s.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}

	/** Number of hex digits (3, 6 or 8) of a valid hex string, else 0. */
	public static int hexDigits(String text) {
		if (!isHex(text)) {
			return 0;
		}
		String s = text.trim();
		return s.startsWith("#") ? s.length() - 1 : s.length();
	}
}
