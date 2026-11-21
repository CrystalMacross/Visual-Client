package net.visualclient.gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.visualclient.compat.Compat;
import net.visualclient.font.Fonts;
import net.visualclient.render.Render2D;

/**
 * Mapping-independent access to the mod's drawing helpers.
 *
 * Render2D, Fonts and Compat take Minecraft's graphics and font objects, whose class names
 * differ between the Minecraft builds (intermediary names in 1.21.x, official names in 26.x).
 * Their handles are resolved once by name and parameter count and the Minecraft types are
 * erased to Object, so this class and {@link NewGui} work unchanged in every build.
 */
final class Gfx {

	private Gfx() {
	}

	private static final MethodHandle RECT = find(Render2D.class, "rect", 6);
	private static final MethodHandle ROUND = find(Render2D.class, "round", 7);
	private static final MethodHandle OUTLINE = find(Render2D.class, "outline", 6);
	private static final MethodHandle GRAD_V = find(Render2D.class, "gradientV", 7);
	private static final MethodHandle SHADOW = find(Render2D.class, "shadow", 6);
	private static final MethodHandle ACCENT_GLOW = find(Render2D.class, "accentGlow", 7);
	private static final MethodHandle FX_OUTLINE = find(Render2D.class, "fxOutline", 7);
	private static final MethodHandle SCANLINES = find(Render2D.class, "scanlines", 6);
	private static final MethodHandle TEXT = find(Render2D.class, "text", 6);
	private static final MethodHandle TEXT_CENTER = find(Render2D.class, "textCenter", 6);
	private static final MethodHandle TEXT_RIGHT = find(Render2D.class, "textRight", 6);
	private static final MethodHandle FIT = find(Render2D.class, "fit", 3);
	private static final MethodHandle CROSS = find(Render2D.class, "cross", 5);
	private static final MethodHandle WIDTH = find(Fonts.class, "width", 2);
	private static final MethodHandle PUSH = find(Compat.class, "push", 1);
	private static final MethodHandle POP = find(Compat.class, "pop", 1);
	private static final MethodHandle TRANSLATE = find(Compat.class, "translate", 3);
	private static final MethodHandle SCALE = find(Compat.class, "scale", 3);
	private static final MethodHandle PICKER = find(ColorPickerBridge.class, "render", 5);

	private static boolean reported;

	private static MethodHandle find(Class<?> owner, String name, int params) {
		try {
			for (Method m : owner.getDeclaredMethods()) {
				if (!m.getName().equals(name) || m.getParameterCount() != params || !Modifier.isStatic(m.getModifiers())) continue;
				MethodHandle h = MethodHandles.lookup().unreflect(m);
				Class<?>[] types = h.type().parameterArray();
				for (int i = 0; i < types.length; i++) {
					if (isGameType(types[i])) types[i] = Object.class;
				}
				Class<?> ret = isGameType(h.type().returnType()) ? Object.class : h.type().returnType();
				return h.asType(MethodType.methodType(ret, types));
			}
		} catch (Throwable t) {
			report(t);
		}
		return null;
	}

	private static boolean isGameType(Class<?> c) {
		String n = c.getName();
		return n.startsWith("net.minecraft.") || n.startsWith("com.mojang.");
	}

	private static void report(Throwable t) {
		if (!reported) {
			reported = true;
			System.err.println("[Visual Client] New UI drawing problem: " + t);
		}
	}

	static void rect(Object g, int x, int y, int w, int h, int c) {
		try { RECT.invokeExact(g, x, y, w, h, c); } catch (Throwable t) { report(t); }
	}

	static void round(Object g, int x, int y, int w, int h, int r, int c) {
		try { ROUND.invokeExact(g, x, y, w, h, r, c); } catch (Throwable t) { report(t); }
	}

	static void outline(Object g, int x, int y, int w, int h, int c) {
		try { OUTLINE.invokeExact(g, x, y, w, h, c); } catch (Throwable t) { report(t); }
	}

	static void gradientV(Object g, int x, int y, int w, int h, int top, int bottom) {
		try { GRAD_V.invokeExact(g, x, y, w, h, top, bottom); } catch (Throwable t) { report(t); }
	}

	static void shadow(Object g, int x, int y, int w, int h, int size) {
		try { SHADOW.invokeExact(g, x, y, w, h, size); } catch (Throwable t) { report(t); }
	}

	static void accentGlow(Object g, int x, int y, int w, int h, int size, float strength) {
		try { ACCENT_GLOW.invokeExact(g, x, y, w, h, size, strength); } catch (Throwable t) { report(t); }
	}

	static void fxOutline(Object g, int x, int y, int w, int h, String mode, float fade) {
		try { FX_OUTLINE.invokeExact(g, x, y, w, h, mode, fade); } catch (Throwable t) { report(t); }
	}

	static void scanlines(Object g, int x, int y, int w, int h, float fade) {
		try { SCANLINES.invokeExact(g, x, y, w, h, fade); } catch (Throwable t) { report(t); }
	}

	static void text(Object g, Object font, String s, int x, int y, int c) {
		try { TEXT.invokeExact(g, font, s, x, y, c); } catch (Throwable t) { report(t); }
	}

	static void textCenter(Object g, Object font, String s, int cx, int y, int c) {
		try { TEXT_CENTER.invokeExact(g, font, s, cx, y, c); } catch (Throwable t) { report(t); }
	}

	static void textRight(Object g, Object font, String s, int right, int y, int c) {
		try { TEXT_RIGHT.invokeExact(g, font, s, right, y, c); } catch (Throwable t) { report(t); }
	}

	static String fit(Object font, String s, int w) {
		if (s == null) return "";
		try { return (String) FIT.invokeExact(font, s, w); } catch (Throwable t) { report(t); return s; }
	}

	static void cross(Object g, int x, int y, int size, int c) {
		try { CROSS.invokeExact(g, x, y, size, c); } catch (Throwable t) { report(t); }
	}

	static int width(Object font, String s) {
		if (s == null || s.isEmpty()) return 0;
		try { return (int) WIDTH.invokeExact(font, s); } catch (Throwable t) { report(t); return s.length() * 6; }
	}

	static void push(Object g) {
		try { PUSH.invokeExact(g); } catch (Throwable t) { report(t); }
	}

	static void pop(Object g) {
		try { POP.invokeExact(g); } catch (Throwable t) { report(t); }
	}

	static void translate(Object g, float x, float y) {
		try { TRANSLATE.invokeExact(g, x, y); } catch (Throwable t) { report(t); }
	}

	static void scale(Object g, float x, float y) {
		try { SCALE.invokeExact(g, x, y); } catch (Throwable t) { report(t); }
	}

	static void picker(Object g, Object font, Hits hits, int mx, int my) {
		try { PICKER.invokeExact(g, font, hits, mx, my); } catch (Throwable t) { report(t); }
	}
}
