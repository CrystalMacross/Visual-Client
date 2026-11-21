package net.visualclient.core;

import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.NumberSetting;

/**
 * Extra settings added to existing modules by the patcher (same package as ClientModule,
 * so it may use the protected add()).
 */
public final class ExtraSettings {

	private ExtraSettings() {
	}

	/** Totem Tweaks: "No Pop Animation". */
	public static BoolSetting noPopAnimation;

	/** Called at the end of TotemTweaksModule's constructor. */
	public static void totemTweaks(ClientModule module) {
		noPopAnimation = module.add(new BoolSetting("No Pop Animation", false));
		noPopAnimation.describe("Hides the totem that flies over your screen when a totem pops.");
	}

	/** Replaces popSize.asFloat() in TotemTweaksModule.popFactor: a near-zero scale hides the pop totem. */
	public static float popScale(NumberSetting popSize) {
		return noPopAnimation != null && noPopAnimation.on() ? 0.0001f : popSize.asFloat();
	}
}
