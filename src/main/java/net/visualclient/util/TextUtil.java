package net.visualclient.util;

/** Small string helpers without any Minecraft dependency. */
public final class TextUtil {
	private TextUtil() {
	}

	private static final String[] MMSS = new String[3600];

	/** "m:ss" for seconds, cached so HUDs do not allocate every frame. */
	public static String mmss(int seconds) {
		if (seconds < 0) {
			seconds = 0;
		}
		if (seconds >= MMSS.length) {
			return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
		}
		String s = MMSS[seconds];
		if (s == null) {
			s = (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
			MMSS[seconds] = s;
		}
		return s;
	}

	public static String roman(int n) {
		if (n <= 0 || n > 10) {
			return Integer.toString(n);
		}
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			case 7 -> "VII";
			case 8 -> "VIII";
			case 9 -> "IX";
			default -> "X";
		};
	}

	public static String formatNumber(double v, double step) {
		if (step >= 1d) {
			return Long.toString(Math.round(v));
		}
		int decimals = step >= 0.1d ? 1 : 2;
		return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
	}

	public static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "~";
	}
}
