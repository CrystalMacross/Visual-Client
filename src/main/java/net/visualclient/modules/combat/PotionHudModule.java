package net.visualclient.modules.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.hud.Overlay;
import net.visualclient.render.Render2D;
import net.visualclient.util.ColorUtil;
import net.visualclient.util.TextUtil;

/** Writes the remaining time of every potion effect under (or inside) the vanilla effect boxes. */
public final class PotionHudModule extends ClientModule implements Overlay {
	private final ModeSetting position = add(new ModeSetting("Time shown", "Auto", "Auto", "Below box", "Inside box"));
	private final BoolSetting level = add(new BoolSetting("Show level", true));
	private final NumberSetting warnUnder = add(new NumberSetting("Warn under", 10, 0, 60, 1).suffix(" s"));
	private final ColorSetting textColor = add(new ColorSetting("Text color", 0xFFFFFFFF, false));
	private final ColorSetting warnColor = add(new ColorSetting("Warning color", 0xFFFF6B6B, false));

	public PotionHudModule() {
		super("Potion HUD", "Shows how long your potion effects last, right under the effect boxes.", Category.COMBAT);
		defaultEnabled(true);
		position.describe("Auto: under the box, and inside it while a second row of effects would be covered.");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void sortLikeVanilla(List<MobEffectInstance> list) {
		try {
			list.sort((Comparator) Collections.reverseOrder());
		} catch (RuntimeException ignored) {
			// not comparable in this version: keep the game's order
		}
	}

	@Override
	public void renderOverlay(GuiGraphics g, Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.screen instanceof AbstractContainerScreen<?>) {
			return;
		}
		Collection<MobEffectInstance> effects = player.getActiveEffects();
		if (effects.isEmpty()) {
			return;
		}
		List<MobEffectInstance> sorted = new ArrayList<>(effects);
		sortLikeVanilla(sorted);
		Font font = mc.font;
		int screenWidth = g.guiWidth();
		boolean alwaysInside = position.is("Inside box");
		boolean auto = position.is("Auto");
		boolean anyHarmful = false;
		if (auto) {
			for (MobEffectInstance e : sorted) {
				if (e.showIcon() && !e.getEffect().value().isBeneficial()) {
					anyHarmful = true;
					break;
				}
			}
		}
		int beneficial = 0;
		int harmful = 0;
		float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 140.0);
		for (MobEffectInstance effect : sorted) {
			if (!effect.showIcon()) {
				continue;
			}
			int boxX = screenWidth;
			int boxY = 1;
			boolean good = effect.getEffect().value().isBeneficial();
			if (good) {
				boxX -= 25 * ++beneficial;
			} else {
				boxX -= 25 * ++harmful;
				boxY += 26;
			}
			boolean inside = alwaysInside || (auto && good && anyHarmful);
			boolean infinite = effect.isInfiniteDuration();
			int seconds = infinite ? Integer.MAX_VALUE : effect.getDuration() / 20;
			String text = infinite ? "**" : TextUtil.mmss(seconds);
			boolean warn = !infinite && seconds <= warnUnder.asInt() && warnUnder.asInt() > 0;
			int color = warn ? ColorUtil.mulAlpha(warnColor.argb(), pulse) : textColor.argb();
			int textWidth = font.width(text);
			int textX = boxX + 12 - textWidth / 2;
			int textY = inside ? boxY + 14 : boxY + 25;
			Render2D.round(g, textX - 2, textY - 1, textWidth + 4, 10, 2, 0x99000000);
			Render2D.text(g, font, text, textX, textY, color);
			if (level.on() && effect.getAmplifier() > 0) {
				String lv = TextUtil.roman(effect.getAmplifier() + 1);
				Render2D.textShadow(g, font, lv, boxX + 22 - font.width(lv), boxY + 2, 0xFFFFFFFF);
			}
		}
	}
}
