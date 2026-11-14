package net.visualclient.modules.visuals;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.glint.GlintTextures;
import net.visualclient.util.ColorUtil;

/** Changes the colour of the enchantment glint. Type a HEX code or use the picker. */
public final class ArmorGlintModule extends ClientModule {
	public static final ArmorGlintModule INSTANCE = new ArmorGlintModule();

	private final ColorSetting color = add(new ColorSetting("Glint color", 0xFF00E5FF, false));
	private final BoolSetting rainbow = add(new BoolSetting("Rainbow", false));
	private final NumberSetting speed = add(new NumberSetting("Rainbow speed", 1.0, 0.2, 5.0, 0.1));
	private final NumberSetting brightness = add(new NumberSetting("Brightness", 1.0, 0.4, 2.0, 0.05));
	private final BoolSetting armor = add(new BoolSetting("Armor", true));
	private final BoolSetting items = add(new BoolSetting("Held items and inventory", true));

	private ArmorGlintModule() {
		super("Armor Glint", "Changes the color of the enchantment glint on armor and items.", Category.VISUALS);
		color.visibleIf(() -> !rainbow.on());
		speed.visibleIf(rainbow::on);
		color.onChange(v -> GlintTextures.markDirty());
		rainbow.onChange(v -> GlintTextures.markDirty());
		brightness.onChange(v -> GlintTextures.markDirty());
		armor.onChange(v -> GlintTextures.markDirty());
		items.onChange(v -> GlintTextures.markDirty());
	}

	public boolean rainbow() {
		return rainbow.on();
	}

	public boolean armor() {
		return armor.on();
	}

	public boolean items() {
		return items.on();
	}

	public float brightness() {
		return brightness.asFloat();
	}

	/** RGB (no alpha) to paint the glint with right now. */
	public int currentRgb(long nowMs) {
		if (rainbow.on()) {
			double period = 9000.0 / speed.asDouble();
			float hue = (float) ((nowMs % (long) period) / period);
			return ColorUtil.hsv(hue, 0.85f, 1f) & 0xFFFFFF;
		}
		return color.rgb();
	}

	@Override
	protected void onEnable() {
		GlintTextures.markDirty();
	}

	@Override
	protected void onDisable() {
		GlintTextures.markDirty();
	}
}
