package net.visualclient;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.visualclient.core.ClientModule;
import net.visualclient.core.ConfigManager;
import net.visualclient.glint.GlintTextures;
import net.visualclient.gui.ClickGuiScreen;
import net.visualclient.gui.HudEditorScreen;
import net.visualclient.gui.PackMixerScreen;
import net.visualclient.hud.HudModule;
import net.visualclient.hud.HudRenderer;
import net.visualclient.modules.Modules;
import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.modules.player.ShulkerTooltipModule;
import net.visualclient.modules.visuals.PackMixerModule;
import net.visualclient.pack.PackMix;
import net.visualclient.util.Log;

import org.lwjgl.glfw.GLFW;

public final class VisualClient implements ClientModInitializer {
	public static final String MOD_ID = "visualclient";

	private static KeyMapping openMenuKey;
	private static boolean started;

	@Override
	public void onInitializeClient() {
		// 1. Load the saved settings first. The Pack Mixer must know its filters before the first resource load.
		ConfigManager.init(FabricLoader.getInstance().getConfigDir(), Modules.ALL);
		ConfigManager.load();
		PackMix.load();
		PackMix.setActive(PackMixerModule.INSTANCE.isEnabled());

		// 2. Buttons inside module settings that open screens.
		ThemeModule.openHudEditor = () -> {
			Minecraft mc = Minecraft.getInstance();
			mc.setScreen(new HudEditorScreen(mc.screen));
		};
		ThemeModule.resetHudPositions = () -> {
			for (HudModule m : Modules.hud()) {
				m.resetPosition();
			}
		};
		PackMixerModule.openMixer = () -> {
			Minecraft mc = Minecraft.getInstance();
			mc.setScreen(new PackMixerScreen(mc.screen));
		};

		// 3. Key to open the menu (Right Shift by default, can be changed in Controls).
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openMenuKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.visualclient.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

		// 4. HUD, tooltips, tick and shutdown.
		HudRenderer.register();
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> ShulkerTooltipModule.onTooltip(stack, lines));
		ClientTickEvents.END_CLIENT_TICK.register(VisualClient::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			for (ClientModule m : Modules.ALL) {
				m.shutdown();
			}
			ConfigManager.saveNow();
		});
		Log.info("Visual Client loaded. Press Right Shift in game to open the menu.");
	}

	private static void onTick(Minecraft mc) {
		if (!started) {
			started = true;
			for (ClientModule m : Modules.ALL) {
				m.startup();
			}
		}
		for (ClientModule m : Modules.ALL) {
			m.runTick();
		}
		GlintTextures.tick();
		while (openMenuKey.consumeClick()) {
			if (mc.screen == null) {
				mc.setScreen(new ClickGuiScreen());
			}
		}
		ConfigManager.tick();
	}
}
