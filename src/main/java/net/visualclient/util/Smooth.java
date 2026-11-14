package net.visualclient.util;

/**
 * Frame-rate independent exponential smoothing. Because it uses real time
 * (not ticks), animations stay fluid at 60, 144 or any other FPS.
 */
public final class Smooth {
	private float value;
	private float target;
	private final float speed;
	private long last = System.nanoTime();

	/** @param speed higher = snappier (roughly 1/seconds to reach ~63%). */
	public Smooth(float initial, float speed) {
		this.value = initial;
		this.target = initial;
		this.speed = speed;
	}

	public void set(float newTarget) {
		this.target = newTarget;
	}

	public void snap(float v) {
		this.value = v;
		this.target = v;
		this.last = System.nanoTime();
	}

	public float target() {
		return target;
	}

	public float get() {
		long now = System.nanoTime();
		float dt = Math.min((now - last) / 1_000_000_000f, 0.1f);
		last = now;
		if (value != target) {
			value += (target - value) * (1f - (float) Math.exp(-speed * dt));
			if (Math.abs(target - value) < 0.002f) {
				value = target;
			}
		}
		return value;
	}
}
