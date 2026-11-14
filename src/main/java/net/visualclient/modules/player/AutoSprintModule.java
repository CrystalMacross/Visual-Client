package net.visualclient.modules.player;

import net.minecraft.client.Minecraft;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;

/** Holds the sprint key for you while you walk forward. The game still applies its own sprint rules. */
public final class AutoSprintModule extends ClientModule {
	private boolean pressedByUs;

	public AutoSprintModule() {
		super("Auto Sprint", "Sprints automatically while you move forward.", Category.PLAYER);
	}

	@Override
	protected void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.screen != null) {
			return;
		}
		if (mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()) {
			mc.options.keySprint.setDown(true);
			pressedByUs = true;
		} else if (pressedByUs) {
			mc.options.keySprint.setDown(false);
			pressedByUs = false;
		}
	}

	@Override
	protected void onDisable() {
		Minecraft mc = Minecraft.getInstance();
		if (pressedByUs && mc != null && mc.options != null) {
			mc.options.keySprint.setDown(false);
		}
		pressedByUs = false;
	}
}
