package net.visualclient.modules.visuals;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.pack.PackMix;

/** Combine resource packs from your resourcepacks folder and pick which parts of each pack are used. */
public final class PackMixerModule extends ClientModule {
	public static final PackMixerModule INSTANCE = new PackMixerModule();

	/** Set by the mod entry point so this class stays free of screen code. */
	public static Runnable openMixer = () -> { };

	private final ActionSetting open = add(new ActionSetting("Open Pack Mixer", () -> openMixer.run()));

	private PackMixerModule() {
		super("Pack Mixer", "Mix resource packs from your folder. Choose which parts of each pack to use.", Category.VISUALS);
	}

	@Override
	protected void onEnable() {
		PackMix.setActive(true);
	}

	@Override
	protected void onDisable() {
		PackMix.setActive(false);
	}
}
