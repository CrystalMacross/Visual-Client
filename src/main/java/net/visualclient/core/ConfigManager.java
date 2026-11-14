package net.visualclient.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.visualclient.util.Log;
import net.visualclient.util.MiniJson;

/** Loads and saves config/visualclient.json. Saving is debounced so dragging sliders stays smooth. */
public final class ConfigManager {
	private static final int VERSION = 1;
	private static final long AUTOSAVE_MS = 4000L;

	private static Path file;
	private static List<ClientModule> modules = List.of();
	private static Map<String, Object> extra = new LinkedHashMap<>();
	private static volatile boolean dirty;
	private static long lastSave;

	private ConfigManager() {
	}

	public static void init(Path configDir, List<ClientModule> allModules) {
		file = configDir.resolve("visualclient.json");
		modules = allModules;
	}

	public static void markDirty() {
		dirty = true;
	}

	/** A named block of free-form data owned by another component (pack mixer, menu layout, ...). */
	public static Map<String, Object> section(String name) {
		Object o = extra.get(name);
		Map<String, Object> m = MiniJson.asMap(o);
		if (m == null) {
			m = new LinkedHashMap<>();
			extra.put(name, m);
		}
		return m;
	}

	public static void load() {
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			Map<String, Object> root = MiniJson.asMap(MiniJson.parse(text));
			if (root == null) {
				return;
			}
			Map<String, Object> mods = MiniJson.asMap(root.get("modules"));
			for (ClientModule m : modules) {
				if (mods != null && mods.containsKey(m.name())) {
					m.fromJson(mods.get(m.name()));
				}
			}
			Map<String, Object> loadedExtra = MiniJson.asMap(root.get("extra"));
			if (loadedExtra != null) {
				extra = loadedExtra;
			}
		} catch (Exception e) {
			Log.error("Could not read visualclient.json, using defaults. A backup was kept.", e);
			try {
				Files.copy(file, file.resolveSibling("visualclient.json.broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// nothing else we can do
			}
		}
		dirty = false;
	}

	/** Call every client tick. Writes the file at most every few seconds. */
	public static void tick() {
		if (dirty && System.currentTimeMillis() - lastSave >= AUTOSAVE_MS) {
			saveNow();
		}
	}

	public static void saveNow() {
		if (file == null) {
			return;
		}
		dirty = false;
		lastSave = System.currentTimeMillis();
		try {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("version", VERSION);
			Map<String, Object> mods = new LinkedHashMap<>();
			for (ClientModule m : modules) {
				mods.put(m.name(), m.toJson());
			}
			root.put("modules", mods);
			root.put("extra", extra);
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("visualclient.json.tmp");
			Files.writeString(tmp, MiniJson.pretty(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			Log.error("Could not save visualclient.json", e);
		}
	}
}
