package net.visualclient.modules.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.hud.HudModule;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;

public final class PingModule extends HudModule {
	private final BoolSetting colorize = add(new BoolSetting("Color by latency", true));

	private int ping = -1;
	private boolean local;
	private int counter;
	private String text = "-";

	public PingModule() {
		super("Ping Display", "Shows your latency to the server in milliseconds.", Category.MISC);
		commonSettings();
	}

	@Override
	protected void tick() {
		if (++counter % 10 != 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		local = mc.isSingleplayer();
		ClientPacketListener connection = mc.getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
		ping = info == null ? -1 : info.getLatency();
		text = local ? "Local" : ping < 0 ? "-" : Integer.toString(ping);
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return 28;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		Font font = mc.font;
		String unit = local || ping < 0 ? "" : " ms";
		int valueWidth = font.width(text);
		int w = 12 + valueWidth + (unit.isEmpty() ? 0 : font.width(unit));
		int h = 18;
		contentWidth = w;
		contentHeight = h;
		box(g, w, h);
		int color = fg();
		if (colorize.on() && !local && ping >= 0) {
			color = ping <= 60 ? Theme.GOOD : ping <= 150 ? Theme.WARN : Theme.BAD;
		}
		Render2D.text(g, font, text, 7, 5, color);
		if (!unit.isEmpty()) {
			Render2D.text(g, font, unit, 7 + valueWidth, 5, muted());
		}
	}
}
