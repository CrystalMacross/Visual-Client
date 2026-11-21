package net.visualclient.modules.misc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.ConfigManager;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.TextSetting;
import net.visualclient.modules.Modules;
import net.visualclient.util.MiniJson;

/**
 * Config profiles: save / load named configs and share them as a copy-paste code.
 *
 * Configs live in {@code config/visualclient/configs/<name>.json}. A share code is
 * {@code VC1:} + base64url(deflate(json)) and contains every module's settings
 * (and the HUD layout), so other players can import exactly the same setup.
 */
public final class ConfigsModule extends ClientModule {

	public static final ConfigsModule INSTANCE = new ConfigsModule();

	private static final String CODE_PREFIX = "VC1:";
	private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

	private final TextSetting name;
	private final ActionSetting save;
	private final ActionSetting load;
	private final ActionSetting next;
	private final ActionSetting delete;
	private final ActionSetting copyCode;
	private final TextSetting code;
	private final ActionSetting importCode;

	private ConfigsModule() {
		super("Configs", "Save and load your settings as named configs, or share them with a config code.", configsCategory());
		name = add(new TextSetting("Config name", "default", 32, "e.g. pvp"));
		save = add(new ActionSetting("Save config", this::saveConfig));
		load = add(new ActionSetting("Load config", this::loadConfig));
		next = add(new ActionSetting("Next saved config", this::nextConfig));
		delete = add(new ActionSetting("Delete config", this::deleteConfig));
		copyCode = add(new ActionSetting("Copy config code", this::copyCode));
		code = add(new TextSetting("Config code", "", 32000, "Paste a code here, or leave empty to use the clipboard"));
		importCode = add(new ActionSetting("Import config code", this::importCode));
		save.describe("Saves all current settings under the config name.");
		load.describe("Loads the config with this name.");
		next.describe(savedSummary());
		delete.describe("Deletes the config with this name.");
		copyCode.describe("Copies a code of your current settings to the clipboard.");
		importCode.describe("Applies a config code from the field above or the clipboard.");
	}

	/** The "Configs" tab (Category.CONFIGS, added by the patcher); MISC if it is missing. */
	private static Category configsCategory() {
		try {
			return Category.valueOf("CONFIGS");
		} catch (IllegalArgumentException e) {
			return Category.MISC;
		}
	}

	@Override
	public boolean canToggle() {
		return false;
	}

	/** Used by Modules: appends the modules added by the patcher (and this one) to the module list. */
	public static List<ClientModule> withConfigs(Object[] modules) {
		List<ClientModule> list = new ArrayList<>(modules.length + 1);
		for (Object m : modules) list.add((ClientModule) m);
		list.add(net.visualclient.modules.extra.NoExplosionParticlesModule.INSTANCE);
		list.add(INSTANCE);
		return Collections.unmodifiableList(list);
	}

	// ------------------------------------------------------------------ actions

	private void saveConfig() {
		String n = cleanName();
		if (n == null) { save.describe("Please enter a config name (letters, numbers, - and _)."); return; }
		try {
			Path file = dir().resolve(n + ".json");
			Files.createDirectories(file.getParent());
			Files.writeString(file, MiniJson.pretty(snapshot()), StandardCharsets.UTF_8);
			save.describe("Saved \"" + n + "\" ✓");
			next.describe(savedSummary());
		} catch (Exception e) {
			save.describe("Could not save: " + e.getMessage());
		}
	}

