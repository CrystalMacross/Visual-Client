package net.visualclient.gui;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Extra features for the Texture Painter that do not touch Minecraft classes, so the
 * same compiled class works in every Minecraft build of the bundle:
 *
 * <ul>
 *   <li>extra paintable textures that are not item textures (the placed End Crystal),</li>
 *   <li>exporting the painted textures as a ready-to-use resource pack .zip.</li>
 * </ul>
 *
 * {@code TexturePainterScreen} calls into this class (see tools/texture-painter-patch).
 */
public final class TexturePainterExtras {

	/** Folder the painter saves into (same as TexturePainterScreen.PACK_NAME). */
	public static final String PACK_NAME = "Visual Client Texture Edits";

	/** Entry path prefix for extra entries, so they can never clash with a real item id. */
	private static final String PLACED_CRYSTAL = "placed_end_crystal";

	private static final AtomicBoolean EXPORTING = new AtomicBoolean(false);

	private TexturePainterExtras() {
	}

	// ------------------------------------------------------------------ extra textures

	/**
	 * Called once per registered item while the painter builds its item list.
	 *
	 * @return {@code null}, or {@code {entryId, textureLocationPath}} for an extra entry that
	 *         should be listed next to this item.
	 */
	public static String[] extraEntry(String namespace, String itemPath) {
		if ("minecraft".equals(namespace) && "end_crystal".equals(itemPath)) {
			return new String[] {
				"minecraft:" + PLACED_CRYSTAL,
				"textures/entity/end_crystal/end_crystal.png"
			};
		}
		return null;
	}

	/**
	 * Where a painted texture is written inside {@code assets/<namespace>/textures}.
	 * Normal items go to {@code item/<path>.png}; extra entries to their real location.
	 */
	public static Path resolveTexture(Path texturesDir, String entryPath) {
		if (PLACED_CRYSTAL.equals(entryPath)) {
			return texturesDir.resolve("entity").resolve("end_crystal").resolve("end_crystal.png");
		}
		return texturesDir.resolve("item").resolve(entryPath + ".png");
	}

	// ------------------------------------------------------------------ zip export

	/** Called by the "Export ZIP" button. Runs in the background so the game never freezes. */
	public static void exportZip(Object screen) {
		if (!EXPORTING.compareAndSet(false, true)) {
			setMessage(screen, "Export already running...");
			return;
		}
		setMessage(screen, "Choose where to save the ZIP...");
		Thread thread = new Thread(() -> {
			try {
				setMessage(screen, runExport());
			} catch (Throwable t) {
				setMessage(screen, "Could not export ZIP: " + t.getMessage());
			} finally {
				EXPORTING.set(false);
			}
		}, "Visual Client Texture Export");
		thread.setDaemon(true);
		thread.start();
	}

	private static String runExport() throws IOException {
		Path gameDir = gameDir();
		Path packDir = gameDir.resolve("resourcepacks").resolve(PACK_NAME);
		Path assets = packDir.resolve("assets");
		if (!Files.isDirectory(assets) || !containsPng(assets)) {
			return "Nothing to export yet - paint a texture and press Save first.";
		}

		Path exportDir = gameDir.resolve("visualclient").resolve("texture-exports");
		Files.createDirectories(exportDir);
		Path fallback = exportDir.resolve(PACK_NAME + ".zip");

		Path target;
		DialogResult dialog = saveDialog(fallback);
		if (dialog.cancelled) {
			return "ZIP export cancelled.";
		}
		target = dialog.path != null ? dialog.path : fallback;
		if (!target.getFileName().toString().toLowerCase().endsWith(".zip")) {
			target = target.resolveSibling(target.getFileName() + ".zip");
		}
		if (target.toAbsolutePath().normalize().startsWith(packDir.toAbsolutePath().normalize())) {
			return "Please pick a location outside the pack folder.";
		}

		int files = writeZip(packDir, target);
		return "Exported " + files + " file(s) to " + target.toAbsolutePath();
	}

	private static boolean containsPng(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".png"));
		}
	}

	/** Zips the pack folder so pack.mcmeta sits at the root of the archive. */
	static int writeZip(Path packDir, Path target) throws IOException {
		List<Path> entries = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(packDir)) {
			walk.filter(Files::isRegularFile).sorted().forEach(entries::add);
		}
		Path parent = target.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."), "vc-export", ".zip.tmp");
		int count = 0;
		try {
			try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(out)) {
				boolean hasMeta = false;
				for (Path file : entries) {
					String name = packDir.relativize(file).toString().replace('\\', '/');
					if (name.equals("pack.mcmeta")) {
						hasMeta = true;
					}
					zip.putNextEntry(new ZipEntry(name));
					Files.copy(file, zip);
					zip.closeEntry();
					count++;
				}
				if (!hasMeta) {
					zip.putNextEntry(new ZipEntry("pack.mcmeta"));
					zip.write("{\"pack\":{\"pack_format\":48,\"description\":\"Textures painted with Visual Client\"}}"
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					zip.closeEntry();
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tmp);
		}
		return count;
	}

	// ------------------------------------------------------------------ helpers

	private record DialogResult(Path path, boolean cancelled) {
	}

	/**
	 * Opens the native "save file" dialog through LWJGL's tinyfd (shipped with Minecraft).
	 * Without a usable dialog (missing class, no dialog program on Linux) the fallback path is used.
	 */
	private static DialogResult saveDialog(Path fallback) {
		try {
			Class<?> tinyfd = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
			Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
			Method m = tinyfd.getMethod("tinyfd_saveFileDialog",
				CharSequence.class, CharSequence.class, pointerBuffer, CharSequence.class);
			long start = System.nanoTime();
			Object result = m.invoke(null, "Save painted textures as ZIP",
				fallback.toAbsolutePath().toString(), null, "Resource pack (*.zip)");
			if (result != null && !result.toString().isBlank()) {
				return new DialogResult(Path.of(result.toString()), false);
			}
			// Returned instantly: no dialog could be shown, so just use the default location.
			if (System.nanoTime() - start < 400_000_000L) {
				return new DialogResult(null, false);
			}
			return new DialogResult(null, true);
		} catch (Throwable t) {
			return new DialogResult(null, false);
		}
	}

	private static Path gameDir() {
		try {
			Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object instance = loader.getMethod("getInstance").invoke(null);
			return (Path) loader.getMethod("getGameDir").invoke(instance);
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}

	private static void setMessage(Object screen, String message) {
		try {
			Field f = screen.getClass().getDeclaredField("saveMessage");
			f.setAccessible(true);
			f.set(screen, message);
		} catch (Throwable ignored) {
			// The message is only cosmetic.
		}
	}
}
