package net.visualclient.glint;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** PNG helpers that need no Minecraft classes, so they can be tested on their own. */
public final class ImageTools {
	private ImageTools() {
	}

	/**
	 * Keeps the brightness pattern of a vanilla glint texture but paints it in another colour.
	 * The brightest pixel becomes exactly the chosen colour (times brightness).
	 */
	public static byte[] recolorGlint(byte[] png, int rgb, float brightness) throws IOException {
		BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
		if (src == null) {
			throw new IOException("Not a PNG image");
		}
		int w = src.getWidth();
		int h = src.getHeight();
		int peak = 1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src.getRGB(x, y);
				if ((p >>> 24) == 0) {
					continue;
				}
				peak = Math.max(peak, Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255)));
			}
		}
		int tr = rgb >> 16 & 255;
		int tg = rgb >> 8 & 255;
		int tb = rgb & 255;
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src.getRGB(x, y);
				int a = p >>> 24;
				float v = Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255)) / (float) peak * brightness;
				int r = Math.min(255, Math.round(tr * v));
				int g = Math.min(255, Math.round(tg * v));
				int b = Math.min(255, Math.round(tb * v));
				out.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
			}
		}
		return write(out);
	}

	/**
	 * The 16x16 overlay texture the game uses for the red "hurt" flash and the white creeper flash.
	 * The top 8 rows hold the hurt colour: its alpha is how much of the original colour is kept,
	 * so strength 0.30 reproduces the vanilla look when the colour is pure red.
	 */
	public static byte[] hurtOverlay(int rgb, float strength) throws IOException {
		strength = Math.max(0f, Math.min(1f, strength));
		int keep = Math.round((1f - strength) * 255f);
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if (y < 8) {
					img.setRGB(x, y, keep << 24 | (rgb & 0xFFFFFF));
				} else {
					int k = (int) ((1.0f - x / 15.0f * 0.75f) * 255.0f);
					img.setRGB(x, y, k << 24 | 0xFFFFFF);
				}
			}
		}
		return write(img);
	}

	private static byte[] write(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}
}
