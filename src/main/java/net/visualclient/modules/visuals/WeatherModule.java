package net.visualclient.modules.visuals;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;

/**
 * Forces the weather you SEE, only on your screen. The server keeps its own weather.
 * A mixin on Level#getRainLevel / getThunderLevel reads {@link #rain()} and {@link #thunder()}.
 */
public final class WeatherModule extends ClientModule {
	public static final WeatherModule INSTANCE = new WeatherModule();

	private final ModeSetting mode = add(new ModeSetting("Weather", "Clear", "Clear", "Rain", "Thunder"));
	private final NumberSetting fade = add(new NumberSetting("Fade time", 2.0, 0.0, 10.0, 0.5).suffix(" s"));

	private float rain;
	private float thunder;
	private long last = System.nanoTime();

	private WeatherModule() {
		super("Weather Changer", "Changes the weather you see. Only on your screen, the server is not affected.", Category.VISUALS);
	}

	public static boolean active() {
		return INSTANCE.isEnabled();
	}

	public static float rain() {
		INSTANCE.advance();
		return INSTANCE.rain;
	}

	public static float thunder() {
		INSTANCE.advance();
		return INSTANCE.thunder;
	}

	private void advance() {
		long now = System.nanoTime();
		float dt = Math.min((now - last) / 1_000_000_000f, 0.25f);
		last = now;
		float targetRain = mode.is("Clear") ? 0f : 1f;
		float targetThunder = mode.is("Thunder") ? 1f : 0f;
		float seconds = fade.asFloat();
		float step = seconds <= 0.01f ? 1f : dt / seconds;
		rain = approach(rain, targetRain, step);
		thunder = approach(thunder, targetThunder, step);
	}

	private static float approach(float value, float target, float step) {
		if (value < target) {
			return Math.min(target, value + step);
		}
		return Math.max(target, value - step);
	}
}
