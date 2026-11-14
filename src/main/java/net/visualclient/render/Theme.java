package net.visualclient.render;

import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.util.ColorUtil;

/** Central place for the colours used by the menu and by every HUD box. Driven by the Theme module. */
public final class Theme {
	public static final int GOOD = 0xFF63E6A0;
	public static final int WARN = 0xFFFFC857;
	public static final int BAD = 0xFFFF6B6B;

	private Theme() {
	}

	private static ThemeModule m() {
		return ThemeModule.INSTANCE;
	}

	public static int accent() {
		return m().accent.argb();
	}

	public static int accent2() {
		return m().accent2.argb();
	}

	/** Panel / HUD box background. */
	public static int background() {
		return m().background.argb();
	}

	public static int hudBackground() {
		return ColorUtil.mulAlpha(m().background.argb(), m().hudOpacity.asFloat());
	}

	public static int text() {
		return m().text.argb();
	}

	public static int muted() {
		return ColorUtil.lerp(m().text.argb(), ColorUtil.opaque(m().background.argb()), 0.45f);
	}

	/** Slightly lighter than the background, used for rows. */
	public static int row() {
		return ColorUtil.withAlpha(ColorUtil.brighten(m().background.argb(), 0.07f), Math.min(255, ColorUtil.alpha(m().background.argb()) + 10));
	}

	public static int rowHover() {
		return ColorUtil.withAlpha(ColorUtil.brighten(m().background.argb(), 0.14f), Math.min(255, ColorUtil.alpha(m().background.argb()) + 20));
	}

	public static int radius() {
		return m().radius.asInt();
	}

	public static boolean dimBackground() {
		return m().dim.on();
	}

	/** Red when low, amber in the middle, green when high. */
	public static int level(float fraction) {
		return fraction >= 0.6f ? GOOD : fraction >= 0.3f ? WARN : BAD;
	}
}
