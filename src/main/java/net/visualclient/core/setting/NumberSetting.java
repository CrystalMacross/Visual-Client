package net.visualclient.core.setting;

import net.visualclient.util.TextUtil;

public final class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;
	private String suffix = "";

	public NumberSetting(String name, double initial, double min, double max, double step) {
		super(name, initial);
		this.min = min;
		this.max = max;
		this.step = step;
		this.value = sanitize(initial);
	}

	public NumberSetting suffix(String text) {
		this.suffix = text;
		return this;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	public double asDouble() {
		return value;
	}

	public float asFloat() {
		return value.floatValue();
	}

	public int asInt() {
		return (int) Math.round(value);
	}

	/** Position inside the range, 0..1. */
	public double fraction() {
		return max == min ? 0d : (value - min) / (max - min);
	}

	public void setFraction(double f) {
		f = Math.max(0d, Math.min(1d, f));
		set(min + (max - min) * f);
	}

	public String display() {
		return TextUtil.formatNumber(value, step) + suffix;
	}

	@Override
	protected Double sanitize(Double v) {
		double x = Math.max(min, Math.min(max, v));
		if (step > 0d) {
			x = Math.round((x - min) / step) * step + min;
			x = Math.max(min, Math.min(max, x));
		}
		return x;
	}

	@Override
	public Object toJson() {
		return value;
	}

	@Override
	public void fromJson(Object json) {
		if (json instanceof Number n) {
			load(n.doubleValue());
		}
	}
}
