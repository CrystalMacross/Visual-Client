package net.visualclient.core.setting;

public final class TextSetting extends Setting<String> {
	private final int maxLength;
	private final String placeholder;

	public TextSetting(String name, String initial, int maxLength, String placeholder) {
		super(name, initial);
		this.maxLength = maxLength;
		this.placeholder = placeholder;
	}

	public int maxLength() {
		return maxLength;
	}

	public String placeholder() {
		return placeholder;
	}

	@Override
	protected String sanitize(String v) {
		if (v == null) {
			return "";
		}
		return v.length() > maxLength ? v.substring(0, maxLength) : v;
	}

	@Override
	public Object toJson() {
		return value;
	}

	@Override
	public void fromJson(Object json) {
		if (json instanceof String s) {
			load(s);
		}
	}
}
