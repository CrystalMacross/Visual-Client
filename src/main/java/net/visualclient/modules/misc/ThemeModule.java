package net.visualclient.modules.misc;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.NumberSetting;

/** Colours and look of the menu and the HUD. Always on. */
public final class ThemeModule extends ClientModule {
	public static final ThemeModule INSTANCE = new ThemeModule();

	/** Set by the mod entry point so this class stays free of Minecraft code. */
	public static Runnable openHudEditor = () -> { };
	public static Runnable resetHudPositions = () -> { };

	public final ColorSetting accent = add(new ColorSetting("Accent", 0xFFFF7A6B, false));
	public final ColorSetting accent2 = add(new ColorSetting("Accent gradient", 0xFFFFC857, false));
	public final ColorSetting background = add(new ColorSetting("Background", 0xE0141B28, true));
	public final ColorSetting text = add(new ColorSetting("Text", 0xFFEAF0F8, false));
	public final NumberSetting radius = add(new NumberSetting("Corner radius", 3, 0, 6, 1).suffix(" px"));
	public final NumberSetting hudOpacity = add(new NumberSetting("HUD opacity", 0.85, 0.2, 1.0, 0.05));
	public final BoolSetting dim = add(new BoolSetting("Dim game behind menu", true));
	private final ActionSetting editor = add(new ActionSetting("Edit HUD layout", () -> openHudEditor.run()));
	private final ActionSetting reset = add(new ActionSetting("Reset HUD positions", () -> resetHudPositions.run()));

	private ThemeModule() {
		super("Theme", "Colours and shape of the menu and the HUD.", Category.MISC);
		defaultEnabled(true);
	}

	@Override
	public boolean canToggle() {
		return false;
	}
}
