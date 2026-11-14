package net.visualclient.glint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.visualclient.modules.visuals.ArmorGlintModule;
import net.visualclient.util.Log;

/**
 * Recolours the enchantment glint by registering a recoloured copy of the vanilla glint texture
 * under the original texture name. It reads the glint from your active resource packs, so custom
 * glints keep their pattern. The copy is rebuilt after every resource reload.
 */
public final class GlintTextures {
	private static final Identifier ARMOR = Identifier.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_armor.png");
	private static final Identifier ITEM = Identifier.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_item.png");

	private static final Set<Identifier> TOUCHED = new HashSet<>();
	private static byte[] armorOriginal;
	private static byte[] itemOriginal;
	private static volatile boolean dirty = true;
	private static boolean wasLoading = true;
	private static long lastRainbow;
	private static boolean failedOnce;

	private GlintTextures() {
	}

	public static void markDirty() {
		dirty = true;
	}

	/** Call every client tick (also while the module is off, so a vanilla glint can be restored). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		boolean loading = mc.getOverlay() != null;
		if (wasLoading && !loading) {
			armorOriginal = null;
			itemOriginal = null;
			dirty = true;
		}
		wasLoading = loading;
		if (loading) {
			return;
		}
		ArmorGlintModule module = ArmorGlintModule.INSTANCE;
		boolean override = module.isEnabled();
		if (!override && TOUCHED.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (override && module.rainbow() && now - lastRainbow >= 120L) {
			dirty = true;
			lastRainbow = now;
		}
		if (!dirty) {
			return;
		}
		dirty = false;
		try {
			int rgb = override ? module.currentRgb(now) : 0;
			float brightness = override ? module.brightness() : 1f;
			apply(mc, ARMOR, override && module.armor(), rgb, brightness);
			apply(mc, ITEM, override && module.items(), rgb, brightness);
		} catch (Exception e) {
			if (!failedOnce) {
				failedOnce = true;
				Log.error("Could not recolor the enchantment glint", e);
			}
		}
	}

	private static void apply(Minecraft mc, Identifier id, boolean recolor, int rgb, float brightness) throws IOException {
		if (!recolor && !TOUCHED.contains(id)) {
			return; // never touched, the vanilla texture is still in use
		}
		byte[] original = original(mc, id);
		if (original == null) {
			return;
		}
		byte[] png = recolor ? ImageTools.recolorGlint(original, rgb, brightness) : original;
		NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
		mc.getTextureManager().register(id, new DynamicTexture(() -> "visualclient glint", image));
		TOUCHED.add(id);
	}

	private static byte[] original(Minecraft mc, Identifier id) throws IOException {
		byte[] cached = id == ARMOR ? armorOriginal : itemOriginal;
		if (cached != null) {
			return cached;
		}
		Optional<Resource> resource = mc.getResourceManager().getResource(id);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			cached = in.readAllBytes();
		}
		if (id == ARMOR) {
			armorOriginal = cached;
		} else {
			itemOriginal = cached;
		}
		return cached;
	}
}
