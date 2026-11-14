package net.visualclient.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.ConfigManager;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.core.setting.Setting;
import net.visualclient.core.setting.TextSetting;
import net.visualclient.hud.HasStatus;
import net.visualclient.modules.Modules;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;
import net.visualclient.util.MiniJson;
import net.visualclient.util.Smooth;

/**
 * The main menu: one panel per category (Combat, Visuals, Player, Misc).
 * Left click switches a module, right click opens its settings. Every color setting has a HEX field.
 */
public final class ClickGuiScreen extends Screen {
	private static final int HEADER_H = 22;
	private static final int ROW_H = 18;
	private static final Component[] TITLES = new Component[Category.values().length];

	static {
		for (Category c : Category.values()) {
			TITLES[c.ordinal()] = Component.literal(c.label()).withStyle(ChatFormatting.BOLD);
		}
	}

	private static final class Panel {
		final Category category;
		int x;
		int y;
		float scroll;
		int bodyH;
		int clipTop;
		int clipBottom;
		boolean placed;

		Panel(Category category) {
			this.category = category;
		}
	}

	private final List<Panel> panels = new ArrayList<>();
	private final Map<ClientModule, Smooth> expansion = new IdentityHashMap<>();
	private final Map<Setting<?>, Smooth> switches = new IdentityHashMap<>();
	private final Hits hits = new Hits();
	private final Smooth openAnim = new Smooth(0f, 16f);
	private final TextInput input = new TextInput();
	private final float[] hsva = new float[4];
	private final float[] tmp = new float[3];

	private Object editTarget;
	private ColorSetting picker;
	private Panel draggingPanel;
	private int grabX;
	private int grabY;
	private Hits.Hit activeDrag;
	private String hint = "";

	public ClickGuiScreen() {
		super(Component.literal("Visual Client"));
	}

	// ---- setup ------------------------------------------------------------------------------------

	@Override
	protected void init() {
		if (panels.isEmpty()) {
			for (Category c : Category.values()) {
				panels.add(new Panel(c));
			}
			loadLayout();
		}
		int pw = panelWidth();
		int gap = 10;
		int total = panels.size() * pw + (panels.size() - 1) * gap;
		int startX = Math.max(6, (this.width - total) / 2);
		for (int i = 0; i < panels.size(); i++) {
			Panel p = panels.get(i);
			if (!p.placed) {
				p.x = startX + i * (pw + gap);
				p.y = 28;
				p.placed = true;
			}
			p.x = Math.max(0, Math.min(p.x, this.width - 40));
			p.y = Math.max(0, Math.min(p.y, this.height - 40));
		}
		openAnim.snap(0f);
		openAnim.set(1f);
	}

	private int panelWidth() {
		return Math.max(126, Math.min(152, (this.width - 50) / 4));
	}

	private void loadLayout() {
		Map<String, Object> section = ConfigManager.section("menu");
		for (Panel p : panels) {
			Map<String, Object> m = MiniJson.asMap(section.get(p.category.name()));
			if (m != null && m.get("x") instanceof Number nx && m.get("y") instanceof Number ny) {
				p.x = nx.intValue();
				p.y = ny.intValue();
				p.placed = true;
			}
		}
	}

