package net.visualclient.core.setting;

import java.util.Arrays;

public final class ModeSetting extends Setting<String> {
	private final String[] modes;

	public ModeSetting(String name, String initial, String... modes) {
		super(name, initial);
		this.modes = modes;
		if (!Arrays.asList(modes).contains(initial)) {
			this.value = modes[0];
		}
	}

	public String[] modes() {
		return modes;
	}

	public boolean is(String mode) {
		return value.equals(mode);
	}

	public int index() {
		for (int i = 0; i < modes.length; i++) {
			if (modes[i].equals(value)) {
				return i;
			}
		}
		return 0;
	}

	/** Moves to the next (dir = 1) or previous (dir = -1) mode. */
	public void cycle(int dir) {
		int i = (index() + dir) % modes.length;
		if (i < 0) {
			i += modes.length;
		}
		set(modes[i]);
	}

	@Override
	protected String sanitize(String v) {
		return v != null && Arrays.asList(modes).contains(v) ? v : modes[0];
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
