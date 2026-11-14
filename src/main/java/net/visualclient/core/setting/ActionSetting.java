package net.visualclient.core.setting;

/** A button inside a module's settings. It stores nothing. */
public final class ActionSetting extends Setting<Boolean> {
	private final Runnable action;

	public ActionSetting(String name, Runnable action) {
		super(name, Boolean.FALSE);
		this.action = action;
	}

	public void run() {
		action.run();
	}

	@Override
	public Object toJson() {
		return null;
	}

	@Override
	public void fromJson(Object json) {
		// nothing to load
	}
}
