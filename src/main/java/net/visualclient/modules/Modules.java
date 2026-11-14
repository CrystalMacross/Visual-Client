package net.visualclient.modules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.hud.HudModule;
import net.visualclient.modules.combat.ArmorHudModule;
import net.visualclient.modules.combat.DamageTintModule;
import net.visualclient.modules.combat.PotionHudModule;
import net.visualclient.modules.combat.TotemCounterModule;
import net.visualclient.modules.misc.DiscordRpcModule;
import net.visualclient.modules.misc.FpsModule;
import net.visualclient.modules.misc.MusicHudModule;
import net.visualclient.modules.misc.PingModule;
import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.modules.player.AutoSprintModule;
import net.visualclient.modules.player.SaturationModule;
import net.visualclient.modules.player.ShulkerTooltipModule;
import net.visualclient.modules.visuals.ArmorGlintModule;
import net.visualclient.modules.visuals.FullbrightModule;
import net.visualclient.modules.visuals.HideArmorModule;
import net.visualclient.modules.visuals.PackMixerModule;
import net.visualclient.modules.visuals.SkyColorModule;
import net.visualclient.modules.visuals.WeatherModule;

/** Every module of the client, in the order they appear in the menu. */
public final class Modules {
	public static final List<ClientModule> ALL = List.of(
			// Combat
			DamageTintModule.INSTANCE,
			new ArmorHudModule(),
			new TotemCounterModule(),
			new PotionHudModule(),
			// Visuals
			WeatherModule.INSTANCE,
			SkyColorModule.INSTANCE,
			ArmorGlintModule.INSTANCE,
			PackMixerModule.INSTANCE,
			FullbrightModule.INSTANCE,
			HideArmorModule.INSTANCE,
			// Player
			new AutoSprintModule(),
			new SaturationModule(),
			ShulkerTooltipModule.INSTANCE,
			// Misc
			new FpsModule(),
			new PingModule(),
			new MusicHudModule(),
			DiscordRpcModule.INSTANCE,
			ThemeModule.INSTANCE);

	private static final Map<Category, List<ClientModule>> BY_CATEGORY = new EnumMap<>(Category.class);
	private static final List<HudModule> HUD = new ArrayList<>();

	static {
		for (Category c : Category.values()) {
			BY_CATEGORY.put(c, new ArrayList<>());
		}
		for (ClientModule m : ALL) {
			BY_CATEGORY.get(m.category()).add(m);
			if (m instanceof HudModule h) {
				HUD.add(h);
			}
		}
	}

	private Modules() {
	}

	public static List<ClientModule> of(Category category) {
		return BY_CATEGORY.get(category);
	}

	public static List<HudModule> hud() {
		return HUD;
	}
}
