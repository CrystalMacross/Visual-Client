package net.visualclient.modules.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.TextSetting;
import net.visualclient.discord.DiscordRpc;
import net.visualclient.hud.HasStatus;

/** Shows "Playing Minecraft" on your Discord profile. Talks only to the Discord app on your PC. */
public final class DiscordRpcModule extends ClientModule implements HasStatus {
	public static final DiscordRpcModule INSTANCE = new DiscordRpcModule();

	private final TextSetting applicationId = add(new TextSetting("Application ID", "", 32, "Paste it from discord.com/developers"));
	private final TextSetting line = add(new TextSetting("First line", "Playing Minecraft 1.21.11", 60, ""));
	private final BoolSetting showServer = add(new BoolSetting("Show server address", false));
	private final BoolSetting showTime = add(new BoolSetting("Show elapsed time", true));
	private final TextSetting imageKey = add(new TextSetting("Image key", "logo", 40, "Name of your art asset"));

	private long startEpoch = System.currentTimeMillis() / 1000L;
	private int counter;

	private DiscordRpcModule() {
		super("Discord RPC", "Shows what you are playing on your Discord profile.", Category.MISC);
		applicationId.describe("Create an application at discord.com/developers and paste its Application ID here.");
		applicationId.onChange(id -> {
			if (isEnabled()) {
				DiscordRpc.start(id);
			}
		});
	}

	@Override
	public String statusLine() {
		return isEnabled() ? DiscordRpc.status() : "Off";
	}

	@Override
	protected void onEnable() {
		startEpoch = System.currentTimeMillis() / 1000L;
		DiscordRpc.start(applicationId.get());
		counter = 0;
	}

	@Override
	protected void onDisable() {
		DiscordRpc.stop();
	}

	@Override
	protected void tick() {
		if (counter++ % 20 != 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		String state;
		if (mc.level == null) {
			state = "In the menus";
		} else if (mc.isSingleplayer()) {
			state = "Singleplayer";
		} else {
			ServerData server = mc.getCurrentServer();
			state = showServer.on() && server != null ? server.ip : "Multiplayer";
		}
		DiscordRpc.update(new DiscordRpc.Activity(line.get(), state, showTime.on() ? startEpoch : 0L, imageKey.get(), "Visual Client"));
	}
}
