package net.visualclient.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.Setting;
import net.visualclient.util.Log;
import net.visualclient.util.MiniJson;
import net.visualclient.util.Smooth;

/** One feature of the client. Has a category, an on/off state and any number of settings. */
public abstract class ClientModule {
	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;
	private boolean expanded;
	private boolean tickFailed;

	/** Drives the toggle switch in the menu (0 = off, 1 = on). */
	public final Smooth switchAnim = new Smooth(0f, 18f);

	protected ClientModule(String name, String description, Category category) {
		this.name = name;
		this.description = description;
		this.category = category;
	}

	public final String name() {
		return name;
	}

	public final String description() {
		return description;
	}

	public final Category category() {
		return category;
	}

	public final boolean isEnabled() {
		return enabled;
	}

	public final boolean isExpanded() {
		return expanded;
	}

	public final void setExpanded(boolean value) {
		if (expanded != value) {
			expanded = value;
			ConfigManager.markDirty();
		}
	}

	/** Modules like the theme are always active and have no switch. */
	public boolean canToggle() {
		return true;
	}

	public final void setEnabled(boolean value) {
		if (!canToggle() && !value) {
			return;
		}
		if (enabled == value) {
			return;
		}
		enabled = value;
		switchAnim.set(value ? 1f : 0f);
		ConfigManager.markDirty();
		guard(value ? "enable" : "disable", value ? this::onEnable : this::onDisable);
	}

	public final void toggle() {
		setEnabled(!enabled);
	}

	/** Sets the default state. Only call from the constructor of a subclass. */
	protected final void defaultEnabled(boolean value) {
		this.enabled = value;
		this.switchAnim.snap(value ? 1f : 0f);
	}

	/** Called once, when the game is ready, for every module that is switched on. */
	public final void startup() {
		if (enabled) {
			guard("startup", this::onEnable);
		}
	}

	/** Called when the game closes. */
	public final void shutdown() {
		if (enabled) {
			guard("shutdown", this::onDisable);
		}
	}

	/** Called every client tick while the module is on. Errors switch the tick handler off instead of crashing. */
	public final void runTick() {
		if (!enabled || tickFailed) {
			return;
		}
		try {
			tick();
		} catch (Throwable t) {
			tickFailed = true;
			Log.error("Module '" + name + "' crashed in tick and was paused", t);
		}
	}

	private void guard(String what, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			Log.error("Module '" + name + "' failed to " + what, t);
		}
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	protected void tick() {
	}

	protected final <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public final List<Setting<?>> settings() {
		return settings;
	}

	public final boolean hasSettings() {
		for (Setting<?> s : settings) {
			if (s.isVisible()) {
				return true;
			}
		}
		return false;
	}

	/** Hook for subclasses that save more than their settings (HUD position, ...). */
	protected void saveExtra(Map<String, Object> out) {
	}

	protected void loadExtra(Map<String, Object> in) {
	}

	public final Map<String, Object> toJson() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", enabled);
		out.put("expanded", expanded);
		Map<String, Object> values = new LinkedHashMap<>();
		for (Setting<?> s : settings) {
			if (!(s instanceof ActionSetting)) {
				values.put(s.name(), s.toJson());
			}
		}
		out.put("settings", values);
		saveExtra(out);
		return out;
	}

	public final void fromJson(Object json) {
		Map<String, Object> in = MiniJson.asMap(json);
		if (in == null) {
			return;
		}
		enabled = MiniJson.bool(in.get("enabled"), enabled) || !canToggle();
		expanded = MiniJson.bool(in.get("expanded"), false);
		Map<String, Object> values = MiniJson.asMap(in.get("settings"));
		if (values != null) {
			for (Setting<?> s : settings) {
				if (values.containsKey(s.name())) {
					s.fromJson(values.get(s.name()));
				}
			}
		}
		loadExtra(in);
		switchAnim.snap(enabled ? 1f : 0f);
	}
}
