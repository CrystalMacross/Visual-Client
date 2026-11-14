package net.visualclient.modules.combat;

import java.io.ByteArrayInputStream;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.glint.ImageTools;
import net.visualclient.hooks.OverlayAccess;
import net.visualclient.util.Log;

/**
 * Changes the colour of the flash that hurt players and mobs get. Type a HEX code (e.g. #00FFAA)
 * or use the picker. Works by rewriting the game's 16x16 overlay texture.
 */
public final class DamageTintModule extends ClientModule {
	public static final DamageTintModule INSTANCE = new DamageTintModule();

	private static final int VANILLA_RGB = 0xFF0000;
	private static final float VANILLA_STRENGTH = 0.302f;

	private final ColorSetting color = add(new ColorSetting("Tint color", 0xFF00E5FF, false));
	private final NumberSetting strength = add(new NumberSetting("Strength", 40, 5, 100, 1).suffix("%"));

	private static volatile OverlayAccess overlay;
	private static volatile boolean dirty = true;
	private static boolean failedOnce;

	private DamageTintModule() {
		super("Damage Tint", "Changes the color of the flash when a player or mob is hurt.", Category.COMBAT);
		strength.describe("How strongly the hurt color covers the original color. Vanilla is about 30%.");
		color.onChange(v -> dirty = true);
		strength.onChange(v -> dirty = true);
	}

	/** Called by the OverlayTexture mixin when the game creates its overlay texture. */
	public static void captureOverlay(Object texture) {
		if (texture instanceof OverlayAccess access) {
			overlay = access;
			dirty = true;
		}
	}

	@Override
	protected void onEnable() {
		dirty = true;
	}

	@Override
	protected void onDisable() {
		write(VANILLA_RGB, VANILLA_STRENGTH);
	}

	@Override
	protected void tick() {
		if (dirty && overlay != null) {
			dirty = false;
			write(color.rgb(), strength.asFloat() / 100f);
		}
	}

	private static void write(int rgb, float strength) {
		OverlayAccess access = overlay;
		if (access == null) {
			return;
		}
		try {
			byte[] png = ImageTools.hurtOverlay(rgb, strength);
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			DynamicTexture texture = access.visualclient$texture();
			texture.setPixels(image);
			texture.upload();
		} catch (Exception e) {
			if (!failedOnce) {
				failedOnce = true;
				Log.error("Could not change the damage tint", e);
			}
		}
	}
}
