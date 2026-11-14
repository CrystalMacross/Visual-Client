package net.visualclient.modules.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.hud.HudModule;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;

public final class FpsModule extends HudModule {
	private final BoolSetting label = add(new BoolSetting("Show label", true));
	private final BoolSetting colorize = add(new BoolSetting("Color by performance", true));

	private long windowStart = System.nanoTime();
	private int frames;
	private int fps;
	private String text = "0";

	public FpsModule() {
		super("FPS Counter", "Shows how many frames per second the game draws.", Category.MISC);
		defaultEnabled(true);
		commonSettings();
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return 6;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		// FPS is measured from the frames this HUD draws, so it needs no game internals.
		frames++;
		long now = System.nanoTime();
		long span = now - windowStart;
		if (span >= 400_000_000L) {
			fps = (int) Math.round(frames * 1.0e9 / span);
			frames = 0;
			windowStart = now;
			text = Integer.toString(fps);
		}
		Font font = mc.font;
		String unit = label.on() ? " FPS" : "";
		int valueWidth = font.width(text);
		int w = 12 + valueWidth + (unit.isEmpty() ? 0 : font.width(unit));
		int h = 18;
		contentWidth = w;
		contentHeight = h;
		box(g, w, h);
		int valueColor = colorize.on() ? (fps >= 60 ? Theme.GOOD : fps >= 30 ? Theme.WARN : Theme.BAD) : fg();
		Render2D.text(g, font, text, 7, 5, valueColor);
		if (!unit.isEmpty()) {
			Render2D.text(g, font, unit, 7 + valueWidth, 5, muted());
		}
	}
}
