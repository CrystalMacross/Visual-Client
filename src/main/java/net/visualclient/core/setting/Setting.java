package net.visualclient.core.setting;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.visualclient.core.ConfigManager;

/** Base class of every configurable value. */
public abstract class Setting<T> {
	private final String name;
	private String description = "";
	private BooleanSupplier visibility = () -> true;
	private Consumer<T> listener;
	protected T value;

	protected Setting(String name, T initial) {
		this.name = name;
		this.value = initial;
	}

	public final String name() {
		return name;
	}

	public final String description() {
		return description;
	}

	public final T get() {
		return value;
	}

	/** Sets the value, saves the config later and notifies the change listener. */
	public final void set(T newValue) {
		T clean = sanitize(newValue);
		if (Objects.equals(clean, value)) {
			return;
		}
		value = clean;
		ConfigManager.markDirty();
		if (listener != null) {
			listener.accept(clean);
		}
	}

	/** Sets the value without notifying anyone (used while loading the config). */
	protected final void load(T newValue) {
		this.value = sanitize(newValue);
	}

	protected T sanitize(T v) {
		return v;
	}

	public final Setting<T> describe(String text) {
		this.description = text;
		return this;
	}

	public final Setting<T> visibleIf(BooleanSupplier supplier) {
		this.visibility = supplier;
		return this;
	}

	public final Setting<T> onChange(Consumer<T> l) {
		this.listener = l;
		return this;
	}

	public final boolean isVisible() {
		return visibility.getAsBoolean();
	}

	public abstract Object toJson();

	public abstract void fromJson(Object json);
}
