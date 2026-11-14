package net.visualclient.modules.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;

/** Makes everything fully bright. "Gamma" needs no effect icon; "Night vision" is the fallback method. */
public final class FullbrightModule extends ClientModule {
	public static final FullbrightModule INSTANCE = new FullbrightModule();

	private final ModeSetting method = add(new ModeSetting("Method", "Gamma", "Gamma", "Night vision"));
	private final NumberSetting gamma = add(new NumberSetting("Gamma", 12, 2, 15, 1));
	private boolean addedEffect;

	private FullbrightModule() {
		super("Fullbright", "Everything is fully lit, even deep in caves.", Category.VISUALS);
		gamma.visibleIf(() -> method.is("Gamma"));
		method.describe("Gamma changes the brightness value the game reads. Night vision adds a hidden effect.");
	}

	public static boolean gammaActive() {
		return INSTANCE.isEnabled() && INSTANCE.method.is("Gamma");
	}

	public static double gammaValue() {
		return INSTANCE.gamma.asDouble();
	}

	/** Called by the OptionInstance mixin: is this object the game's brightness option? */
	public static boolean isGammaOption(Object option) {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.options != null && option == mc.options.gamma();
	}

	@Override
	protected void tick() {
		if (!method.is("Night vision")) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
		if (current == null || current.getDuration() < 240) {
			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, false, false, false));
			addedEffect = true;
		}
	}

	@Override
	protected void onDisable() {
		Minecraft mc = Minecraft.getInstance();
		if (addedEffect && mc != null && mc.player != null) {
			mc.player.removeEffect(MobEffects.NIGHT_VISION);
		}
		addedEffect = false;
	}
}
