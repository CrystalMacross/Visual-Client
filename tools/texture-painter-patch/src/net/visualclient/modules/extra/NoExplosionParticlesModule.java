package net.visualclient.modules.extra;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.modules.visuals.FpsBoostModule;

/**
 * Hides all explosion particles (TNT, creepers, end crystals, beds, respawn anchors ...).
 *
 * Works through the particle filter that FpsBoostModule.dropParticle already has for every
 * spawned particle; the patcher routes its decisions through the helpers below, so this module
 * works whether FPS Boost is on or off and never touches other particles.
 */
public final class NoExplosionParticlesModule extends ClientModule {

	public static final NoExplosionParticlesModule INSTANCE = new NoExplosionParticlesModule();

	private NoExplosionParticlesModule() {
		super("No Explosion Particles", "Hides explosion particles from TNT, creepers, end crystals, beds and respawn anchors.", Category.EXTRA);
	}

	/** Replaces FpsBoostModule.isEnabled() at the start of dropParticle. */
	public static boolean filterActive(ClientModule fpsBoost) {
		return fpsBoost.isEnabled() || INSTANCE.isEnabled();
	}

	/** Explosion particles: none while this module is on, otherwise FPS Boost's percentage. */
	public static double explosionPercent(NumberSetting fpsBoostSetting) {
		if (INSTANCE.isEnabled()) return 0;
		return FpsBoostModule.INSTANCE.isEnabled() ? fpsBoostSetting.asDouble() : 100;
	}

	/** Every other particle: FPS Boost's percentage only while FPS Boost itself is on. */
	public static double otherPercent(NumberSetting fpsBoostSetting) {
		return FpsBoostModule.INSTANCE.isEnabled() ? fpsBoostSetting.asDouble() : 100;
	}
}
