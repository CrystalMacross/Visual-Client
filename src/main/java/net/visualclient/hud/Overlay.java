package net.visualclient.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Something that draws on the in-game HUD every frame while its module is on. */
public interface Overlay {
	void renderOverlay(GuiGraphics graphics, Minecraft mc);
}