	private void saveLayout() {
		Map<String, Object> section = ConfigManager.section("menu");
		for (Panel p : panels) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("x", p.x);
			m.put("y", p.y);
			section.put(p.category.name(), m);
		}
		ConfigManager.markDirty();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		endEdit(true);
		saveLayout();
		ConfigManager.saveNow();
		this.minecraft.setScreen(null);
	}

	// ---- rendering --------------------------------------------------------------------------------

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (Theme.dimBackground()) {
			super.render(g, mouseX, mouseY, partialTick);
		}
		hits.clear();
		hint = "";
		float open = openAnim.get();
		int slide = Math.round((1f - open) * 12f);
		int pw = panelWidth();
		for (Panel p : panels) {
			if (p != draggingPanel) {
				drawPanel(g, p, pw, slide, mouseX, mouseY);
			}
		}
		if (draggingPanel != null) {
			drawPanel(g, draggingPanel, pw, slide, mouseX, mouseY);
		}
		drawFooter(g, mouseX, mouseY);
	}

	private void drawPanel(GuiGraphics g, Panel p, int pw, int slide, int mx, int my) {
		Font font = this.font;
		int px = p.x;
		int py = p.y + slide;
		int r = Theme.radius();
		int maxBody = Math.max(70, this.height - py - 34 - HEADER_H);

		p.clipTop = py + HEADER_H;
		p.clipBottom = py + HEADER_H + maxBody;
		int content = layout(g, p, px, py, pw, mx, my, false);
		int body = Math.min(content + 8, maxBody);
		p.bodyH = body;
		p.clipBottom = py + HEADER_H + body;
		p.scroll = Math.max(0f, Math.min(p.scroll, Math.max(0, content + 8 - body)));

		int total = HEADER_H + body;
		int bg = Theme.background();
		Render2D.round(g, px, py, pw, total, r, bg);
		Render2D.roundTop(g, px, py, pw, HEADER_H, r, ColorUtil.darken(bg, 0.28f));
		Render2D.gradientH(g, px, py + HEADER_H - 2, pw, 2, Theme.accent(), Theme.accent2(), 12);
		g.drawString(font, TITLES[p.category.ordinal()], px + 9, py + 7, Theme.text(), false);
		int active = 0;
		List<ClientModule> list = Modules.of(p.category);
		for (ClientModule m : list) {
			if (m.isEnabled() && m.canToggle()) {
				active++;
			}
		}
		Render2D.textRight(g, font, active + "/" + list.size(), px + pw - 9, py + 7, Theme.muted());

		Hits.Hit header = hits.add(px, py, pw, HEADER_H);
		header.left = (a, b) -> {
			draggingPanel = p;
			grabX = (int) a - p.x;
			grabY = (int) b - p.y;
		};

		g.enableScissor(px, py + HEADER_H, px + pw, py + HEADER_H + body);
		layout(g, p, px, py, pw, mx, my, true);
		g.disableScissor();

		if (content + 8 > body) {
			int trackH = body - 4;
			int barH = Math.max(12, trackH * body / (content + 8));
			int range = content + 8 - body;
			int barY = py + HEADER_H + 2 + Math.round((trackH - barH) * (p.scroll / range));
			Render2D.round(g, px + pw - 4, barY, 2, barH, 1, ColorUtil.withAlpha(0xFFFFFF, 70));
		}
	}

	/** Walks all modules of a panel. With draw = false it only measures (the content height is returned). */
	private int layout(GuiGraphics g, Panel p, int px, int py, int pw, int mx, int my, boolean draw) {
		int top = py + HEADER_H + 4 - (draw ? (int) p.scroll : 0);
		int y = top;
		for (ClientModule m : Modules.of(p.category)) {
			int rowY = y;
			if (draw) {
				drawModuleRow(g, p, m, px, rowY, pw, mx, my);
			}
			y += ROW_H;
			boolean expandable = m.hasSettings() || m instanceof HasStatus;
			Smooth anim = expansion.computeIfAbsent(m, k -> new Smooth(m.isExpanded() ? 1f : 0f, 15f));
			anim.set(m.isExpanded() && expandable ? 1f : 0f);
			float t = anim.get();
			if (t > 0.004f) {
				int full = settingsHeight(m);
				int visible = Math.round(full * t);
				if (draw && visible > 0) {
					g.enableScissor(px, y, px + pw, y + visible);
					drawSettings(g, p, m, px, y, pw, full, t >= 0.98f, mx, my);
					g.disableScissor();
				}
				y += visible;
			}
		}
		return y - top;
	}

	private void drawModuleRow(GuiGraphics g, Panel p, ClientModule m, int px, int y, int pw, int mx, int my) {
		Font font = this.font;
		int x = px + 4;
		int w = pw - 8;
		boolean hover = Hits.inside(mx, my, x, y, w, ROW_H - 1) && my >= p.clipTop && my < p.clipBottom;
		float on = m.switchAnim.get();
		if (hover) {
			hint = m.description();
		}
		int bg = hover ? Theme.rowHover() : (m.isExpanded() ? Theme.row() : 0);
		if ((bg >>> 24) != 0) {
			Render2D.round(g, x, y, w, ROW_H - 1, 3, bg);
		}
		boolean toggle = m.canToggle();
		boolean hasDots = m.hasSettings() || m instanceof HasStatus;
		int right = x + w - 6;
		int nameMax = w - 14 - (toggle ? 24 : 0) - (hasDots ? 14 : 0);
		int nameColor = ColorUtil.lerp(Theme.muted(), Theme.text(), toggle ? on : 1f);
		Render2D.text(g, font, Render2D.fit(font, m.name(), nameMax), x + 7, y + 5, nameColor);
		if (toggle) {
			drawSwitch(g, right - 18, y + 4, 18, 9, on);
			right -= 24;
		}
		if (hasDots) {
			int dots = hover || m.isExpanded() ? Theme.text() : Theme.muted();
			for (int i = 0; i < 3; i++) {
				g.fill(right - 9 + i * 3, y + 8, right - 7 + i * 3, y + 10, dots);
			}
		}
		Hits.Hit hit = hits.add(x, y, w, ROW_H - 1).clip(p.clipTop, p.clipBottom);
		hit.left = (a, b) -> {
			if (toggle) {
				m.toggle();
			} else {
				toggleExpand(m);
			}
		};
		hit.right = (a, b) -> toggleExpand(m);
	}

	private static void toggleExpand(ClientModule m) {
		if (m.hasSettings() || m instanceof HasStatus) {
			m.setExpanded(!m.isExpanded());
		}
	}

	private static void drawSwitch(GuiGraphics g, int x, int y, int w, int h, float t) {
		int track = ColorUtil.lerp(ColorUtil.withAlpha(0xFFFFFF, 46), Theme.accent(), t);
		Render2D.round(g, x, y, w, h, h / 2, track);
		int knob = x + 1 + Math.round(t * (w - h));
		Render2D.round(g, knob, y + 1, h - 2, h - 2, (h - 2) / 2, 0xFFFFFFFF);
	}

	// ---- settings ---------------------------------------------------------------------------------

	private int heightOf(Setting<?> s) {
		if (s instanceof NumberSetting) {
			return 25;
		}
		if (s instanceof TextSetting) {
			return 29;
		}
		if (s instanceof ActionSetting) {
			return 20;
		}
		if (s instanceof ColorSetting c) {
			return 18 + (picker == c ? pickerHeight(c) : 0);
		}
		return 16;
	}

	private static int pickerHeight(ColorSetting c) {
		return c.hasAlpha() ? 66 : 54;
	}

	private int settingsHeight(ClientModule m) {
		int h = 4;
		for (Setting<?> s : m.settings()) {
			if (s.isVisible()) {
				h += heightOf(s);
			}
		}
		if (m instanceof HasStatus) {
			h += 15;
		}
		return h + 3;
	}

	private void drawSettings(GuiGraphics g, Panel p, ClientModule m, int px, int y, int pw, int full, boolean live, int mx, int my) {
		Render2D.rect(g, px + 8, y + 2, 1, full - 5, ColorUtil.withAlpha(Theme.accent(), 90));
		int cy = y + 4;
		for (Setting<?> s : m.settings()) {
			if (!s.isVisible()) {
				continue;
			}
			int h = heightOf(s);
			drawSetting(g, p, s, px, cy, pw, h, live, mx, my);
			cy += h;
		}
		if (m instanceof HasStatus status) {
			Render2D.text(g, this.font, Render2D.fit(this.font, status.statusLine(), pw - 28), px + 14, cy + 3, Theme.muted());
		}
	}

	private Smooth switchAnim(Setting<?> s, boolean on) {
		Smooth sm = switches.computeIfAbsent(s, k -> new Smooth(on ? 1f : 0f, 18f));
		sm.set(on ? 1f : 0f);
		return sm;
	}

	private void drawSetting(GuiGraphics g, Panel p, Setting<?> s, int px, int y, int pw, int h, boolean live, int mx, int my) {
		Font font = this.font;
		int x = px + 14;
		int right = px + pw - 9;
		int w = right - x;
		boolean hover = live && Hits.inside(mx, my, px + 9, y, pw - 12, h) && my >= p.clipTop && my < p.clipBottom;
		if (hover && !s.description().isEmpty()) {
			hint = s.description();
		}
		int label = hover ? Theme.text() : Theme.muted();

		if (s instanceof BoolSetting b) {
			Render2D.text(g, font, Render2D.fit(font, b.name(), w - 24), x, y + 4, label);
			drawSwitch(g, right - 16, y + 4, 16, 8, switchAnim(b, b.on()).get());
			if (live) {
				hits.add(px + 9, y, pw - 12, h).clip(p.clipTop, p.clipBottom).left = (a, c) -> b.toggle();
			}
		} else if (s instanceof ModeSetting ms) {
			String value = ms.get();
			Render2D.text(g, font, Render2D.fit(font, ms.name(), w - font.width(value) - 8), x, y + 4, label);
			Render2D.textRight(g, font, value, right, y + 4, Theme.accent());
			if (live) {
				Hits.Hit hit = hits.add(px + 9, y, pw - 12, h).clip(p.clipTop, p.clipBottom);
				hit.left = (a, c) -> ms.cycle(1);
				hit.right = (a, c) -> ms.cycle(-1);
			}
		} else if (s instanceof NumberSetting ns) {
			String value = ns.display();
			Render2D.text(g, font, Render2D.fit(font, ns.name(), w - font.width(value) - 8), x, y + 3, label);
			Render2D.textRight(g, font, value, right, y + 3, Theme.text());
			int ty = y + 16;
			Render2D.round(g, x, ty, w, 3, 1, ColorUtil.withAlpha(0xFFFFFF, 40));
			int filled = Math.max(2, (int) Math.round(w * ns.fraction()));
			Render2D.gradientH(g, x, ty, filled, 3, Theme.accent(), Theme.accent2(), Math.min(filled, 16));
			Render2D.round(g, x + filled - 2, ty - 2, 5, 7, 2, 0xFFFFFFFF);
			if (live) {
				Hits.Hit hit = hits.add(x - 3, y + 11, w + 6, 12).clip(p.clipTop, p.clipBottom);
				hit.drag = (a, c) -> ns.setFraction((a - x) / (double) w);
			}
		} else if (s instanceof ColorSetting cs) {
			drawColor(g, p, cs, px, x, right, y, live, hover, label);
		} else if (s instanceof TextSetting ts) {
			Render2D.text(g, font, Render2D.fit(font, ts.name(), w), x, y + 2, label);
			boolean editing = editTarget == ts;
			int fy = y + 14;
			Render2D.round(g, x, fy, w, 12, 2, 0x55000000);
			if (editing) {
				Render2D.outline(g, x, fy, w, 12, Theme.accent());
			}
			String shown = editing ? input.text() + (caretOn() ? "|" : "") : ts.get();
			if (shown.isEmpty()) {
				Render2D.text(g, font, Render2D.fit(font, ts.placeholder(), w - 8), x + 4, fy + 2, ColorUtil.mulAlpha(Theme.muted(), 0.6f));
			} else {
				String visible = editing ? tail(font, shown, w - 8) : Render2D.fit(font, shown, w - 8);
				Render2D.text(g, font, visible, x + 4, fy + 2, editing ? Theme.text() : Theme.muted());
			}
			if (live) {
				hits.add(x, fy, w, 12).clip(p.clipTop, p.clipBottom).left = (a, c) -> beginEdit(ts, ts.get(), ts.maxLength(), cp -> true);
			}
		} else if (s instanceof ActionSetting as) {
			Render2D.round(g, x - 3, y + 1, w + 3, 16, 3, hover ? Theme.rowHover() : Theme.row());
			Render2D.rect(g, x + 2, y + 15, w - 7, 1, ColorUtil.withAlpha(Theme.accent(), hover ? 230 : 130));
			Render2D.textCenter(g, font, Render2D.fit(font, as.name(), w - 8), x - 3 + (w + 3) / 2, y + 5, Theme.text());
			if (live) {
				hits.add(x - 3, y + 1, w + 3, 16).clip(p.clipTop, p.clipBottom).left = (a, c) -> as.run();
			}
		}
	}

	private void drawColor(GuiGraphics g, Panel p, ColorSetting cs, int px, int x, int right, int y, boolean live, boolean hover, int label) {
		Font font = this.font;
		int swatchW = 14;
		int sx = right - swatchW;
		boolean editing = editTarget == cs;
		String shown = editing ? input.text() : cs.hex();
		int fieldW = font.width(cs.hasAlpha() ? "#AARRGGBB" : "#RRGGBB") + 8;
		int fx = sx - 4 - fieldW;
		Render2D.text(g, font, Render2D.fit(font, cs.name(), Math.max(12, fx - x - 4)), x, y + 4, label);

		boolean valid = !editing || ColorUtil.isHex(shown);
		Render2D.round(g, fx, y + 2, fieldW, 12, 2, 0x55000000);
		if (editing) {
			Render2D.outline(g, fx, y + 2, fieldW, 12, valid ? Theme.accent() : Theme.BAD);
		}
		String text = editing && caretOn() ? shown + "|" : shown;
		Render2D.text(g, font, tail(font, text, fieldW - 6), fx + 4, y + 4, editing ? Theme.text() : Theme.muted());

		Render2D.round(g, sx, y + 3, swatchW, 10, 2, 0xFF262B36);
		Render2D.round(g, sx, y + 3, swatchW, 10, 2, cs.argb());
		if (picker == cs) {
			Render2D.outline(g, sx - 1, y + 2, swatchW + 2, 12, Theme.accent());
		}
		if (live) {
			hits.add(fx, y + 2, fieldW, 12).clip(p.clipTop, p.clipBottom).left =
					(a, c) -> beginEdit(cs, cs.hex(), 9, cp -> cp == '#' || Character.digit(cp, 16) >= 0);
			hits.add(sx, y + 2, swatchW, 12).clip(p.clipTop, p.clipBottom).left = (a, c) -> togglePicker(cs);
		}
		if (picker == cs) {
			drawPicker(g, p, cs, x, y + 18, right - x, live);
		}
	}

	private void drawPicker(GuiGraphics g, Panel p, ColorSetting cs, int x, int y, int w, boolean live) {
		int svW = w - 18;
		int svH = 48;
		int cols = Math.max(1, svW / 2);
		for (int i = 0; i < cols; i++) {
			float sat = (i + 0.5f) / cols;
			int x0 = x + i * svW / cols;
			int x1 = x + (i + 1) * svW / cols;
			g.fillGradient(x0, y, x1, y + svH, ColorUtil.hsv(hsva[0], sat, 1f), ColorUtil.hsv(hsva[0], sat, 0f));
		}
		int markX = x + Math.round(hsva[1] * (svW - 1));
		int markY = y + Math.round((1f - hsva[2]) * (svH - 1));
		Render2D.outline(g, markX - 2, markY - 2, 5, 5, 0xFFFFFFFF);

		int hx = x + svW + 6;
		for (int j = 0; j < svH; j++) {
			g.fill(hx, y + j, hx + 8, y + j + 1, ColorUtil.hsv(j / (float) (svH - 1), 1f, 1f));
		}
		int hueY = y + Math.round(hsva[0] * (svH - 1));
		g.fill(hx - 2, hueY, hx + 10, hueY + 1, 0xFFFFFFFF);

		int rgb = ColorUtil.hsv(hsva[0], hsva[1], hsva[2]) & 0xFFFFFF;
		if (cs.hasAlpha()) {
			int ay = y + svH + 6;
			g.fill(x, ay, x + svW, ay + 7, 0xFF262B36);
			int steps = Math.max(1, svW / 3);
			for (int i = 0; i < steps; i++) {
				int x0 = x + i * svW / steps;
				int x1 = x + (i + 1) * svW / steps;
				g.fill(x0, ay, x1, ay + 7, ColorUtil.withAlpha(rgb, Math.round(255f * (i + 0.5f) / steps)));
			}
			int alphaX = x + Math.round(hsva[3] * (svW - 1));
			g.fill(alphaX, ay - 1, alphaX + 1, ay + 8, 0xFFFFFFFF);
			if (live) {
				hits.add(x, ay - 2, svW, 11).clip(p.clipTop, p.clipBottom).drag = (a, c) -> {
					hsva[3] = clamp01((float) ((a - x) / (svW - 1d)));
					applyPicker(cs);
				};
			}
		}
		if (live) {
			hits.add(x, y, svW, svH).clip(p.clipTop, p.clipBottom).drag = (a, c) -> {
				hsva[1] = clamp01((float) ((a - x) / (svW - 1d)));
				hsva[2] = 1f - clamp01((float) ((c - y) / (svH - 1d)));
				applyPicker(cs);
			};
			hits.add(hx - 2, y, 12, svH).clip(p.clipTop, p.clipBottom).drag = (a, c) -> {
				hsva[0] = clamp01((float) ((c - y) / (svH - 1d)));
				applyPicker(cs);
			};
		}
	}

	private static float clamp01(float f) {
		return f < 0f ? 0f : Math.min(f, 1f);
	}

	private void togglePicker(ColorSetting cs) {
		endEdit(true);
		if (picker == cs) {
			picker = null;
			return;
		}
		picker = cs;
		syncPicker(cs);
	}

	private void syncPicker(ColorSetting cs) {
		ColorUtil.toHsv(cs.argb(), tmp);
		hsva[0] = tmp[0];
		hsva[1] = tmp[1];
		hsva[2] = tmp[2];
		hsva[3] = ColorUtil.alpha(cs.argb()) / 255f;
	}

	private void applyPicker(ColorSetting cs) {
		int rgb = ColorUtil.hsv(hsva[0], hsva[1], hsva[2]) & 0xFFFFFF;
		int alpha = cs.hasAlpha() ? Math.round(hsva[3] * 255f) : 255;
		cs.set((alpha << 24) | rgb);
	}

	// ---- footer -----------------------------------------------------------------------------------

	private void drawFooter(GuiGraphics g, int mx, int my) {
		Font font = this.font;
		int bw = 104;
		int bh = 16;
		int bx = this.width / 2 - bw / 2;
		int by = this.height - bh - 8;
		boolean hover = Hits.inside(mx, my, bx, by, bw, bh);
		Render2D.round(g, bx, by, bw, bh, 3, hover ? Theme.rowHover() : Theme.background());
		Render2D.rect(g, bx + 6, by + bh - 2, bw - 12, 1, Theme.accent());
		Render2D.textCenter(g, font, "Edit HUD layout", this.width / 2, by + 4, Theme.text());
		hits.add(bx, by, bw, bh).left = (a, b) -> {
			endEdit(true);
			saveLayout();
			this.minecraft.setScreen(new HudEditorScreen(this));
		};
		String line = hint.isEmpty()
				? "Left click switches a module. Right click opens its settings. Drag a header to move a panel."
				: hint;
		Render2D.textCenter(g, font, Render2D.fit(font, line, this.width - 40), this.width / 2, by - 13, ColorUtil.withAlpha(Theme.text(), 200));
	}

	// ---- text editing -----------------------------------------------------------------------------

	private boolean caretOn() {
		return (System.currentTimeMillis() / 450L) % 2L == 0L;
	}

	private static String tail(Font font, String s, int maxWidth) {
		String out = s;
		while (out.length() > 1 && font.width(out) > maxWidth) {
			out = out.substring(1);
		}
		return out;
	}

	private void beginEdit(Object target, String initial, int maxLength, java.util.function.IntPredicate filter) {
		endEdit(true);
		editTarget = target;
		input.begin(initial, maxLength, filter);
	}

	private void endEdit(boolean commit) {
		Object target = editTarget;
		if (target == null) {
			return;
		}
		editTarget = null;
		if (!commit) {
			return;
		}
		if (target instanceof TextSetting ts) {
			ts.set(input.text());
		} else if (target instanceof ColorSetting cs) {
			if (cs.setHex(input.text()) && picker == cs) {
				syncPicker(cs);
			}
		}
	}

	/** Applies a color while the HEX code is typed, as soon as it is a complete 6 or 8 digit code. */
	private void liveApply() {
		if (editTarget instanceof ColorSetting cs) {
			int digits = ColorUtil.hexDigits(input.text());
			if ((digits == 6 || digits == 8) && cs.setHex(input.text()) && picker == cs) {
				syncPicker(cs);
			}
		}
	}

	private String clipboard() {
		try {
			String text = Minecraft.getInstance().keyboardHandler.getClipboard();
			return text == null ? "" : text.trim();
		} catch (RuntimeException e) {
			return "";
		}
	}

	// ---- input ------------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (editTarget != null) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				endEdit(false);
			} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				endEdit(true);
			} else if (key == GLFW.GLFW_KEY_BACKSPACE) {
				input.backspace();
				liveApply();
			} else if (key == GLFW.GLFW_KEY_V && (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
				input.append(clipboard());
				liveApply();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (editTarget != null) {
			input.append(event.codepointAsString());
			liveApply();
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();
		endEdit(true);
		Hits.Hit hit = hits.find(mx, my);
		if (hit == null) {
			return super.mouseClicked(event, doubleClick);
		}
		Hits.Action action = button == 0 ? hit.left : button == 1 ? hit.right : null;
		if (action != null) {
			action.run(mx, my);
		}
		if (button == 0 && hit.drag != null) {
			activeDrag = hit;
			hit.drag.run(mx, my);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingPanel != null) {
			draggingPanel.x = Math.max(0, Math.min((int) event.x() - grabX, this.width - 40));
			draggingPanel.y = Math.max(0, Math.min((int) event.y() - grabY, this.height - 40));
			return true;
		}
		if (activeDrag != null && activeDrag.drag != null) {
			activeDrag.drag.run(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean wasDragging = draggingPanel != null || activeDrag != null;
		draggingPanel = null;
		activeDrag = null;
		if (wasDragging) {
			saveLayout();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int pw = panelWidth();
		for (Panel p : panels) {
			if (Hits.inside(mouseX, mouseY, p.x, p.y, pw, HEADER_H + p.bodyH)) {
				p.scroll -= (float) (scrollY * 14d);
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
