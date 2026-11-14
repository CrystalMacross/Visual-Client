package net.visualclient.modules.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.hud.Overlay;
import net.visualclient.render.Render2D;
import net.visualclient.util.ColorUtil;

/** Draws your hidden saturation on top of the hunger bar. */
public final class SaturationModule extends ClientModule implements Overlay {
	private final ModeSetting style = add(new ModeSetting("Style", "On icons", "On icons", "Bar"));
	private final ColorSetting color = add(new ColorSetting("Color", 0xFFFFC857, false));
	private final BoolSetting value = add(new BoolSetting("Show number", true));

	public SaturationModule() {
		super("Saturation Overlay", "Shows how much saturation you have on top of the hunger bar.", Category.PLAYER);
	}

	@Override
	public void renderOverlay(GuiGraphics g, Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || player.isCreative() || player.isSpectator() || player.getVehicle() instanceof LivingEntity) {
			return;
		}
		FoodData food = player.getFoodData();
		float saturation = food.getSaturationLevel();
		int right = g.guiWidth() / 2 + 91;
		int top = g.guiHeight() - 39;
		int gold = color.argb();

		if (style.is("On icons")) {
			// Each hunger icon stands for 2 saturation points. Fill it from the bottom, like a liquid level.
			for (int i = 0; i < 10; i++) {
				float points = Math.max(0f, Math.min(2f, saturation - 2f * i));
				if (points <= 0f) {
					break;
				}
				int iconX = right - i * 8 - 9;
				int filled = Math.max(1, Math.round(7f * points / 2f));
				Render2D.rect(g, iconX + 1, top + 8 - filled, 7, filled, ColorUtil.withAlpha(gold, 150));
			}
		} else {
			int barWidth = 81;
			int fill = Math.round(barWidth * Math.max(0f, Math.min(1f, saturation / 20f)));
			Render2D.rect(g, right - barWidth, top - 4, barWidth, 2, 0x66000000);
			Render2D.rect(g, right - barWidth, top - 4, fill, 2, gold);
		}
		if (value.on()) {
			String text = String.format(java.util.Locale.ROOT, "%.1f", saturation);
			Render2D.textShadow(g, mc.font, text, right - mc.font.width(text), top - 14, gold);
		}
	}
}
