package net.visualclient.hooks;

import net.minecraft.client.renderer.texture.DynamicTexture;

/** Implemented by the OverlayTexture mixin so the Damage Tint module can reach the game's overlay texture. */
public interface OverlayAccess {
	DynamicTexture visualclient$texture();
}
