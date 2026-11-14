package net.visualclient.modules.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.hud.HudModule;
import net.visualclient.render.Render2D;
import net.visualclient.util.ColorUtil;

/**
 * Shows the armor you wear (and optionally what is in your hands) with its durability.
 * The texts are built once per tick, so drawing a frame allocates nothing.
 */
public final class ArmorHudModule extends HudModule {
	private final ModeSetting layout = add(new ModeSetting("Layout", "Vertical", "Vertical", "Horizontal"));
	private final ModeSetting durability = add(new ModeSetting("Durability", "Percent", "Percent", "Value", "Off"));
	private final BoolSetting bars = add(new BoolSetting("Durability bars", true));
	private final BoolSetting hands = add(new BoolSetting("Show held items", false));

	private final ItemStack[] stacks = new ItemStack[6];
	private final String[] labels = new String[6];
	private final float[] fractions = new float[6];

	private ItemStack[] preview;
	private final String[] previewLabels = new String[6];
	private final float[] previewFractions = new float[6];

	public ArmorHudModule() {
		super("Armor HUD", "Shows your armor pieces and how much durability is left.", Category.COMBAT);
		for (int i = 0; i < stacks.length; i++) {
			stacks[i] = ItemStack.EMPTY;
			fractions[i] = 1f;
		}
		defaultEnabled(true);
		commonSettings();
	}

	@Override
	protected void tick() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return;
		}
		stacks[0] = p.getItemBySlot(EquipmentSlot.HEAD);
		stacks[1] = p.getItemBySlot(EquipmentSlot.CHEST);
		stacks[2] = p.getItemBySlot(EquipmentSlot.LEGS);
		stacks[3] = p.getItemBySlot(EquipmentSlot.FEET);
		stacks[4] = p.getMainHandItem();
		stacks[5] = p.getOffhandItem();
		for (int i = 0; i < stacks.length; i++) {
			describe(stacks[i], labels, fractions, i);
		}
	}

	/** Works out the durability fraction and the text for one stack. */
	private void describe(ItemStack stack, String[] outLabels, float[] outFractions, int i) {
		float fraction = 1f;
		String label = null;
		if (stack != null && stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int left = max - stack.getDamageValue();
			fraction = max <= 0 ? 1f : Math.max(0f, Math.min(1f, left / (float) max));
			if (!durability.is("Off")) {
				label = durability.is("Percent") ? Math.round(fraction * 100f) + "%" : Integer.toString(left);
			}
		}
		outLabels[i] = label;
		outFractions[i] = fraction;
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return sh / 2 - 40;
	}

	private ItemStack[] demoStacks() {
		if (preview == null) {
			preview = new ItemStack[] {
					damaged(Items.NETHERITE_HELMET, 60), damaged(Items.NETHERITE_CHESTPLATE, 180),
					damaged(Items.NETHERITE_LEGGINGS, 300), damaged(Items.NETHERITE_BOOTS, 40),
					ItemStack.EMPTY, ItemStack.EMPTY
			};
		}
		return preview;
	}

	private static ItemStack damaged(Item item, int damage) {
		ItemStack s = new ItemStack(item);
		s.setDamageValue(damage);
		return s;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		ItemStack[] source = stacks;
		String[] text = labels;
		float[] fraction = fractions;
		if (editing) {
			boolean any = false;
			for (int i = 0; i < 4; i++) {
				any |= !stacks[i].isEmpty();
			}
			if (!any) {
				source = demoStacks();
				for (int i = 0; i < source.length; i++) {
					describe(source[i], previewLabels, previewFractions, i);
				}
				text = previewLabels;
				fraction = previewFractions;
			}
		}
		int limit = hands.on() ? 6 : 4;
		int shown = 0;
		for (int i = 0; i < limit; i++) {
			if (!source[i].isEmpty()) {
				shown++;
			}
		}
		if (shown == 0) {
			contentWidth = 0;
			contentHeight = 0;
			return;
		}
		Font font = mc.font;
		boolean vertical = layout.is("Vertical");
		boolean showText = !durability.is("Off");
		int rowH = 20;
		int textW = 0;
		if (showText) {
			textW = durability.is("Percent") ? font.width("100%") : font.width("2031");
		}
		int cellW = vertical ? 8 + 16 + (showText ? 5 + textW : 0) + 5 : 8 + Math.max(16, textW) + 4;
		int cellH = vertical ? rowH : 16 + (showText ? 11 : 0) + 8;
		int w = vertical ? cellW : cellW * shown - (shown > 0 ? 4 : 0) + 4;
		int h = vertical ? rowH * shown + 4 : cellH;
		contentWidth = w;
		contentHeight = h;
		box(g, w, h);
		int index = 0;
		for (int i = 0; i < limit; i++) {
			ItemStack stack = source[i];
			if (stack.isEmpty()) {
				continue;
			}
			int cx = vertical ? 7 : 6 + index * cellW;
			int cy = vertical ? 4 + index * rowH : 4;
			g.renderItem(stack, cx, cy);
			if (!stack.isDamageableItem() && stack.getCount() > 1) {
				g.renderItemDecorations(font, stack, cx, cy);
			}
			float f = fraction[i];
			int color = ColorUtil.hsv(f * 0.33f, 0.72f, 1f);
			String label = text[i];
			boolean bar = bars.on() && stack.isDamageableItem();
			if (vertical) {
				if (label != null) {
					Render2D.text(g, font, label, cx + 16 + 5, cy + 4, color);
				}
				if (bar) {
					Render2D.rect(g, cx, cy + 17, 16, 1, 0x66000000);
					Render2D.rect(g, cx, cy + 17, Math.max(1, Math.round(16 * f)), 1, color);
				}
			} else {
				if (label != null) {
					Render2D.textCenter(g, font, label, cx + 8, cy + 17, color);
				}
				if (bar) {
					Render2D.rect(g, cx, cy + 16, 16, 1, 0x66000000);
					Render2D.rect(g, cx, cy + 16, Math.max(1, Math.round(16 * f)), 1, color);
				}
			}
			index++;
		}
	}
}
