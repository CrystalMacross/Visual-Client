package net.visualclient.core;

import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ModeSetting;
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

	// ------------------------------------------------------------------ GUI Style: UI Old / New

	private static final String MENU_SCREEN = "net.visualclient.gui.NewGuiScreen";

	/** GUI Style: "UI" - Old (classic menu) or New (reworked menu). */
	public static ModeSetting uiMode;

	/** Called right after GuiStyleModule's super constructor, so "UI" is its first setting. */
	public static void guiStyle(ClientModule module) {
		uiMode = module.add(new ModeSetting("UI", "New", "Old", "New"));
		uiMode.describe("Old = the classic Visual Client menu, New = the reworked menu.");
		uiMode.onChange(v -> reopenMenu());
	}

	/** Used by GuiOpener: which menu to open. */
	public static boolean useNewUi() {
		return uiMode == null || !uiMode.is("Old");
	}

	public static void setUi(String mode) {
		if (uiMode != null) uiMode.set(mode);
	}

	/** Switches an open Visual Client menu to the selected UI right away. */
	static void reopenMenu() {
		try {
			Class<?> bridge = Class.forName(MENU_SCREEN);
			Object current = bridge.getMethod("vc$currentScreen").invoke(null);
			if (current == null) return;
			String name = current.getClass().getName();
			boolean menu = name.equals("net.visualclient.gui.VisualGuiScreen") || name.equals(MENU_SCREEN);
			if (!menu) return;
			boolean isNew = name.equals(MENU_SCREEN);
			if (isNew != useNewUi()) bridge.getMethod("vc$openMenu").invoke(null);
		} catch (Throwable ignored) {
			// takes effect the next time the menu is opened
		}
	}
}
