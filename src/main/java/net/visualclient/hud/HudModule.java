package net.visualclient.hud;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;

/** A module with a box on the HUD that can be moved in the HUD editor and scaled. */
public abstract class HudModule extends ClientModule implements Overlay {
	protected final NumberSetting scale = new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05);
	protected final BoolSetting useTheme = new BoolSetting("Use theme colors", true);
	protected final ColorSetting backgroundColor = new ColorSetting("Background", 0xCC141B28, true);
	protected final ColorSetting textColorSetting = new ColorSetting("Text", 0xFFEAF0F8, false);
	protected final ColorSetting accentColor = new ColorSetting("Accent", 0xFFFF7A6B, false);

	private int x = Integer.MIN_VALUE;
	private int y = Integer.MIN_VALUE;
	private int boundsX;
	private int boundsY;
	private int boundsW = 40;
	private int boundsH = 18;
	/** Size of the content in unscaled pixels. Subclasses set this while rendering. */
	protected int contentWidth = 60;
	protected int contentHeight = 18;

	protected HudModule(String name, String description, Category category) {
		super(name, description, category);
	}

	/** Adds the settings every HUD box shares. Call it last in the constructor of the subclass. */
	protected final void commonSettings() {
		add(scale);
		add(useTheme);
		add(backgroundColor).visibleIf(() -> !useTheme.on());
		add(textColorSetting).visibleIf(() -> !useTheme.on());
		add(accentColor).visibleIf(() -> !useTheme.on());
	}

	protected abstract int defaultX(int screenWidth, int screenHeight);

	protected abstract int defaultY(int screenWidth, int screenHeight);

	/** Draws the box at (0, 0). The matrix is already moved and scaled. Must set contentWidth/contentHeight. */
	protected abstract void renderContent(GuiGraphics g, Minecraft mc, boolean editing);

	@Override
	public final void renderOverlay(GuiGraphics g, Minecraft mc) {
		renderHud(g, mc, false);
	}

	public final void renderHud(GuiGraphics g, Minecraft mc, boolean editing) {
		float s = scale.asFloat();
		int sw = g.guiWidth();
		int sh = g.guiHeight();
		int w = Math.max(1, Math.round(contentWidth * s));
		int h = Math.max(1, Math.round(contentHeight * s));
		int px = x == Integer.MIN_VALUE ? defaultX(sw, sh) : x;
		int py = y == Integer.MIN_VALUE ? defaultY(sw, sh) : y;
		px = Math.max(0, Math.min(px, Math.max(0, sw - w)));
		py = Math.max(0, Math.min(py, Math.max(0, sh - h)));
		boundsX = px;
		boundsY = py;
		boundsW = w;
		boundsH = h;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) px, (float) py);
		pose.scale(s, s);
		try {
			renderContent(g, mc, editing);
		} finally {
			pose.popMatrix();
		}
	}

	// ---- position, used by the HUD editor ----------------------------------------------------------

	/** False while the module draws nothing (for example "hide when none"). */
	public final boolean hasContent() {
		return contentWidth > 0 && contentHeight > 0;
	}

	public final int boundsX() {
		return boundsX;
	}

	public final int boundsY() {
		return boundsY;
	}

	public final int boundsW() {
		return boundsW;
	}

	public final int boundsH() {
		return boundsH;
	}

	public final void moveTo(int newX, int newY) {
		this.x = newX;
		this.y = newY;
		net.visualclient.core.ConfigManager.markDirty();
	}

	public final void resetPosition() {
		this.x = Integer.MIN_VALUE;
		this.y = Integer.MIN_VALUE;
		net.visualclient.core.ConfigManager.markDirty();
	}

	@Override
	protected void saveExtra(Map<String, Object> out) {
		if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
			out.put("x", x);
			out.put("y", y);
		}
	}

	@Override
	protected void loadExtra(Map<String, Object> in) {
		if (in.get("x") instanceof Number nx && in.get("y") instanceof Number ny) {
			this.x = nx.intValue();
			this.y = ny.intValue();
		}
	}

	// ---- colours and the shared box look -----------------------------------------------------------

	protected final int bg() {
		return useTheme.on() ? Theme.hudBackground() : backgroundColor.argb();
	}

	protected final int fg() {
		return useTheme.on() ? Theme.text() : textColorSetting.argb();
	}

	protected final int muted() {
		return useTheme.on() ? Theme.muted() : net.visualclient.util.ColorUtil.lerp(textColorSetting.argb(), net.visualclient.util.ColorUtil.opaque(backgroundColor.argb()), 0.45f);
	}

	protected final int accent() {
		return useTheme.on() ? Theme.accent() : accentColor.argb();
	}

	protected final int accent2() {
		return useTheme.on() ? Theme.accent2() : accentColor.argb();
	}

	/** Rounded background with a thin accent stripe on the left edge. */
	protected final void box(GuiGraphics g, int w, int h) {
		int r = Math.min(Theme.radius(), 4);
		Render2D.round(g, 0, 0, w, h, r, bg());
		g.fillGradient(0, r, 2, Math.max(r + 1, h - r), accent(), accent2());
	}
}
