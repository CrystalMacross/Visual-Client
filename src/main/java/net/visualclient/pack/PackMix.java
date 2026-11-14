package net.visualclient.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.visualclient.core.ConfigManager;
import net.visualclient.util.MiniJson;

/** Settings of the Pack Mixer: which categories each pack may contribute, and saved presets. */
public final class PackMix {
	public record Preset(String name, List<String> order, Map<String, Integer> masks) {
	}

	private static final Map<String, Integer> MASKS = new ConcurrentHashMap<>();
	private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
	private static volatile boolean active;

	private PackMix() {
	}

	/** Filters only apply while the Pack Mixer module is switched on. */
	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	/** Bit mask of allowed categories for a pack; ALL when the pack is not filtered. */
	public static int maskOf(String packId) {
		return MASKS.getOrDefault(packId, PackCategory.ALL);
	}

	public static boolean isFiltered(String packId) {
		return MASKS.containsKey(packId);
	}

	public static void setMask(String packId, int mask) {
		mask &= PackCategory.ALL;
		if (mask == PackCategory.ALL) {
			MASKS.remove(packId);
		} else {
			MASKS.put(packId, mask);
		}
		save();
	}

	public static Map<String, Integer> masksSnapshot() {
		return new LinkedHashMap<>(MASKS);
	}

	public static void replaceMasks(Map<String, Integer> masks) {
		MASKS.clear();
		for (Map.Entry<String, Integer> e : masks.entrySet()) {
			int m = e.getValue() & PackCategory.ALL;
			if (m != PackCategory.ALL) {
				MASKS.put(e.getKey(), m);
			}
		}
		save();
	}

	public static List<Preset> presets() {
		return new ArrayList<>(PRESETS.values());
	}

	public static void savePreset(String name, List<String> order, Map<String, Integer> masks) {
		PRESETS.put(name, new Preset(name, new ArrayList<>(order), new LinkedHashMap<>(masks)));
		save();
	}

	public static void deletePreset(String name) {
		PRESETS.remove(name);
		save();
	}

	// ---- persistence ------------------------------------------------------------------------------

	public static void load() {
		Map<String, Object> section = ConfigManager.section("packmix");
		MASKS.clear();
		PRESETS.clear();
		Map<String, Object> masks = MiniJson.asMap(section.get("masks"));
		if (masks != null) {
			for (Map.Entry<String, Object> e : masks.entrySet()) {
				int m = (int) MiniJson.num(e.getValue(), PackCategory.ALL) & PackCategory.ALL;
				if (m != PackCategory.ALL) {
					MASKS.put(e.getKey(), m);
				}
			}
		}
		List<Object> presets = MiniJson.asList(section.get("presets"));
		if (presets != null) {
			for (Object o : presets) {
				Map<String, Object> p = MiniJson.asMap(o);
				if (p == null) {
					continue;
				}
				String name = MiniJson.str(p.get("name"), "");
				if (name.isBlank()) {
					continue;
				}
				List<String> order = new ArrayList<>();
				List<Object> rawOrder = MiniJson.asList(p.get("order"));
				if (rawOrder != null) {
					for (Object id : rawOrder) {
						if (id instanceof String s) {
							order.add(s);
						}
					}
				}
				Map<String, Integer> pm = new LinkedHashMap<>();
				Map<String, Object> rawMasks = MiniJson.asMap(p.get("masks"));
				if (rawMasks != null) {
					for (Map.Entry<String, Object> e : rawMasks.entrySet()) {
						pm.put(e.getKey(), (int) MiniJson.num(e.getValue(), PackCategory.ALL));
					}
				}
				PRESETS.put(name, new Preset(name, order, pm));
			}
		}
	}

	public static void save() {
		Map<String, Object> section = ConfigManager.section("packmix");
		section.put("masks", new LinkedHashMap<String, Object>(MASKS));
		List<Object> list = new ArrayList<>();
		for (Preset p : PRESETS.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", p.name());
			m.put("order", p.order());
			m.put("masks", new LinkedHashMap<String, Object>(p.masks()));
			list.add(m);
		}
		section.put("presets", list);
		ConfigManager.markDirty();
	}
}