	private void loadConfig() {
		String n = cleanName();
		if (n == null) { load.describe("Please enter a config name."); return; }
		try {
			Path file = dir().resolve(n + ".json");
			if (!Files.isRegularFile(file)) { load.describe("No config called \"" + n + "\". " + savedSummary()); return; }
			int applied = apply(MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
			load.describe("Loaded \"" + n + "\" (" + applied + " modules) ✓");
		} catch (Exception e) {
			load.describe("Could not load: " + e.getMessage());
		}
	}

	private void nextConfig() {
		List<String> saved = savedNames();
		if (saved.isEmpty()) { next.describe("No saved configs yet."); return; }
		int i = saved.indexOf(name.get());
		name.set(saved.get((i + 1) % saved.size()));
		next.describe(savedSummary());
	}

	private void deleteConfig() {
		String n = cleanName();
		if (n == null) { delete.describe("Please enter a config name."); return; }
		try {
			boolean removed = Files.deleteIfExists(dir().resolve(n + ".json"));
			delete.describe(removed ? "Deleted \"" + n + "\"." : "No config called \"" + n + "\".");
			next.describe(savedSummary());
		} catch (Exception e) {
			delete.describe("Could not delete: " + e.getMessage());
		}
	}

	private void copyCode() {
		try {
			String c = encode(MiniJson.stringify(snapshot()));
			if (Clipboard.set(c)) {
				copyCode.describe("Code copied to clipboard (" + c.length() + " chars) ✓");
			} else {
				code.set(c);
				copyCode.describe("Clipboard not available - the code is in the field below.");
			}
		} catch (Exception e) {
			copyCode.describe("Could not create code: " + e.getMessage());
		}
	}

	private void importCode() {
		String c = code.get() == null ? "" : code.get().trim();
		if (c.isEmpty()) c = Clipboard.get();
		if (c == null || c.isBlank()) { importCode.describe("No code found. Paste it into the field or copy it first."); return; }
		try {
			int applied = apply(MiniJson.parse(decode(c.trim())));
			code.set("");
			importCode.describe("Imported config (" + applied + " modules) ✓ Save it with a name to keep it.");
		} catch (Exception e) {
			importCode.describe("Invalid config code: " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------ snapshot / apply

	private static Map<String, Object> snapshot() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", "visualclient-config");
		root.put("version", 1);
		Map<String, Object> modules = new LinkedHashMap<>();
		for (ClientModule m : Modules.ALL) {
			if (m == INSTANCE) continue;
			modules.put(m.name(), m.toJson());
		}
		root.put("modules", modules);
		Map<String, Object> extra = extra();
		if (extra != null) root.put("extra", extra);
		return root;
	}

	private static int apply(Object json) {
		Map<String, Object> root = MiniJson.asMap(json);
		if (root == null) throw new IllegalArgumentException("not a config");
		Map<String, Object> modules = MiniJson.asMap(root.get("modules"));
		if (modules == null) throw new IllegalArgumentException("no modules in config");
		int applied = 0;
		for (ClientModule m : Modules.ALL) {
			if (m == INSTANCE || !modules.containsKey(m.name())) continue;
			m.fromJson(modules.get(m.name()));
			applied++;
		}
		Map<String, Object> extraIn = MiniJson.asMap(root.get("extra"));
		Map<String, Object> extra = extra();
		if (extraIn != null && extra != null) extra.putAll(extraIn);
		ConfigManager.markDirty();
		ConfigManager.saveNow();
		return applied;
	}

	/** ConfigManager's "extra" section (HUD layout etc.). */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> extra() {
		try {
			Field f = ConfigManager.class.getDeclaredField("extra");
			f.setAccessible(true);
			return (Map<String, Object>) f.get(null);
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------ share code

	static String encode(String json) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(json.getBytes(StandardCharsets.UTF_8));
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
		deflater.end();
		return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	static String decode(String code) throws DataFormatException {
		String c = code.replaceAll("\\s+", "");
		if (!c.startsWith(CODE_PREFIX)) throw new IllegalArgumentException("codes start with " + CODE_PREFIX);
		byte[] data = Base64.getUrlDecoder().decode(c.substring(CODE_PREFIX.length()));
		Inflater inflater = new Inflater();
		inflater.setInput(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		try {
			while (!inflater.finished()) {
				int n = inflater.inflate(buf);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new DataFormatException("truncated code");
				out.write(buf, 0, n);
				if (out.size() > MAX_JSON_BYTES) throw new DataFormatException("code too large");
			}
		} finally {
			inflater.end();
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ files

	private String cleanName() {
		String n = name.get() == null ? "" : name.get().trim();
		return n.matches("[A-Za-z0-9 _.-]{1,32}") && !n.startsWith(".") ? n : null;
	}

	private static List<String> savedNames() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir())) {
			s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json"))
				.map(f -> f.substring(0, f.length() - 5)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
		} catch (Exception ignored) {
			// folder does not exist yet
		}
		return out;
	}

	private static String savedSummary() {
		List<String> saved = savedNames();
		return saved.isEmpty() ? "No saved configs yet." : "Saved: " + String.join(", ", saved);
	}

	private static Path dir() {
		try {
			Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object instance = loader.getMethod("getInstance").invoke(null);
			return ((Path) loader.getMethod("getConfigDir").invoke(instance)).resolve("visualclient").resolve("configs");
		} catch (Throwable t) {
			return Path.of("config", "visualclient", "configs");
		}
	}

	/** Clipboard through GLFW (part of Minecraft in every version). Must run on the render thread. */
	static final class Clipboard {
		static boolean set(String text) {
			try {
				Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
				long window = (long) glfw.getMethod("glfwGetCurrentContext").invoke(null);
				Method m = glfw.getMethod("glfwSetClipboardString", long.class, CharSequence.class);
				m.invoke(null, window, text);
				return true;
			} catch (Throwable t) {
				return false;
			}
		}

		static String get() {
			try {
				Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
				long window = (long) glfw.getMethod("glfwGetCurrentContext").invoke(null);
				return (String) glfw.getMethod("glfwGetClipboardString", long.class).invoke(null, window);
			} catch (Throwable t) {
				return null;
			}
		}
	}
}
