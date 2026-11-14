package net.visualclient.hud;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.visualclient.VisualClient;
import net.visualclient.core.ClientModule;
import net.visualclient.gui.HudEditorScreen;
import net.visualclient.modules.Modules;
import net.visualclient.util.Log;

/** One HUD element that draws every enabled overlay module. A module that throws is switched off for the session. */
public final class HudRenderer {
	private static ClientModule[] modules = new ClientModule[0];
	private static Overlay[] overlays = new Overlay[0];
	private static boolean[] broken = new boolean[0];

	private HudRenderer() {
	}

	public static void register() {
		List<ClientModule> list = new ArrayList<>();
		for (ClientModule m : Modules.ALL) {
			if (m instanceof Overlay) {
				list.add(m);
			}
		}
		modules = list.toArray(new ClientModule[0]);
		overlays = new Overlay[modules.length];
		for (int i = 0; i < modules.length; i++) {
			overlays[i] = (Overlay) modules[i];
		}
		broken = new boolean[modules.length];
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VisualClient.MOD_ID, "hud"), (graphics, deltaTracker) -> render(graphics));
	}

	private static void render(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		boolean editor = mc.screen instanceof HudEditorScreen;
		for (int i = 0; i < modules.length; i++) {
			ClientModule m = modules[i];
			if (!m.isEnabled() || broken[i] || (editor && m instanceof HudModule)) {
				continue;
			}
			try {
				overlays[i].renderOverlay(graphics, mc);
			} catch (Throwable t) {
				broken[i] = true;
				Log.error("HUD of '" + m.name() + "' crashed and was switched off", t);
			}
		}
	}
}
