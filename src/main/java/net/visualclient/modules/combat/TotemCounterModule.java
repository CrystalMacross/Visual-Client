package net.visualclient.modules.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.hud.HudModule;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;

/** A small box with the totem icon and how many Totems of Undying you carry. */
public final class TotemCounterModule extends HudModule {
	private final BoolSetting hideAtZero = add(new BoolSetting("Hide when you have none", false));
	private final BoolSetting warn = add(new BoolSetting("Red at zero", true));

	private int count;
	private String countText = "0";
	private ItemStack icon;

	public TotemCounterModule() {
		super("Totem Counter", "Counts the Totems of Undying in your inventory and off hand.", Category.COMBAT);
		commonSettings();
	}

	@Override
	protected void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		int total = 0;
		Inventory inventory = player.getInventory();
		int size = Math.min(36, inventory.getContainerSize());
		for (int i = 0; i < size; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(Items.TOTEM_OF_UNDYING)) {
				total += stack.getCount();
			}
		}
		ItemStack off = player.getOffhandItem();
		if (off.is(Items.TOTEM_OF_UNDYING)) {
			total += off.getCount();
		}
		if (total != count) {
			count = total;
			countText = Integer.toString(total);
		}
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return 50;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		if (hideAtZero.on() && count == 0 && !editing) {
			contentWidth = 0;
			contentHeight = 0;
			return;
		}
		if (icon == null) {
			icon = new ItemStack(Items.TOTEM_OF_UNDYING);
		}
		Font font = mc.font;
		String text = countText;
		int w = 8 + 16 + 5 + font.width(text) + 5;
		int h = 24;
		contentWidth = w;
		contentHeight = h;
		box(g, w, h);
		g.renderItem(icon, 7, 4);
		int color = count == 0 && warn.on() ? Theme.BAD : fg();
		Render2D.text(g, font, text, 7 + 16 + 5, 8, color);
	}
}
