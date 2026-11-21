package net.visualclient.modules.extra;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.core.setting.TextSetting;

/**
 * Size of dropped items on the ground: one size for all items plus optional sizes per item
 * ("diamond=2, totem_of_undying=3").
 *
 * The patcher adds ItemSizeMixin to every Minecraft build: it calls {@link #captureState} when
 * an item entity's render state is extracted and {@link #scale} at the start of the item
 * renderer's submit/render. Minecraft types are passed as Object and reached through method
 * handles found by name, so this class is the same in every build.
 */
public final class ItemSizeModule extends ClientModule {

	public static final ItemSizeModule INSTANCE = new ItemSizeModule();

	private final NumberSetting size = add(new NumberSetting("Size", 1.5, 0.25, 5.0, 0.05));
	private final TextSetting perItem = add(new TextSetting("Per item", "", 256, "diamond=2, totem_of_undying=3"));

	private static final Map<Object, Float> STATE_SCALE = new WeakHashMap<>();
	private static MethodHandle getItem;
	private static MethodHandle scaleHandle;
	private static boolean resolved;

	private String parsedFrom;
	private Map<String, Float> parsed = new HashMap<>();

	private ItemSizeModule() {
		super("Item Size", "Changes how big dropped items look on the ground - for all items or per item.", Category.EXTRA);
		size.suffix("x");
		size.describe("Size of every dropped item (1.0 = normal).");
		perItem.describe("Own size per item, e.g. diamond=2, totem_of_undying=3, minecraft:ender_pearl=0.5");
	}

	/** Remembers the size for an item entity's render state (called when the state is extracted). */
	public static void captureState(Object itemEntity, Object state) {
		if (!INSTANCE.isEnabled() || state == null) return;
		float s = INSTANCE.sizeFor(itemId(itemEntity));
		synchronized (STATE_SCALE) {
			STATE_SCALE.put(state, s);
		}
	}

	/** Scales the pose at the start of the item renderer (the entity dispatcher pops it afterwards). */
	public static void scale(Object state, Object poseStack) {
		if (!INSTANCE.isEnabled() || poseStack == null) return;
		Float s;
		synchronized (STATE_SCALE) {
			s = STATE_SCALE.get(state);
		}
		float f = s != null ? s : (float) INSTANCE.size.asDouble();
		if (Math.abs(f - 1f) < 1e-3f) return;
		resolve(null, poseStack);
		if (scaleHandle == null) return;
		try {
			scaleHandle.invoke(poseStack, f, f, f);
		} catch (Throwable ignored) {
			// leave the item at its normal size
		}
	}

	private float sizeFor(String id) {
		String text = perItem.get();
		if (text == null) text = "";
		if (!text.equals(parsedFrom)) {
			parsedFrom = text;
			parsed = parse(text);
		}
		if (id != null && !parsed.isEmpty()) {
			Float v = parsed.get(id);
			if (v == null && id.startsWith("minecraft:")) v = parsed.get(id.substring(10));
			if (v != null) return v;
		}
		return (float) size.asDouble();
	}

	private static Map<String, Float> parse(String text) {
		Map<String, Float> out = new HashMap<>();
		for (String part : text.split("[,;]")) {
			int eq = part.indexOf('=');
			if (eq <= 0) continue;
			String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
			try {
				float v = Float.parseFloat(part.substring(eq + 1).trim().replace(',', '.'));
				if (v > 0 && v <= 10) {
					if (key.startsWith("minecraft:")) key = key.substring(10);
					out.put(key, v);
				}
			} catch (NumberFormatException ignored) {
				// skip broken entries
			}
		}
		return out;
	}

	/** "minecraft:diamond" for an item entity (ItemStack.toString() is "<count> <item id>"). */
	private static String itemId(Object itemEntity) {
		if (itemEntity == null) return null;
		resolve(itemEntity, null);
		if (getItem == null) return null;
		try {
			Object stack = getItem.invoke(itemEntity);
			if (stack == null) return null;
			String s = String.valueOf(stack);
			int sp = s.indexOf(' ');
			String id = (sp >= 0 ? s.substring(sp + 1) : s).trim().toLowerCase(Locale.ROOT);
			return id.contains(":") ? id : "minecraft:" + id;
		} catch (Throwable t) {
			return null;
		}
	}

	private static synchronized void resolve(Object itemEntity, Object poseStack) {
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		if (getItem == null && itemEntity != null) {
			for (String name : new String[] {"getItem", "method_6983"}) {
				try {
					var m = itemEntity.getClass().getMethod(name);
					if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive()) {
						getItem = lookup.unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
						break;
					}
				} catch (Throwable ignored) {
					// try the next name
				}
			}
		}
		if (scaleHandle == null && poseStack != null && !resolved) {
			resolved = true;
			for (String name : new String[] {"scale", "method_22905"}) {
				try {
					var m = poseStack.getClass().getMethod(name, float.class, float.class, float.class);
					scaleHandle = lookup.unreflect(m).asType(MethodType.methodType(void.class, Object.class, float.class, float.class, float.class));
					break;
				} catch (Throwable ignored) {
					// try the next name
				}
			}
		}
	}
}
