package net.visualclient.core.setting;

import net.visualclient.util.ColorUtil;

/** A colour that can be edited with a picker or by typing a HEX code. */
public final class ColorSetting extends Setting<Integer> {
	private final boolean alpha;

	public ColorSetting(String name, int argb, boolean alpha) {
		super(name, alpha ? argb : (argb | 0xFF000000));
		this.alpha = alpha;
	}

	public boolean hasAlpha() {
		return alpha;
	}

	public int argb() {
		return value;
	}

	public int rgb() {
		return value & 0xFFFFFF;
	}

	/** "#RRGGBB" (or "#AARRGGBB" when the setting supports transparency). */
	public String hex() {
		return ColorUtil.hex(value, alpha);
	}

	/**
	 * Applies a typed HEX code (#RGB, #RRGGBB or #AARRGGBB).
	 * Short codes keep the current transparency. Returns false if the text is not a valid HEX code.
	 */
	public boolean setHex(String text) {
		if (!ColorUtil.isHex(text)) {
			return false;
		}
		int digits = ColorUtil.hexDigits(text);
		int parsed = ColorUtil.parseHex(text, value);
		if (alpha && digits != 8) {
			parsed = ColorUtil.withAlpha(parsed, ColorUtil.alpha(value));
		}
		set(parsed);
		return true;
	}

	@Override
	protected Integer sanitize(Integer v) {
		return alpha ? v : (v | 0xFF000000);
	}

	@Override
	public Object toJson() {
		return hex();
	}

	@Override
	public void fromJson(Object json) {
		if (json instanceof String s && ColorUtil.isHex(s)) {
			int parsed = ColorUtil.parseHex(s, value);
			if (alpha && ColorUtil.hexDigits(s) != 8) {
				parsed = ColorUtil.withAlpha(parsed, ColorUtil.alpha(value));
			}
			load(parsed);
		}
	}
}
