package net.visualclient.modules.extra;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;

/**
 * Turns off the equip animation of the first-person hands: a new item (hotbar switch, picked up,
 * swapped to the offhand) appears at once instead of dropping out of view and sliding back up,
 * and the hand no longer dips after an attack.
 *
 * VisualMixinPlugin patches ItemInHandRenderer: before each return of its tick() (and of the other
 * methods that change the hand heights) the heights go through {@link #height} and the shown
 * items through {@link #stack}. Nothing is changed while the module is off.
 */
public final class NoEquipAnimationModule extends ClientModule {

	public static final NoEquipAnimationModule INSTANCE = new NoEquipAnimationModule();

	private NoEquipAnimationModule() {
		super("No Equipment Animation", "Items in your hands appear instantly instead of sliding up when you switch or equip them.", Category.EXTRA);
	}

	/** A hand height (0 = out of view, 1 = fully raised): always fully raised while active. */
	public static float height(float value) {
		return INSTANCE.isEnabled() ? 1.0F : value;
	}

	/** The item a hand shows: the item really held while active, otherwise unchanged. */
	public static Object stack(Object shown, Object held) {
		return INSTANCE.isEnabled() && held != null ? held : shown;
	}

	/** Which methods were hooked (shown in the log; "none found" if Minecraft changed the renderer). */
	public static String hookStatus() {
		return System.getProperty("visualclient.noequip.hooks", "not loaded yet");
	}
}
