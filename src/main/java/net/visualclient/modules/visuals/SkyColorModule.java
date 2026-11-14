package net.visualclient.modules.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.util.ColorUtil;

/**
 * Recolours the sky (and optionally fog and clouds) of the overworld.
 * In 1.21.11 the sky colour is an environment attribute; a mixin on EnvironmentAttributeProbe calls the
 * modify* methods below. Type any HEX code in the menu, e.g. #C77DFF.
 */
public final class SkyColorModule extends ClientModule {
	public static final SkyColorModule INSTANCE = new SkyColorModule();

	public final ColorSetting sky = add(new ColorSetting("Sky color", 0xFFC77DFF, false));
	private final BoolSetting keepCycle = add(new BoolSetting("Keep day and night", true));
	private final BoolSetting fog = add(new BoolSetting("Tint fog to match", true));
	private final BoolSetting clouds = add(new BoolSetting("Tint clouds", false));
	private final ColorSetting cloudColor = add(new ColorSetting("Cloud color", 0xFFFFFFFF, false));

	private SkyColorModule() {
		super("Sky Color", "Paints the sky in any color. Type a HEX code or use the picker.", Category.VISUALS);
		cloudColor.visibleIf(clouds::on);
		keepCycle.describe("On: the color gets darker at night and in storms. Off: always the exact color.");
	}

	private static boolean inOverworld() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.level != null && mc.level.dimension() == Level.OVERWORLD;
	}

	/** Brightness of the vanilla colour (0..1), so the day and night cycle still shows through. */
	private float cycle(int vanilla) {
		if (!keepCycle.on()) {
			return 1f;
		}
		int peak = Math.max(ColorUtil.red(vanilla), Math.max(ColorUtil.green(vanilla), ColorUtil.blue(vanilla)));
		return peak / 255f;
	}

	/** Returns the colour to use instead of the vanilla sky colour, or null to leave it alone. */
	public static Integer modifySky(Integer vanilla) {
		SkyColorModule m = INSTANCE;
		if (vanilla == null || !m.isEnabled() || !inOverworld()) {
			return null;
		}
		return ColorUtil.opaque(ColorUtil.scaleRgb(m.sky.argb(), m.cycle(vanilla)) & 0xFFFFFF);
	}

	public static Integer modifyFog(Integer vanilla) {
		SkyColorModule m = INSTANCE;
		if (vanilla == null || !m.isEnabled() || !m.fog.on() || !inOverworld()) {
			return null;
		}
		return ColorUtil.opaque(ColorUtil.scaleRgb(m.sky.argb(), m.cycle(vanilla)) & 0xFFFFFF);
	}

	public static Integer modifyCloud(Integer vanilla) {
		SkyColorModule m = INSTANCE;
		if (vanilla == null || !m.isEnabled() || !m.clouds.on() || !inOverworld()) {
			return null;
		}
		int rgb = ColorUtil.scaleRgb(m.cloudColor.argb(), m.cycle(vanilla)) & 0xFFFFFF;
		return (vanilla & 0xFF000000) | rgb;
	}
}
