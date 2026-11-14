package net.visualclient.modules.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ModeSetting;

/** Stops armor from being drawn, so skins stay visible. Only affects what you see. */
public final class HideArmorModule extends ClientModule {
	public static final HideArmorModule INSTANCE = new HideArmorModule();

	private final ModeSetting target = add(new ModeSetting("Hide on", "Me", "Me", "Other players", "All players", "Everyone"));
	private final BoolSetting helmet = add(new BoolSetting("Helmet", true));
	private final BoolSetting chestplate = add(new BoolSetting("Chestplate", true));
	private final BoolSetting leggings = add(new BoolSetting("Leggings", true));
	private final BoolSetting boots = add(new BoolSetting("Boots", true));
	private final BoolSetting keepElytra = add(new BoolSetting("Keep elytra visible", true));

	private HideArmorModule() {
		super("Hide Armor", "Hides armor pieces so you can see the skin underneath.", Category.VISUALS);
	}

	/** Called by the mixin for every equipment slot of every humanoid. Returns the stack to render. */
	public static ItemStack filter(LivingEntity entity, EquipmentSlot slot, ItemStack original) {
		HideArmorModule m = INSTANCE;
		if (!m.isEnabled() || original == null || original.isEmpty()) {
			return original;
		}
		boolean hideSlot = switch (slot) {
			case HEAD -> m.helmet.on();
			case CHEST -> m.chestplate.on();
			case LEGS -> m.leggings.on();
			case FEET -> m.boots.on();
			default -> false;
		};
		if (!hideSlot) {
			return original;
		}
		if (slot == EquipmentSlot.CHEST && m.keepElytra.on() && original.is(Items.ELYTRA)) {
			return original;
		}
		boolean self = entity == Minecraft.getInstance().player;
		boolean player = entity instanceof Player;
		boolean match = switch (m.target.get()) {
			case "Me" -> self;
			case "Other players" -> player && !self;
			case "All players" -> player;
			default -> true;
		};
		return match ? ItemStack.EMPTY : original;
	}
}
