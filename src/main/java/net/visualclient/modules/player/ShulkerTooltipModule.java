package net.visualclient.modules.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;

/** Replaces the short vanilla list in shulker box tooltips with the full contents and amounts. */
public final class ShulkerTooltipModule extends ClientModule {
	private static final Set<String> VANILLA_KEYS = Set.of(
			"container.shulkerBox.itemCount", "container.shulkerBox.more", "container.shulkerBox.unknownContents");

	public static final ShulkerTooltipModule INSTANCE = new ShulkerTooltipModule();

	private final NumberSetting maxRows = add(new NumberSetting("Max rows", 14, 3, 27, 1));
	private final ModeSetting sort = add(new ModeSetting("Sort by", "Amount", "Amount", "Name"));
	private final BoolSetting header = add(new BoolSetting("Show summary", true));
	private final BoolSetting replaceVanilla = add(new BoolSetting("Replace vanilla list", true));

	private static final class Entry {
		final ItemStack sample;
		int count;

		Entry(ItemStack sample) {
			this.sample = sample;
		}
	}

	private ShulkerTooltipModule() {
		super("Shulker Tooltips", "Shows every item inside a shulker box and how many there are, right in the tooltip.", Category.PLAYER);
	}

	/** Called by the Fabric tooltip event for every item tooltip. */
	public static void onTooltip(ItemStack stack, List<Component> lines) {
		ShulkerTooltipModule m = INSTANCE;
		if (!m.isEnabled()) {
			return;
		}
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents == null) {
			return;
		}
		Map<Item, Entry> totals = new LinkedHashMap<>();
		int stacks = 0;
		int items = 0;
		for (ItemStack inside : contents.nonEmptyItems()) {
			stacks++;
			items += inside.getCount();
			totals.computeIfAbsent(inside.getItem(), k -> new Entry(inside)).count += inside.getCount();
		}
		if (stacks == 0) {
			return;
		}
		if (m.replaceVanilla.on()) {
			lines.removeIf(c -> c.getContents() instanceof TranslatableContents t && VANILLA_KEYS.contains(t.getKey()));
		}
		List<Entry> sorted = new ArrayList<>(totals.values());
		if (m.sort.is("Amount")) {
			sorted.sort(Comparator.comparingInt((Entry e) -> e.count).reversed());
		} else {
			sorted.sort(Comparator.comparing((Entry e) -> e.sample.getHoverName().getString()));
		}
		if (m.header.on()) {
			lines.add(Component.literal(stacks + (stacks == 1 ? " stack" : " stacks") + ", " + items + " items").withStyle(ChatFormatting.GRAY));
		}
		int limit = m.maxRows.asInt();
		int shown = 0;
		for (Entry e : sorted) {
			if (shown >= limit) {
				break;
			}
			MutableComponent row = Component.literal("  ");
			row.append(e.sample.getHoverName());
			row.append(Component.literal("  x" + e.count).withStyle(ChatFormatting.GOLD));
			lines.add(row);
			shown++;
		}
		if (sorted.size() > shown) {
			lines.add(Component.literal("  and " + (sorted.size() - shown) + " more kinds").withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
