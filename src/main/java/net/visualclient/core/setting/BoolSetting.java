package net.visualclient.core.setting;

public final class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String name, boolean initial) {
		super(name, initial);
	}

	public boolean on() {
		return value;
	}

	public void toggle() {
		set(!value);
	}

	@Override
	public Object toJson() {
		return value;
	}

	@Override
	public void fromJson(Object json) {
		if (json instanceof Boolean b) {
			load(b);
		}
	}
}
