package net.visualclient.modules.combat;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.hud.HudModule;

/**
 * Armor HUD (1.20.4): the armor pieces sit in hotbar slots drawn with the game's own hotbar
 * texture, so resource packs restyle it too. Each piece shows the vanilla durability bar;
 * an empty slot shows the faint armor outline of the inventory.
 */
public final class ArmorHudModule extends HudModule {
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
	private static final Identifier[] EMPTY_SLOT = new Identifier[]{
		Identifier.withDefaultNamespace("container/slot/helmet"),
		Identifier.withDefaultNamespace("container/slot/chestplate"),
		Identifier.withDefaultNamespace("container/slot/leggings"),
		Identifier.withDefaultNamespace("container/slot/boots"),
		null,
		Identifier.withDefaultNamespace("container/slot/shield")
	};
	private final BoolSetting hands = this.add(new BoolSetting("Show held items", false));
	private final ItemStack[] stacks = new ItemStack[6];
	private ItemStack[] preview;

	public ArmorHudModule() {
		super("Armor HUD", "Shows your armor in hotbar slots with the durability bar of each piece.", Category.COMBAT);

		for (int i = 0; i < this.stacks.length; i++) {
			this.stacks[i] = ItemStack.EMPTY;
		}

		this.defaultEnabled(true);
		this.commonSettings();
	}

	@Override
	protected void tick() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			this.stacks[0] = p.getItemBySlot(EquipmentSlot.HEAD);
			this.stacks[1] = p.getItemBySlot(EquipmentSlot.CHEST);
			this.stacks[2] = p.getItemBySlot(EquipmentSlot.LEGS);
			this.stacks[3] = p.getItemBySlot(EquipmentSlot.FEET);
			this.stacks[4] = p.getMainHandItem();
			this.stacks[5] = p.getOffhandItem();
		}
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return sh / 2 - 11;
	}

	/** Shown in the HUD editor while you wear nothing: a worn helmet, an empty chest slot, new leggings, used boots. */
	private ItemStack[] demoStacks() {
		if (this.preview == null) {
			this.preview = new ItemStack[]{
				damaged(Items.NETHERITE_HELMET, 380),
				ItemStack.EMPTY,
				damaged(Items.NETHERITE_LEGGINGS, 0),
				damaged(Items.NETHERITE_BOOTS, 120),
				ItemStack.EMPTY,
				ItemStack.EMPTY
			};
		}

		return this.preview;
	}

	private static ItemStack damaged(Item item, int damage) {
		ItemStack s = new ItemStack(item);
		s.setDamageValue(damage);
		return s;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		int slots = this.hands.on() ? 6 : 4;
		ItemStack[] source = this.stacks;
		boolean any = false;

		for (int i = 0; i < slots; i++) {
			any |= !source[i].isEmpty();
		}

		if (!any && editing) {
			source = this.demoStacks();
			any = true;
		}

		if (!any) {
			this.contentWidth = 0;
			this.contentHeight = 0;
			return;
		}

		// hotbar texture (182 x 22): 1 px border, then one 20 px cell per slot, then the 1 px right border
		int cells = 1 + slots * 20;
		this.contentWidth = cells + 1;
		this.contentHeight = 22;
		sprite(g, HOTBAR, 182, 22, 0, 0, 0, 0, cells, 22);
		sprite(g, HOTBAR, 182, 22, 181, 0, cells, 0, 1, 22);
		Font font = mc.font;

		for (int i = 0; i < slots; i++) {
			int x = 3 + i * 20;
			ItemStack stack = source[i];
			if (stack.isEmpty()) {
				if (EMPTY_SLOT[i] != null) {
					sprite(g, EMPTY_SLOT[i], x, 3, 16, 16);
				}
			} else {
				g.renderItem(stack, x, 3);
				g.renderItemDecorations(font, stack, x, 3);
			}
		}
	}

	/** A section of a GUI sprite (GuiGraphics.blitSprite with texture size and offset). */
	private static void sprite(GuiGraphics g, Identifier id, int texW, int texH, int u, int v, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, id, texW, texH, u, v, x, y, w, h);
	}

	/** A whole GUI sprite (GuiGraphics.blitSprite). */
	private static void sprite(GuiGraphics g, Identifier id, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h);
	}
}
