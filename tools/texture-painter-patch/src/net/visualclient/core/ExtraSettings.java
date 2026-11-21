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
		noPopAnimation.describe("Completely removes the totem pop animation (the totem that flies over your screen).");
	}

	/** True while "No Pop Animation" is switched on (works even if Totem Tweaks itself is off). */
	public static boolean noPopActive() {
		return noPopAnimation != null && noPopAnimation.on();
	}

	/**
	 * Replaces popSize.asFloat() in TotemTweaksModule.popFactor. Only a fallback: the
	 * NoPopAnimationMixin already stops the pop animation from starting at all.
	 */
	public static float popScale(NumberSetting popSize) {
		return noPopActive() ? 0.0001f : popSize.asFloat();
	}
}
