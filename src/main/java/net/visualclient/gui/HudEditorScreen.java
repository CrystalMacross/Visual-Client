package net.visualclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.visualclient.core.ConfigManager;
import net.visualclient.hud.HudModule;
import net.visualclient.modules.Modules;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;

/** Drag the HUD boxes where you want them. Boxes snap to the screen edges. */
public final class HudEditorScreen extends Screen {
	private static final int SNAP = 5;

	private final Screen parent;
	private final Hits hits = new Hits();
	private HudModule dragging;
	private int grabX;
	private int grabY;

	public HudEditorScreen(Screen parent) {
		super(Component.literal("HUD layout"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		ConfigManager.saveNow();
		this.minecraft.setScreen(parent);
	}

	private HudModule boxAt(double mx, double my) {
		var list = Modules.hud();
		for (int i = list.size() - 1; i >= 0; i--) {
			HudModule m = list.get(i);
			if (m.isEnabled() && m.hasContent() && Hits.inside(mx, my, m.boundsX() - 2, m.boundsY() - 2, m.boundsW() + 4, m.boundsH() + 4)) {
				return m;
			}
		}
		return null;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (Theme.dimBackground()) {
			super.render(g, mouseX, mouseY, partialTick);
		}
		hits.clear();
		Minecraft mc = this.minecraft;
		Font font = this.font;
		HudModule hover = dragging == null ? boxAt(mouseX, mouseY) : dragging;
		int shown = 0;
		for (HudModule m : Modules.hud()) {
			if (!m.isEnabled()) {
				continue;
			}
			m.renderHud(g, mc, true);
			if (!m.hasContent()) {
				continue;
			}
			shown++;
			boolean active = m == hover;
			int color = active ? Theme.accent() : ColorUtil.withAlpha(0xFFFFFF, 90);
			Render2D.outline(g, m.boundsX() - 2, m.boundsY() - 2, m.boundsW() + 4, m.boundsH() + 4, color);
			int labelW = font.width(m.name());
			boolean room = m.boundsX() + m.boundsW() + 8 + labelW < this.width;
			int labelX = room ? m.boundsX() + m.boundsW() + 8 : m.boundsX() - 8 - labelW;
			int labelY = m.boundsY() + Math.max(0, (m.boundsH() - 8) / 2);
			Render2D.text(g, font, m.name(), labelX, labelY, active ? Theme.text() : Theme.muted());
		}

		String help = shown == 0
				? "No HUD boxes are switched on. Turn modules on in the menu first."
				: "Drag a box to move it. Esc or Done saves the layout.";
		Render2D.textCenter(g, font, help, this.width / 2, 14, Theme.text());

		int bh = 16;
		int by = this.height - bh - 10;
		int doneW = 70;
		int resetW = 110;
		int gap = 8;
		int startX = this.width / 2 - (doneW + resetW + gap) / 2;

		boolean doneHover = Hits.inside(mouseX, mouseY, startX, by, doneW, bh);
		Render2D.round(g, startX, by, doneW, bh, 3, doneHover ? ColorUtil.brighten(Theme.accent(), 0.15f) : Theme.accent());
		Render2D.textCenter(g, font, "Done", startX + doneW / 2, by + 4, 0xFF101018);
		hits.add(startX, by, doneW, bh).left = (a, b) -> onClose();

		int rx = startX + doneW + gap;
		boolean resetHover = Hits.inside(mouseX, mouseY, rx, by, resetW, bh);
		Render2D.round(g, rx, by, resetW, bh, 3, resetHover ? Theme.rowHover() : Theme.background());
		Render2D.textCenter(g, font, "Reset positions", rx + resetW / 2, by + 4, Theme.text());
		hits.add(rx, by, resetW, bh).left = (a, b) -> {
			for (HudModule m : Modules.hud()) {
				m.resetPosition();
			}
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (event.button() == 0) {
			Hits.Hit hit = hits.find(mx, my);
			if (hit != null && hit.left != null) {
				hit.left.run(mx, my);
				return true;
			}
			HudModule box = boxAt(mx, my);
			if (box != null) {
				dragging = box;
				grabX = (int) mx - box.boundsX();
				grabY = (int) my - box.boundsY();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging != null) {
			int nx = (int) event.x() - grabX;
			int ny = (int) event.y() - grabY;
			int w = dragging.boundsW();
			int h = dragging.boundsH();
			nx = Math.max(0, Math.min(nx, Math.max(0, this.width - w)));
			ny = Math.max(0, Math.min(ny, Math.max(0, this.height - h)));
			if (nx < SNAP) {
				nx = 0;
			} else if (Math.abs(nx + w - this.width) < SNAP) {
				nx = this.width - w;
			}
			if (ny < SNAP) {
				ny = 0;
			} else if (Math.abs(ny + h - this.height) < SNAP) {
				ny = this.height - h;
			}
			dragging.moveTo(nx, ny);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			ConfigManager.markDirty();
			return true;
		}
		return super.mouseReleased(event);
	}
}
