package net.visualclient.gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.ExtraSettings;
import net.visualclient.core.setting.ActionSetting;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.ColorSetting;
import net.visualclient.core.setting.ModeSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.core.setting.Setting;
import net.visualclient.core.setting.TextSetting;
import net.visualclient.font.Fonts;
import net.visualclient.hud.HudModule;
import net.visualclient.modules.extra.ItemSizeModule;
import net.visualclient.modules.Modules;
import net.visualclient.modules.misc.GuiStyleModule;
import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;
import net.visualclient.util.Smooth;

/**
 * The reworked ("New") Visual Client menu.
 *
 * Layout: a sidebar with the categories (icons + module count), a header with title and a
 * global search, a grid of module cards (name, description, switch, settings button) and a
 * settings page per module (switches, sliders, segmented mode pickers, color swatches with the
 * existing color picker, text fields, action buttons). Theme colors, accent effects,
 * transparency, glow, shadow, outline, backdrop and open animation of the "GUI Style" module
 * are honoured. Everything is drawn with the mod's own Render2D through {@link Gfx}, so the
 * class is the same for every Minecraft build; the thin screen class that hosts it
 * (NewGuiScreen) is generated per build by the patcher.
 */
public final class NewGui {

	private static final int KEY_ESCAPE = 256;
	private static final int KEY_ENTER = 257;
	private static final int KEY_KP_ENTER = 335;
	private static final int KEY_BACKSPACE = 259;
	private static final int KEY_DELETE = 261;
	private static final int KEY_F = 70;
	private static final int KEY_V = 86;
	private static final int MOD_CONTROL = 2;

	private static final int EDIT_TEXT = 0;
	private static final int EDIT_HEX = 1;
	private static final int EDIT_NUMBER = 2;

	private static final String[] TAB_LABELS;
	private static final Category[] TAB_CATEGORY;

	static {
		String[] labels = null;
		Category[] cats = null;
		try {
			Field l = VisualGuiScreen.class.getDeclaredField("TAB_LABELS");
			Field c = VisualGuiScreen.class.getDeclaredField("TAB_CATEGORY");
			l.setAccessible(true);
			c.setAccessible(true);
			labels = ((String[]) l.get(null)).clone();
			cats = ((Category[]) c.get(null)).clone();
		} catch (Throwable t) {
			Category[] all = Category.values();
			labels = new String[all.length];
			cats = new Category[all.length];
			for (int i = 0; i < all.length; i++) {
				labels[i] = all[i].label();
				cats[i] = all[i];
			}
		}
		TAB_LABELS = labels;
		TAB_CATEGORY = cats;
	}

	// remembered between openings (like the old menu)
	private static int lastTab;
	private static ClientModule lastOpen;

	private final Object screen;
	private final MethodHandle scissorOn;
	private final MethodHandle scissorOff;
	private final MethodHandle closeScreen;
	private final MethodHandle itemDraw;

	// item size editor (square overlay)
	private boolean itemEditor;
	private String itemSel;
	private boolean itemOnlyCustom;
	private boolean itemSearchFocused;
	private final TextInput itemSearch = new TextInput();
	private final Smooth itemScroll = new Smooth(0f, 18f);
	private final Smooth itemAnim = new Smooth(0f, 12f);
	private int itemGridX, itemGridY, itemGridW, itemGridH;
	private final Hits hits = new Hits();
	private final TextInput search = new TextInput();
	private final TextInput editInput = new TextInput();
	private final Smooth openAnim = new Smooth(0f, 9f);
	private final Smooth gridScroll = new Smooth(0f, 18f);
	private final Smooth listScroll = new Smooth(0f, 18f);
	private final Smooth[] tabSel;
	private final Smooth[] tabHover;
	private final Map<Object, Smooth> hoverAnims = new IdentityHashMap<>();
	private final Map<Object, Smooth> switchAnims = new IdentityHashMap<>();
	private final Map<Object, Smooth> valueAnims = new IdentityHashMap<>();

	private boolean started;
	private boolean searchFocused;
	private Setting<?> editing;
	private int editKind;
	private Hits.Action activeDrag;
	private int tab;
	private ClientModule open;

	// per frame
	private Object g;
	private Object font;
	private int w;
	private int h;
	private int mx;
	private int my;
	private float fade = 1f;
	private String hint;
	private int px;
	private int py;
	private int pw;
	private int ph;
	private int side;
	private int scrollX;
	private int scrollY;
	private int scrollW;
	private int scrollH;
	private boolean scrollIsList;

	public NewGui(Object screen) {
		this.screen = screen;
		Class<?> sc = screen.getClass();
		scissorOn = bridge(sc, "vc$scissor", void.class, Object.class, int.class, int.class, int.class, int.class);
		scissorOff = bridge(sc, "vc$unscissor", void.class, Object.class);
		closeScreen = bridge(sc, "vc$close", void.class, Object.class);
		itemDraw = bridge(sc, "vc$item", void.class, Object.class, Object.class, int.class, int.class);
		itemSearch.begin("", 32, c -> true);
		tabSel = new Smooth[TAB_LABELS.length];
		tabHover = new Smooth[TAB_LABELS.length];
		for (int i = 0; i < TAB_LABELS.length; i++) {
			tabSel[i] = new Smooth(0f, 16f);
			tabHover[i] = new Smooth(0f, 18f);
		}
		tab = Math.max(0, Math.min(lastTab, TAB_LABELS.length - 1));
		open = lastOpen;
		tabSel[tab].snap(1f);
		search.begin("", 32, c -> true);
	}

	private static MethodHandle bridge(Class<?> c, String name, Class<?> ret, Class<?>... params) {
		try {
			return MethodHandles.publicLookup().findStatic(c, name, MethodType.methodType(ret, params));
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------ screen callbacks

	public void init(int width, int height) {
		if (!started) {
			started = true;
			boolean animate = !"None".equals(GuiStyleModule.openAnimation());
			openAnim.snap(animate ? 0f : 1f);
			openAnim.set(1f);
		}
	}

	public void render(Object graphics, Object font, int width, int height, int mouseX, int mouseY, float partial) {
		this.g = graphics;
		this.font = font;
		this.w = width;
		this.h = height;
		this.mx = mouseX;
		this.my = mouseY;
		hits.clear();
		hint = null;
		if (ItemSizeModule.consumeEditorRequest()) openItemEditor();
		fade = "None".equals(GuiStyleModule.openAnimation()) ? 1f : clamp(openAnim.get(), 0f, 1f);
		layout();
		backdrop();
		boolean scaled = fade < 0.995f;
		if (scaled) {
			float e = easeOut(fade);
			float s = 0.94f + 0.06f * e;
			float cx = px + pw / 2f;
			float cy = py + ph / 2f;
			Gfx.push(g);
			Gfx.translate(g, cx, cy + (1f - e) * 8f);
			Gfx.scale(g, s, s);
			Gfx.translate(g, -cx, -cy);
		}
		Fonts.setGuiRendering(true);
		try {
			panel();
			sidebar();
			if (open != null && !Modules.ALL.contains(open)) open = null;
			if (open != null) settingsPage(); else gridPage();
			footer();
			if (itemEditor) itemEditorOverlay();
		} finally {
			Fonts.setGuiRendering(false);
			if (scaled) Gfx.pop(g);
		}
		Gfx.picker(g, font, hits, mouseX, mouseY);
	}

	public boolean key(int key, int scan, int mods) {
		boolean ctrl = (mods & MOD_CONTROL) != 0;
		if (itemEditor && editing == null) {
			if (key == KEY_ESCAPE) {
				if (!itemSearch.text().isEmpty()) itemSearch.clear(); else itemEditor = false;
			} else if (key == KEY_BACKSPACE) itemSearch.backspace();
			else if (key == KEY_DELETE) itemSearch.clear();
			else if (ctrl && key == KEY_V) itemSearch.append(clipboard());
			else if (ctrl && key == KEY_F) itemSearchFocused = true;
			itemScroll.snap(Math.min(itemScroll.get(), itemScroll.target()));
			return true;
		}
		if (editing != null) {
			if (key == KEY_ESCAPE) endEdit(false);
			else if (key == KEY_ENTER || key == KEY_KP_ENTER) endEdit(true);
			else if (key == KEY_BACKSPACE) editInput.backspace();
			else if (key == KEY_DELETE) editInput.clear();
			else if (ctrl && key == KEY_V) editInput.append(clipboard());
			return true;
		}
		if (searchFocused) {
			if (key == KEY_ESCAPE) {
				if (!search.text().isEmpty()) search.clear(); else searchFocused = false;
				return true;
			}
			if (key == KEY_BACKSPACE) { search.backspace(); return true; }
			if (key == KEY_DELETE) { search.clear(); return true; }
			if (ctrl && key == KEY_V) { search.append(clipboard()); return true; }
			if (key == KEY_ENTER || key == KEY_KP_ENTER) {
				List<ClientModule> list = visibleModules();
				if (!list.isEmpty()) openModule(list.get(0));
				searchFocused = false;
				return true;
			}
		}
		if (ctrl && key == KEY_F) {
			searchFocused = true;
			return true;
		}
		if (key == KEY_ESCAPE) {
			if (open != null) { closeModule(); return true; }
			if (!search.text().isEmpty()) { search.clear(); return true; }
		}
		return false;
	}

	public boolean chr(String s) {
		if (s == null || s.isEmpty()) return false;
		if (editing != null) {
			editInput.append(s);
			return true;
		}
		if (itemEditor) {
			if (!s.isBlank() || itemSearchFocused) {
				itemSearch.append(s);
				itemSearchFocused = true;
				itemScroll.snap(0f);
			}
			return true;
		}
		if (!searchFocused) {
			if (s.isBlank()) return false;
			searchFocused = true;
			open = null;
		}
		search.append(s);
		gridScroll.snap(0f);
		return true;
	}

	public boolean mouseDown(double x, double y, int button) {
		Hits.Hit hit = hits.find(x, y);
		if (hit == null) {
			endEdit(true);
			searchFocused = false;
			return false;
		}
		searchFocused = false;
		Hits.Action action = button == 0 ? hit.left : button == 1 ? hit.right : null;
		if (action == null && editing != null) endEdit(true);
		if (action != null) action.run(x, y);
		if (button == 0 && hit.drag != null) activeDrag = hit.drag;
		return true;
	}

	public boolean mouseDrag(double x, double y, int button, double dx, double dy) {
		if (activeDrag != null) {
			activeDrag.run(x, y);
			return true;
		}
		return false;
	}

	public boolean mouseUp(double x, double y, int button) {
		activeDrag = null;
		return false;
	}

	public boolean scroll(double x, double y, double dx, double dy) {
		if (itemEditor) {
			if (Hits.inside(x, y, itemGridX, itemGridY, itemGridW, itemGridH)) itemScroll.set(itemScroll.target() - (float) dy * 24f);
			return true;
		}
		if (!Hits.inside(x, y, scrollX, scrollY, scrollW, scrollH)) return false;
		Smooth s = scrollIsList ? listScroll : gridScroll;
		s.set(s.target() - (float) dy * 28f);
		return true;
	}

	public void close() {
		endEdit(true);
		lastTab = tab;
		lastOpen = open;
	}

	// ------------------------------------------------------------------ layout & frame

	private void layout() {
		pw = Math.min(clampI(Math.round(w * 0.74f), 430, 780), w - 12);
		ph = Math.min(clampI(Math.round(h * 0.80f), 236, 450), h - 12);
		px = (w - pw) / 2;
		py = (h - ph) / 2;
		side = pw >= 560 ? 120 : pw >= 400 ? 104 : 92;
	}

	private void backdrop() {
		if (!Theme.dimBackground()) return;
		String mode = GuiStyleModule.backdrop();
		if ("Off".equals(mode)) return;
		if ("Vignette".equals(mode)) {
			Gfx.gradientV(g, 0, 0, w, h / 2, a(0xAA000000), a(0x22000000));
			Gfx.gradientV(g, 0, h / 2, w, h - h / 2, a(0x22000000), a(0xAA000000));
			return;
		}
		if ("Accent Tint".equals(mode)) {
			Gfx.rect(g, 0, 0, w, h, a(0x66000000));
			Gfx.gradientV(g, 0, 0, w, h, a(ColorUtil.withAlpha(accent(), 0x08)), a(ColorUtil.withAlpha(accent(), 0x46)));
			return;
		}
		Gfx.rect(g, 0, 0, w, h, a(0x8C000000));
	}

	private void panel() {
		int r = radius();
		if (GuiStyleModule.shadow()) Gfx.shadow(g, px, py, pw, ph, 10);
		if (GuiStyleModule.glow()) Gfx.accentGlow(g, px, py, pw, ph, GuiStyleModule.glowSize(), 0.55f * fade);
		Gfx.round(g, px, py, pw, ph, r, a(Theme.guiAlpha(panelColor())));
		Gfx.round(g, px, py, side, ph, r, a(Theme.guiAlpha(sideColor())));
		Gfx.rect(g, px + side - r, py, r, ph, a(Theme.guiAlpha(sideColor())));
		Gfx.rect(g, px + side - 1, py + 1, 1, ph - 2, a(line()));
		// soft accent light at the top of the content area
		Gfx.gradientV(g, px + side, py, pw - side, 26, a(ColorUtil.withAlpha(accent(), 0x1A)), 0);
		String outline = GuiStyleModule.outline();
		if (!"Off".equals(outline)) Gfx.fxOutline(g, px, py, pw, ph, outline, fade);
		if (GuiStyleModule.scanlines()) Gfx.scanlines(g, px, py, pw, ph, fade);
	}

	// ------------------------------------------------------------------ sidebar

	private void sidebar() {
		int x = px + 9;
		int y = py + 10;
		int acc = accent();
		Gfx.round(g, x, y, 16, 16, 5, a(acc));
		Gfx.textCenter(g, font, "V", x + 8, y + 4, a(0xFFFFFFFF));
		String first = "Visual";
		String second = " Client";
		int tw = Gfx.width(font, first + second);
		if (tw <= side - 34) {
			Gfx.text(g, font, first, x + 22, y + 4, a(Theme.text()));
			Gfx.text(g, font, second, x + 22 + Gfx.width(font, first), y + 4, a(acc));
		} else {
			Gfx.text(g, font, first, x + 22, y + 4, a(Theme.text()));
		}
		y += 28;
		int ix = px + 6;
		int iw = side - 12;
		int ih = 18;
		for (int i = 0; i < TAB_LABELS.length; i++) {
			boolean sel = i == tab && search.text().isEmpty();
			boolean hov = over(ix, y, iw, ih);
			tabSel[i].set(sel ? 1f : 0f);
			tabHover[i].set(hov ? 1f : 0f);
			float st = tabSel[i].get();
			float hv = tabHover[i].get();
			if (hv > 0.01f) Gfx.round(g, ix, y, iw, ih, radius(), a(ColorUtil.withAlpha(Theme.text(), (int) (0x14 * hv))));
			if (st > 0.01f) {
				Gfx.round(g, ix, y, iw, ih, radius(), a(ColorUtil.withAlpha(acc, (int) (0x3C * st))));
				Gfx.round(g, ix, y + 4, 2, ih - 8, 1, a(ColorUtil.withAlpha(acc, (int) (0xFF * st))));
			}
			int fg = ColorUtil.lerp(ColorUtil.lerp(Theme.muted(), Theme.text(), hv), Theme.text(), st);
			glyph(icon(TAB_LABELS[i]), ix + 7, y + 5, a(st > 0.5f ? acc : fg));
			int count = modulesFor(i).size();
			String cs = String.valueOf(count);
			int cw = Gfx.width(font, cs);
			Gfx.text(g, font, Gfx.fit(font, TAB_LABELS[i], iw - 30 - cw), ix + 20, y + 5, a(fg));
			Gfx.textRight(g, font, cs, ix + iw - 6, y + 5, a(ColorUtil.withAlpha(Theme.muted(), 0xA0)));
			final int idx = i;
			hits.add(ix, y, iw, ih).left = (hx, hy) -> selectTab(idx);
			y += ih + 2;
		}
		int by = py + ph - 8 - 16;
		sideButton(ix, by, iw, 16, "Old UI", "Switch back to the classic Visual Client menu (GUI Style > UI).",
			() -> ExtraSettings.setUi("Old"));
		by -= 20;
		if (by > y + 2) {
			sideButton(ix, by, iw, 16, "Edit HUD", "Move the HUD elements on your screen.", () -> {
				Runnable r = ThemeModule.openHudEditor;
				if (r != null) r.run();
			});
		}
	}

	private void sideButton(int x, int y, int bw, int bh, String label, String desc, Runnable run) {
		boolean hov = over(x, y, bw, bh);
		Gfx.round(g, x, y, bw, bh, radius(), a(hov ? ColorUtil.withAlpha(accent(), 0x44) : ColorUtil.withAlpha(Theme.text(), 0x10)));
		Gfx.outline(g, x, y, bw, bh, a(ColorUtil.withAlpha(Theme.text(), hov ? 0x30 : 0x18)));
		Gfx.textCenter(g, font, Gfx.fit(font, label, bw - 6), x + bw / 2, y + (bh - 8) / 2, a(hov ? Theme.text() : Theme.muted()));
		if (hov) hint = desc;
		hits.add(x, y, bw, bh).left = (hx, hy) -> { endEdit(true); run.run(); };
	}

	// ------------------------------------------------------------------ module grid

	private void gridPage() {
		int cx = px + side + 12;
		int cw = pw - side - 24;
		int top = py + 9;
		boolean searching = !search.text().isEmpty();
		String title = searching ? "Search" : TAB_LABELS[tab];
		List<ClientModule> list = visibleModules();
		int sbw = Math.max(90, Math.min(160, cw * 40 / 100));
		Gfx.text(g, font, Gfx.fit(font, title, cw - sbw - 60), cx, top + 5, a(Theme.text()));
		String count = list.size() + (list.size() == 1 ? " module" : " modules");
		Gfx.text(g, font, count, cx + Gfx.width(font, title) + 8, top + 5, a(Theme.muted()));
		searchBox(cx + cw - sbw, top, sbw, 18);
		int gy = top + 26;
		int gh = py + ph - 24 - gy;
		grid(list, cx, gy, cw, gh, searching);
	}

	private void grid(List<ClientModule> list, int x, int y, int gw, int gh, boolean searching) {
		int cols = gw >= 470 ? 3 : gw >= 300 ? 2 : 1;
		int gap = 6;
		int cardH = 48;
		int cardW = (gw - gap * (cols - 1)) / cols;
		int rows = (list.size() + cols - 1) / cols;
		int total = Math.max(0, rows * (cardH + gap) - gap);
		int max = Math.max(0, total - gh);
		clampScroll(gridScroll, max);
		int sc = Math.round(gridScroll.get());
		setScrollRegion(x, y, gw, gh, false);
		scissor(x - 2, y, x + gw + 2, y + gh);
		try {
			for (int i = 0; i < list.size(); i++) {
				int cx = x + (i % cols) * (cardW + gap);
				int cy = y + (i / cols) * (cardH + gap) - sc;
				if (cy + cardH < y || cy > y + gh) continue;
				card(list.get(i), cx, cy, cardW, cardH, y, y + gh);
			}
		} finally {
			unscissor();
		}
		if (list.isEmpty()) {
			Gfx.textCenter(g, font, searching ? "No modules match your search." : "Nothing here yet.", x + gw / 2, y + 24, a(Theme.muted()));
		}
		scrollbar(x + gw + 4, y, gh, sc, max, total);
	}

	private void card(ClientModule m, int x, int y, int cw, int ch, int clipTop, int clipBottom) {
		boolean inClip = my >= clipTop && my < clipBottom;
		boolean hov = inClip && over(x, y, cw, ch);
		float hv = anim(hoverAnims, m, hov ? 1f : 0f);
		boolean toggles = m.canToggle();
		float on = anim(switchAnims, m, toggles && m.isEnabled() ? 1f : 0f);
		int r = radius();
		Gfx.round(g, x, y, cw, ch, r, a(Theme.guiAlpha(ColorUtil.lerp(Theme.row(), Theme.rowHover(), hv))));
		if (on > 0.01f) {
			Gfx.round(g, x, y, cw, ch, r, a(ColorUtil.withAlpha(accent(), (int) (0x26 * on))));
			Gfx.round(g, x, y + 7, 2, ch - 14, 1, a(ColorUtil.withAlpha(accent(), (int) (0xFF * on))));
		}
		if (hov && GuiStyleModule.hover()) Gfx.outline(g, x, y, cw, ch, a(ColorUtil.withAlpha(accent(), 0x60)));
		Hits.Hit body = hits.add(x, y, cw, ch).clip(clipTop, clipBottom);
		body.left = (hx, hy) -> { if (toggles) m.toggle(); else openModule(m); };
		body.right = (hx, hy) -> openModule(m);
		int right = x + cw - 8;
		int nameMax = cw - 18;
		if (toggles) {
			drawSwitch(right - 18, y + 8, on);
			Hits.Hit sw = hits.add(right - 21, y + 5, 24, 16).clip(clipTop, clipBottom);
			sw.left = (hx, hy) -> m.toggle();
			nameMax -= 24;
		}
		if (m.hasSettings()) {
			int gx = toggles ? right - 18 - 15 : right - 9;
			boolean gh = inClip && over(gx - 3, y + 5, 14, 14);
			glyph(GEAR, gx, y + 9, a(gh ? accent() : Theme.muted()));
			Hits.Hit gear = hits.add(gx - 3, y + 5, 14, 14).clip(clipTop, clipBottom);
			gear.left = (hx, hy) -> openModule(m);
			gear.right = gear.left;
			nameMax -= 14;
		}
		Gfx.text(g, font, Gfx.fit(font, m.name(), nameMax), x + 10, y + 9, a(Theme.text()));
		String[] lines = wrap(m.description(), cw - 20, 2);
		for (int i = 0; i < lines.length; i++) {
			Gfx.text(g, font, lines[i], x + 10, y + 22 + i * 10, a(Theme.muted()));
		}
		if (hov) {
			hint = toggles ? "Click to turn " + m.name() + (m.isEnabled() ? " off" : " on") + "  -  right click for settings"
				: "Click to open the settings of " + m.name();
		}
	}

	private void searchBox(int x, int y, int bw, int bh) {
		boolean f = searchFocused;
		boolean hov = over(x, y, bw, bh);
		Gfx.round(g, x, y, bw, bh, radius(), a(fieldColor(f || hov)));
		if (f) Gfx.outline(g, x, y, bw, bh, a(ColorUtil.withAlpha(accent(), 0xA0)));
		glyph(SEARCH, x + 6, y + 6, a(f ? accent() : Theme.muted()));
		String q = search.text();
		boolean empty = q.isEmpty();
		int textW = bw - 30;
		String shown = empty && !f ? "Search modules..." : tail(q, textW) + (f && blink() ? "_" : "");
		Gfx.text(g, font, shown, x + 17, y + (bh - 8) / 2, a(empty && !f ? Theme.muted() : Theme.text()));
		hits.add(x, y, bw, bh).left = (hx, hy) -> { endEdit(true); searchFocused = true; };
		if (!empty) {
			int cx = x + bw - 12;
			boolean ch = over(cx - 2, y + 4, 10, 10);
			Gfx.cross(g, cx, y + 6, 5, a(ch ? Theme.text() : Theme.muted()));
			hits.add(cx - 3, y + 3, 11, 12).left = (hx, hy) -> { search.clear(); gridScroll.snap(0f); };
		}
		if (hov) hint = "Search all modules (or just start typing)  -  Ctrl+F";
	}

	// ------------------------------------------------------------------ settings page

	private void settingsPage() {
		ClientModule m = open;
		int cx = px + side + 12;
		int cw = pw - side - 24;
		int top = py + 9;
		boolean bh = over(cx, top, 18, 18);
		Gfx.round(g, cx, top, 18, 18, radius(), a(bh ? ColorUtil.withAlpha(accent(), 0x55) : fieldColor(false)));
		glyph(BACK, cx + 7, top + 6, a(Theme.text()));
		hits.add(cx, top, 18, 18).left = (hx, hy) -> closeModule();
		if (bh) hint = "Back (Esc)";
		int tx = cx + 26;
		boolean toggles = m.canToggle();
		int maxW = cw - 26 - (toggles ? 32 : 0);
		Gfx.text(g, font, Gfx.fit(font, m.name(), maxW), tx, top + 1, a(Theme.text()));
		Gfx.text(g, font, Gfx.fit(font, m.description(), maxW), tx, top + 11, a(Theme.muted()));
		if (toggles) {
			float on = anim(switchAnims, m, m.isEnabled() ? 1f : 0f);
			drawSwitch(cx + cw - 20, top + 5, on);
			hits.add(cx + cw - 24, top + 1, 28, 18).left = (hx, hy) -> m.toggle();
		}
		int ly = top + 28;
		Gfx.rect(g, cx, ly - 4, cw, 1, a(line()));
		int lh = py + ph - 24 - ly;
		settingsList(m, cx, ly, cw, lh);
	}

	private void settingsList(ClientModule m, int x, int y, int lw, int lh) {
		List<Setting<?>> rows = new ArrayList<>();
		for (Setting<?> s : m.settings()) if (s.isVisible()) rows.add(s);
		int total = 0;
		for (Setting<?> s : rows) total += rowHeight(s) + 3;
		total = Math.max(0, total - 3);
		int max = Math.max(0, total - lh);
		clampScroll(listScroll, max);
		int sc = Math.round(listScroll.get());
		setScrollRegion(x, y, lw, lh, true);
		scissor(x - 2, y, x + lw + 2, y + lh);
		try {
			int ry = y - sc;
			for (Setting<?> s : rows) {
				int rh = rowHeight(s);
				if (ry + rh >= y && ry <= y + lh) row(s, x, ry, lw, rh, y, y + lh);
				ry += rh + 3;
			}
		} finally {
			unscissor();
		}
		if (rows.isEmpty()) Gfx.textCenter(g, font, "This module has no settings.", x + lw / 2, y + 18, a(Theme.muted()));
		scrollbar(x + lw + 4, y, lh, sc, max, total);
	}

	private int rowHeight(Setting<?> s) {
		if (s instanceof NumberSetting) return 28;
		if (s instanceof ActionSetting) {
			String d = s.description();
			return d == null || d.isEmpty() ? 22 : 32;
		}
		return 22;
	}

	private void row(Setting<?> s, int x, int y, int rw, int rh, int clipTop, int clipBottom) {
		boolean inClip = my >= clipTop && my < clipBottom;
		boolean hov = inClip && over(x, y, rw, rh);
		float hv = anim(hoverAnims, s, hov ? 1f : 0f);
		Gfx.round(g, x, y, rw, rh, radius(), a(Theme.guiAlpha(ColorUtil.lerp(Theme.row(), Theme.rowHover(), hv))));
		if (hov) {
			String d = s.description();
			if (d != null && !d.isEmpty()) hint = d;
		}
		if (s instanceof ActionSetting act) {
			action(act, x, y, rw, rh, clipTop, clipBottom);
			return;
		}
		if (s instanceof NumberSetting n) {
			slider(n, x, y, rw, rh, clipTop, clipBottom, inClip);
			return;
		}
		int ctrlW = Math.max(70, Math.min(180, rw * 46 / 100));
		int ctrlX = x + rw - 8 - ctrlW;
		int ty = y + (rh - 8) / 2;
		if (s instanceof BoolSetting b) {
			Gfx.text(g, font, Gfx.fit(font, s.name(), rw - 46), x + 9, ty, a(Theme.text()));
			float t = anim(switchAnims, b, b.on() ? 1f : 0f);
			drawSwitch(x + rw - 8 - 18, y + (rh - 10) / 2, t);
			hits.add(x, y, rw, rh).clip(clipTop, clipBottom).left = (hx, hy) -> { endEdit(true); b.toggle(); };
			return;
		}
		Gfx.text(g, font, Gfx.fit(font, s.name(), ctrlX - x - 15), x + 9, ty, a(Theme.text()));
		int cy = y + (rh - 14) / 2;
		if (s instanceof ModeSetting md) {
			mode(md, ctrlX, cy, ctrlW, 14, clipTop, clipBottom, inClip);
		} else if (s instanceof ColorSetting c) {
			color(c, ctrlX, cy, ctrlW, 14, clipTop, clipBottom);
		} else if (s instanceof TextSetting t) {
			textField(t, ctrlX, cy, ctrlW, 14, clipTop, clipBottom);
		} else {
			Gfx.textRight(g, font, Gfx.fit(font, String.valueOf(s.get()), ctrlW), x + rw - 9, ty, a(Theme.muted()));
		}
	}

	private void slider(NumberSetting n, int x, int y, int rw, int rh, int clipTop, int clipBottom, boolean inClip) {
		boolean ed = editing == n;
		String val = ed ? editInput.text() + (blink() ? "_" : "") : n.display();
		int vw = Gfx.width(font, val);
		Gfx.text(g, font, Gfx.fit(font, n.name(), rw - 30 - vw), x + 9, y + 5, a(Theme.text()));
		if (ed) Gfx.round(g, x + rw - 12 - vw, y + 3, vw + 6, 11, 2, a(fieldColor(true)));
		Gfx.textRight(g, font, val, x + rw - 9, y + 5, a(ed ? Theme.text() : accent()));
		int tx = x + 9;
		int tw = rw - 18;
		int ty = y + rh - 9;
		float f = anim(valueAnims, n, clamp((float) n.fraction(), 0f, 1f));
		Gfx.round(g, tx, ty, tw, 3, 1, a(ColorUtil.withAlpha(Theme.muted(), 0x55)));
		int fw = Math.round(tw * f);
		if (fw > 0) Gfx.round(g, tx, ty, fw, 3, 1, a(accent()));
		boolean kh = inClip && over(tx - 4, ty - 6, tw + 8, 15);
		int knob = kh || activeDrag != null ? 8 : 7;
		Gfx.round(g, tx + fw - knob / 2, ty + 1 - knob / 2, knob, knob, knob / 2, a(0xFFFFFFFF));
		Hits.Hit rowHit = hits.add(x, y, rw, rh).clip(clipTop, clipBottom);
		rowHit.right = (hx, hy) -> beginEdit(n, plain(n.asDouble()), 12, NUMBER_CHARS, EDIT_NUMBER);
		Hits.Hit track = hits.add(tx - 4, ty - 6, tw + 8, 15).clip(clipTop, clipBottom);
		Hits.Action set = (hx, hy) -> {
			if (editing != null) endEdit(true);
			n.setFraction(clamp((float) ((hx - tx) / tw), 0f, 1f));
		};
		track.left = set;
		track.drag = set;
		track.right = rowHit.right;
		Hits.Hit value = hits.add(x + rw - 12 - vw, y + 2, vw + 6, 12).clip(clipTop, clipBottom);
		value.left = rowHit.right;
		value.right = rowHit.right;
		if (inClip && over(x, y, rw, rh) && hint == null) hint = "Drag to change  -  click the value or right click to type a number";
	}

	private void mode(ModeSetting md, int x, int y, int cw, int ch, int clipTop, int clipBottom, boolean inClip) {
		String[] modes = md.modes();
		int total = 0;
		for (String s : modes) total += Gfx.width(font, s) + 12;
		Gfx.round(g, x, y, cw, ch, radius(), a(fieldColor(false)));
		if (modes.length <= 4 && total <= cw) {
			int extra = (cw - total) / modes.length;
			int sx = x;
			for (int i = 0; i < modes.length; i++) {
				int sw = i == modes.length - 1 ? x + cw - sx : Gfx.width(font, modes[i]) + 12 + extra;
				boolean sel = md.is(modes[i]);
				boolean hov = inClip && over(sx, y, sw, ch);
				float t = anim(valueAnims, modeKey(md, i), sel ? 1f : 0f);
				if (t > 0.01f) Gfx.round(g, sx + 1, y + 1, sw - 2, ch - 2, radius(), a(ColorUtil.withAlpha(accent(), (int) (0xFF * t))));
				else if (hov) Gfx.round(g, sx + 1, y + 1, sw - 2, ch - 2, radius(), a(ColorUtil.withAlpha(Theme.text(), 0x18)));
				Gfx.textCenter(g, font, Gfx.fit(font, modes[i], sw - 4), sx + sw / 2, y + (ch - 8) / 2,
					a(sel ? 0xFFFFFFFF : hov ? Theme.text() : Theme.muted()));
				final String v = modes[i];
				hits.add(sx, y, sw, ch).clip(clipTop, clipBottom).left = (hx, hy) -> { endEdit(true); md.set(v); };
				sx += sw;
			}
			return;
		}
		boolean hl = inClip && over(x, y, cw / 2, ch);
		boolean hr = inClip && over(x + cw / 2, y, cw - cw / 2, ch);
		glyph(LEFT, x + 5, y + 4, a(hl ? accent() : Theme.muted()));
		glyph(RIGHT, x + cw - 8, y + 4, a(hr ? accent() : Theme.muted()));
		Gfx.textCenter(g, font, Gfx.fit(font, md.get(), cw - 24), x + cw / 2, y + (ch - 8) / 2, a(Theme.text()));
		Hits.Hit l = hits.add(x, y, cw / 2, ch).clip(clipTop, clipBottom);
		l.left = (hx, hy) -> { endEdit(true); md.cycle(-1); };
		l.right = (hx, hy) -> { endEdit(true); md.cycle(1); };
		Hits.Hit r = hits.add(x + cw / 2, y, cw - cw / 2, ch).clip(clipTop, clipBottom);
		r.left = (hx, hy) -> { endEdit(true); md.cycle(1); };
		r.right = (hx, hy) -> { endEdit(true); md.cycle(-1); };
	}

	private final Map<ModeSetting, Object[]> modeKeys = new IdentityHashMap<>();

	private Object modeKey(ModeSetting md, int i) {
		Object[] keys = modeKeys.computeIfAbsent(md, k -> {
			Object[] arr = new Object[k.modes().length];
			for (int j = 0; j < arr.length; j++) arr[j] = new Object();
			return arr;
		});
		return i < keys.length ? keys[i] : md;
	}

	private void color(ColorSetting c, int x, int y, int cw, int ch, int clipTop, int clipBottom) {
		int sw = 24;
		int sx = x + cw - sw;
		if (c.hasAlpha()) {
			for (int i = 0; i < sw; i += 4) {
				for (int j = 0; j < ch; j += 4) {
					Gfx.rect(g, sx + i, y + j, Math.min(4, sw - i), Math.min(4, ch - j), a(((i + j) / 4) % 2 == 0 ? 0xFFBDBDBD : 0xFF7A7A7A));
				}
			}
		}
		Gfx.round(g, sx, y, sw, ch, radius(), a(c.argb()));
		Gfx.outline(g, sx, y, sw, ch, a(ColorUtil.withAlpha(Theme.text(), 0x50)));
		boolean ed = editing == c;
		int hw = cw - sw - 5;
		Gfx.round(g, x, y, hw, ch, radius(), a(fieldColor(ed)));
		if (ed) Gfx.outline(g, x, y, hw, ch, a(ColorUtil.withAlpha(accent(), 0xA0)));
		String hex = ed ? tail(editInput.text(), hw - 12) + (blink() ? "_" : "") : c.hex();
		Gfx.text(g, font, Gfx.fit(font, hex, hw - 8), x + 5, y + (ch - 8) / 2, a(ed ? Theme.text() : Theme.muted()));
		hits.add(sx, y, sw, ch).clip(clipTop, clipBottom).left = (hx, hy) -> { endEdit(true); ColorPickerBridge.open(c, hx, hy); };
		hits.add(x, y, hw, ch).clip(clipTop, clipBottom).left = (hx, hy) -> beginEdit(c, c.hex(), 9, HEX_CHARS, EDIT_HEX);
	}

	private void textField(TextSetting t, int x, int y, int cw, int ch, int clipTop, int clipBottom) {
		boolean ed = editing == t;
		Gfx.round(g, x, y, cw, ch, radius(), a(fieldColor(ed)));
		if (ed) Gfx.outline(g, x, y, cw, ch, a(ColorUtil.withAlpha(accent(), 0xA0)));
		String value = ed ? editInput.text() : t.get();
		boolean empty = value == null || value.isEmpty();
		String shown;
		int color;
		if (ed) {
			shown = tail(value == null ? "" : value, cw - 14) + (blink() ? "_" : "");
			color = Theme.text();
		} else if (empty) {
			shown = Gfx.fit(font, t.placeholder() == null ? "" : t.placeholder(), cw - 10);
			color = ColorUtil.withAlpha(Theme.muted(), 0x90);
		} else {
			shown = Gfx.fit(font, value, cw - 10);
			color = Theme.text();
		}
		Gfx.text(g, font, shown, x + 5, y + (ch - 8) / 2, a(color));
		hits.add(x, y, cw, ch).clip(clipTop, clipBottom).left = (hx, hy) -> beginEdit(t, t.get(), Math.max(1, t.maxLength()), ANY_CHARS, EDIT_TEXT);
	}

	private void action(ActionSetting act, int x, int y, int rw, int rh, int clipTop, int clipBottom) {
		int bx = x + 6;
		int bw = rw - 12;
		int by = y + 4;
		int bh = 14;
		boolean hov = my >= clipTop && my < clipBottom && over(bx, by, bw, bh);
		Gfx.round(g, bx, by, bw, bh, radius(), a(ColorUtil.withAlpha(accent(), hov ? 0x80 : 0x40)));
		Gfx.textCenter(g, font, Gfx.fit(font, act.name(), bw - 10), bx + bw / 2, by + 3, a(Theme.text()));
		hits.add(bx, by, bw, bh).clip(clipTop, clipBottom).left = (hx, hy) -> { endEdit(true); act.run(); };
		String d = act.description();
		if (d != null && !d.isEmpty()) {
			Gfx.textCenter(g, font, Gfx.fit(font, d, bw - 6), bx + bw / 2, by + bh + 3, a(Theme.muted()));
		}
	}

	// ------------------------------------------------------------------ item size editor

	private void openItemEditor() {
		ClientModule m = ItemSizeModule.INSTANCE;
		if (Modules.ALL.contains(m)) {
			open = m;
			lastOpen = m;
		}
		itemEditor = true;
		itemAnim.snap(0f);
		itemAnim.set(1f);
		itemSearch.clear();
		itemScroll.snap(0f);
	}

	private List<ItemSizeModule.Entry> itemList() {
		String q = itemSearch.text().trim().toLowerCase(Locale.ROOT);
		Map<String, Float> sizes = ItemSizeModule.sizes();
		List<ItemSizeModule.Entry> out = new ArrayList<>();
		for (ItemSizeModule.Entry e : ItemSizeModule.items()) {
			if (itemOnlyCustom && !sizes.containsKey(e.key)) continue;
			if (!q.isEmpty() && !e.key.contains(q.replace(' ', '_')) && !e.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			out.add(e);
		}
		return out;
	}

	private void drawItem(Object stack, int x, int y) {
		if (itemDraw == null || stack == null) return;
		try { itemDraw.invokeExact(g, stack, x, y); } catch (Throwable ignored) { }
	}

	/** Square, sharp-cornered window: item grid with icons, search, filter and a size slider. */
	private void itemEditorOverlay() {
		float t = easeOut(clamp(itemAnim.get(), 0f, 1f));
		Map<String, Float> sizes = ItemSizeModule.sizes();
		int acc = accent();
		// dim everything behind; clicks outside close the editor
		Gfx.rect(g, 0, 0, w, h, a(ColorUtil.withAlpha(0xFF05060A, (int) (0xB0 * t))));
		hits.add(0, 0, w, h).left = (hx, hy) -> itemEditor = false;

		int size = Math.min(Math.min(w, h) - 16, 330);
		int ex = (w - size) / 2;
		int ey = (h - size) / 2 + Math.round((1f - t) * 10f);
		hits.add(ex, ey, size, size).left = (hx, hy) -> itemSearchFocused = false;
		Gfx.shadow(g, ex, ey, size, size, 12);
		if (GuiStyleModule.glow()) Gfx.accentGlow(g, ex, ey, size, size, GuiStyleModule.glowSize(), 0.6f * t);
		Gfx.rect(g, ex, ey, size, size, a(ColorUtil.withAlpha(ColorUtil.darken(Theme.background(), 0.15f), 0xF4)));
		Gfx.gradientV(g, ex, ey, size, 40, a(ColorUtil.withAlpha(acc, 0x2A)), 0);
		Gfx.rect(g, ex, ey, size, 2, a(acc));
		Gfx.outline(g, ex, ey, size, size, a(ColorUtil.withAlpha(Theme.text(), 0x22)));

		// header
		int pad = 10;
		int cx = ex + pad;
		int cw = size - pad * 2;
		Gfx.text(g, font, "Item Sizes", cx, ey + 9, a(Theme.text()));
		String sub = sizes.size() + " custom  \u00b7  others " + plain(ItemSizeModule.defaultSize()) + "x";
		Gfx.text(g, font, Gfx.fit(font, sub, cw - 80), cx + Gfx.width(font, "Item Sizes") + 8, ey + 9, a(Theme.muted()));
		int xb = ex + size - 16;
		boolean xh = over(xb - 3, ey + 6, 13, 13);
		Gfx.cross(g, xb, ey + 9, 6, a(xh ? Theme.text() : Theme.muted()));
		hits.add(xb - 3, ey + 6, 13, 13).left = (hx, hy) -> itemEditor = false;
		// on/off switch of the module itself
		boolean enabled = ItemSizeModule.INSTANCE.isEnabled();
		int swx = xb - 32;
		drawSwitch(swx, ey + 8, anim(switchAnims, ItemSizeModule.INSTANCE, enabled ? 1f : 0f));
		hits.add(swx - 2, ey + 5, 22, 16).left = (hx, hy) -> ItemSizeModule.INSTANCE.toggle();
		if (over(swx - 2, ey + 5, 22, 16)) hint = enabled ? "Item Size is on - click to turn it off" : "Item Size is off - click to turn it on";
		if (!enabled) Gfx.textRight(g, font, "off", swx - 4, ey + 9, a(0xFFE05555));

		// search + filter chip
		int sy = ey + 24;
		int chipW = 58;
		int sw = cw - chipW - 6;
		boolean sf = itemSearchFocused;
		Gfx.rect(g, cx, sy, sw, 16, a(fieldColor(sf || over(cx, sy, sw, 16))));
		Gfx.rect(g, cx, sy + 15, sw, 1, a(sf ? acc : ColorUtil.withAlpha(Theme.text(), 0x20)));
		glyph(SEARCH, cx + 5, sy + 5, a(sf ? acc : Theme.muted()));
		String q = itemSearch.text();
		String shown = q.isEmpty() && !sf ? "Search items..." : tail(q, sw - 30) + (sf && blink() ? "_" : "");
		Gfx.text(g, font, shown, cx + 16, sy + 4, a(q.isEmpty() && !sf ? Theme.muted() : Theme.text()));
		hits.add(cx, sy, sw, 16).left = (hx, hy) -> itemSearchFocused = true;
		int chx = cx + cw - chipW;
		boolean on = itemOnlyCustom;
		Gfx.rect(g, chx, sy, chipW, 16, a(on ? ColorUtil.withAlpha(acc, 0xC0) : fieldColor(over(chx, sy, chipW, 16))));
		Gfx.textCenter(g, font, "Custom", chx + chipW / 2, sy + 4, a(on ? 0xFFFFFFFF : Theme.muted()));
		hits.add(chx, sy, chipW, 16).left = (hx, hy) -> { itemOnlyCustom = !itemOnlyCustom; itemScroll.snap(0f); };
		if (over(chx, sy, chipW, 16)) hint = "Only show items with their own size";

		// grid
		int barH = 54;
		int gy = sy + 22;
		int gh = ey + size - barH - gy - 4;
		int tile = 22;
		int gap = 2;
		int cols = Math.max(1, (cw + gap) / (tile + gap));
		int gx = cx + (cw - (cols * (tile + gap) - gap)) / 2;
		List<ItemSizeModule.Entry> list = itemList();
		int rows = (list.size() + cols - 1) / cols;
		int total = Math.max(0, rows * (tile + gap) - gap);
		int max = Math.max(0, total - gh);
		clampScroll(itemScroll, max);
		int sc = Math.round(itemScroll.get());
		itemGridX = cx;
		itemGridY = gy;
		itemGridW = cw;
		itemGridH = gh;
		if (list.isEmpty()) {
			String msg = ItemSizeModule.items().isEmpty() ? "Items are not loaded yet - join a world or reopen the menu"
				: itemOnlyCustom ? "No item has its own size yet" : "No item matches \"" + q + "\"";
			Gfx.textCenter(g, font, Gfx.fit(font, msg, cw - 10), cx + cw / 2, gy + gh / 2 - 4, a(Theme.muted()));
		}
		scissor(cx, gy, cx + cw, gy + gh);
		try {
			int first = Math.max(0, sc / (tile + gap)) * cols;
			for (int i = first; i < list.size(); i++) {
				int tx = gx + (i % cols) * (tile + gap);
				int ty = gy + (i / cols) * (tile + gap) - sc;
				if (ty > gy + gh) break;
				ItemSizeModule.Entry e = list.get(i);
				boolean sel = e.key.equals(itemSel);
				boolean hov = my >= gy && my < gy + gh && over(tx, ty, tile, tile);
				Float custom = sizes.get(e.key);
				int bg = sel ? ColorUtil.withAlpha(acc, 0x55) : hov ? ColorUtil.withAlpha(Theme.text(), 0x22) : ColorUtil.withAlpha(Theme.text(), 0x0C);
				Gfx.rect(g, tx, ty, tile, tile, a(bg));
				if (sel) Gfx.outline(g, tx, ty, tile, tile, a(acc));
				else if (custom != null) Gfx.rect(g, tx, ty + tile - 2, tile, 2, a(acc));
				drawItem(e.stack, tx + 3, ty + 3);
				if (custom != null) Gfx.rect(g, tx + tile - 4, ty + 1, 3, 3, a(acc));
				hits.add(tx, ty, tile, tile).clip(gy, gy + gh).left = (hx, hy) -> { itemSel = e.key; itemSearchFocused = false; };
				if (hov) hint = e.name + "  -  " + (custom != null ? plain(custom) + "x (own size)" : plain(ItemSizeModule.defaultSize()) + "x (default)");
			}
		} finally {
			unscissor();
		}
		scrollbar(cx + cw + 3, gy, gh, sc, max, total);

		// bottom bar: selected item
		int by = ey + size - barH;
		Gfx.rect(g, ex, by, size, barH, a(ColorUtil.withAlpha(0xFF000000, 0x40)));
		Gfx.rect(g, ex, by, size, 1, a(ColorUtil.withAlpha(Theme.text(), 0x1A)));
		ItemSizeModule.Entry selected = null;
		if (itemSel != null) for (ItemSizeModule.Entry e : ItemSizeModule.items()) if (e.key.equals(itemSel)) { selected = e; break; }
		if (selected == null) {
			Gfx.textCenter(g, font, "Click an item to set its size", ex + size / 2, by + barH / 2 - 4, a(Theme.muted()));
			return;
		}
		final ItemSizeModule.Entry sel = selected;
		Float own = sizes.get(sel.key);
		float value = own != null ? own : ItemSizeModule.defaultSize();
		// big icon
		Gfx.rect(g, cx, by + 9, 36, 36, a(ColorUtil.withAlpha(acc, 0x22)));
		Gfx.outline(g, cx, by + 9, 36, 36, a(ColorUtil.withAlpha(acc, 0x70)));
		Gfx.push(g);
		Gfx.translate(g, cx + 2, by + 11);
		Gfx.scale(g, 2f, 2f);
		drawItem(sel.stack, 0, 0);
		Gfx.pop(g);
		int ix = cx + 44;
		int iw = cw - 44;
		String val = plain(value) + "x";
		Gfx.text(g, font, Gfx.fit(font, sel.name, iw - Gfx.width(font, val) - 8), ix, by + 9, a(Theme.text()));
		Gfx.textRight(g, font, val, ix + iw, by + 9, a(own != null ? acc : Theme.muted()));
		// slider
		double lo = ItemSizeModule.minSize();
		double hi = ItemSizeModule.maxSize();
		int tx = ix;
		int tw = iw;
		int ty = by + 24;
		float f = anim(valueAnims, sel.key, clamp((float) ((value - lo) / (hi - lo)), 0f, 1f));
		Gfx.rect(g, tx, ty, tw, 3, a(ColorUtil.withAlpha(Theme.muted(), 0x55)));
		int fw = Math.round(tw * f);
		if (fw > 0) Gfx.rect(g, tx, ty, fw, 3, a(own != null ? acc : ColorUtil.withAlpha(acc, 0x80)));
		Gfx.rect(g, tx + fw - 3, ty - 3, 7, 9, a(0xFFFFFFFF));
		Hits.Hit track = hits.add(tx - 4, ty - 6, tw + 8, 15);
		Hits.Action setv = (hx, hy) -> {
			double v = lo + clamp((float) ((hx - tx) / tw), 0f, 1f) * (hi - lo);
			ItemSizeModule.setSize(sel.key, (float) (Math.round(v / 0.05) * 0.05));
		};
		track.left = setv;
		track.drag = setv;
		if (over(tx - 4, ty - 6, tw + 8, 15)) hint = "Drag to change the size of " + sel.name;
		// presets + reset
		float[] presets = {0.5f, 1f, 2f, 3f};
		int px0 = ix;
		int chipY = by + 35;
		for (float pv : presets) {
			String lbl = plain(pv) + "x";
			int lw = Gfx.width(font, lbl) + 10;
			boolean act = own != null && Math.abs(own - pv) < 0.001f;
			boolean hv = over(px0, chipY, lw, 13);
			Gfx.rect(g, px0, chipY, lw, 13, a(act ? ColorUtil.withAlpha(acc, 0xC0) : hv ? ColorUtil.withAlpha(Theme.text(), 0x26) : ColorUtil.withAlpha(Theme.text(), 0x12)));
			Gfx.textCenter(g, font, lbl, px0 + lw / 2, chipY + 3, a(act ? 0xFFFFFFFF : Theme.text()));
			hits.add(px0, chipY, lw, 13).left = (hx, hy) -> ItemSizeModule.setSize(sel.key, pv);
			px0 += lw + 3;
		}
		if (own != null) {
			String rl = "Reset";
			int rw = Gfx.width(font, rl) + 12;
			int rx = ix + iw - rw;
			boolean hv = over(rx, chipY, rw, 13);
			Gfx.rect(g, rx, chipY, rw, 13, a(hv ? ColorUtil.withAlpha(0xFFE05555, 0xB0) : ColorUtil.withAlpha(0xFFE05555, 0x50)));
			Gfx.textCenter(g, font, rl, rx + rw / 2, chipY + 3, a(0xFFFFFFFF));
			hits.add(rx, chipY, rw, 13).left = (hx, hy) -> ItemSizeModule.setSize(sel.key, null);
			if (hv) hint = "Use the default size again (" + plain(ItemSizeModule.defaultSize()) + "x)";
		}
	}

	// ------------------------------------------------------------------ footer

	private void footer() {
		int cx = px + side + 12;
		int cw = pw - side - 24;
		int fy = py + ph - 17;
		Gfx.rect(g, cx, fy - 5, cw, 1, a(line()));
		String text = hint != null ? hint : open != null ? "Esc to go back" : "Left click: on/off  -  right click or gear: settings  -  type to search";
		String right = "Esc to close";
		int rw = Gfx.width(font, right);
		Gfx.text(g, font, Gfx.fit(font, text, cw - rw - 12), cx, fy + 1, a(Theme.muted()));
		Gfx.textRight(g, font, right, cx + cw, fy + 1, a(ColorUtil.withAlpha(Theme.muted(), 0x90)));
	}

	// ------------------------------------------------------------------ controls

	private void drawSwitch(int x, int y, float t) {
		int track = ColorUtil.lerp(ColorUtil.withAlpha(Theme.muted(), 0x66), accent(), t);
		Gfx.round(g, x, y, 18, 10, 5, a(track));
		int kx = x + 1 + Math.round(8 * t);
		Gfx.round(g, kx, y + 1, 8, 8, 4, a(0xFFFFFFFF));
	}

	private void scrollbar(int x, int y, int sh, int scroll, int max, int total) {
		if (max <= 0 || total <= 0) return;
		int th = Math.max(16, sh * sh / total);
		int ty = y + Math.round((sh - th) * (scroll / (float) max));
		Gfx.round(g, x, y, 2, sh, 1, a(ColorUtil.withAlpha(Theme.text(), 0x12)));
		Gfx.round(g, x, ty, 2, th, 1, a(ColorUtil.withAlpha(accent(), 0xC0)));
	}

	// ------------------------------------------------------------------ actions

	private void selectTab(int i) {
		endEdit(true);
		tab = i;
		lastTab = i;
		open = null;
		lastOpen = null;
		search.clear();
		searchFocused = false;
		gridScroll.snap(0f);
		List<ClientModule> list = modulesFor(i);
		if (list.size() == 1 && !list.get(0).canToggle()) openModule(list.get(0));
	}

	private void openModule(ClientModule m) {
		endEdit(true);
		open = m;
		lastOpen = m;
		listScroll.snap(0f);
		searchFocused = false;
	}

	private void closeModule() {
		endEdit(true);
		open = null;
		lastOpen = null;
	}

	private static final IntPredicate ANY_CHARS = c -> true;
	private static final IntPredicate HEX_CHARS = c -> c == '#' || Character.digit(c, 16) >= 0;
	private static final IntPredicate NUMBER_CHARS = c -> (c >= '0' && c <= '9') || c == '.' || c == ',' || c == '-';

	private void beginEdit(Setting<?> s, String initial, int max, IntPredicate filter, int kind) {
		if (editing != null && editing != s) endEdit(true);
		if (editing == s) return;
		editing = s;
		editKind = kind;
		editInput.begin(initial == null ? "" : initial, max, filter);
		searchFocused = false;
	}

	private void endEdit(boolean commit) {
		Setting<?> s = editing;
		if (s == null) return;
		editing = null;
		if (!commit) return;
		String v = editInput.text();
		try {
			if (editKind == EDIT_TEXT && s instanceof TextSetting t) {
				t.set(v);
			} else if (editKind == EDIT_HEX && s instanceof ColorSetting c) {
				String hex = v.trim();
				if (!hex.isEmpty()) c.setHex(hex.startsWith("#") ? hex : "#" + hex);
			} else if (editKind == EDIT_NUMBER && s instanceof NumberSetting n) {
				String num = v.trim().replace(',', '.');
				if (!num.isEmpty()) n.set(Double.valueOf(Double.parseDouble(num)));
			}
		} catch (RuntimeException ignored) {
			// invalid input: keep the old value
		}
	}

	// ------------------------------------------------------------------ data

	private List<ClientModule> modulesFor(int i) {
		Category c = TAB_CATEGORY[i];
		if (c != null) return Modules.of(c);
		List<ClientModule> out = new ArrayList<>();
		for (ClientModule m : Modules.ALL) if (m instanceof HudModule) out.add(m);
		return out;
	}

	private List<ClientModule> visibleModules() {
		String q = search.text().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return modulesFor(tab);
		// best matches first: name starts with the query, a word of the name starts with it,
		// the name contains it, then the description contains it
		List<List<ClientModule>> ranks = new ArrayList<>();
		for (int i = 0; i < 4; i++) ranks.add(new ArrayList<>());
		for (ClientModule m : Modules.ALL) {
			String n = m.name().toLowerCase(Locale.ROOT);
			String d = m.description() == null ? "" : m.description().toLowerCase(Locale.ROOT);
			if (n.startsWith(q)) ranks.get(0).add(m);
			else if (n.contains(" " + q)) ranks.get(1).add(m);
			else if (n.contains(q)) ranks.get(2).add(m);
			else if (d.contains(q)) ranks.get(3).add(m);
		}
		List<ClientModule> out = new ArrayList<>();
		for (List<ClientModule> l : ranks) out.addAll(l);
		return out;
	}

	// ------------------------------------------------------------------ helpers

	private void setScrollRegion(int x, int y, int sw, int sh, boolean list) {
		scrollX = x;
		scrollY = y;
		scrollW = sw;
		scrollH = sh;
		scrollIsList = list;
	}

	private static void clampScroll(Smooth s, int max) {
		if (s.target() > max) s.set(max);
		if (s.target() < 0) s.set(0f);
		if (s.get() > max + 40) s.snap(max);
	}

	private void scissor(int x1, int y1, int x2, int y2) {
		if (scissorOn == null) return;
		try { scissorOn.invokeExact(g, x1, y1, x2, y2); } catch (Throwable ignored) { }
	}

	private void unscissor() {
		if (scissorOff == null) return;
		try { scissorOff.invokeExact(g); } catch (Throwable ignored) { }
	}

	private boolean over(int x, int y, int ow, int oh) {
		return mx >= x && my >= y && mx < x + ow && my < y + oh;
	}

	private float anim(Map<Object, Smooth> map, Object key, float target) {
		Smooth s = map.get(key);
		if (s == null) {
			s = new Smooth(target, 16f);
			map.put(key, s);
		}
		s.set(target);
		return s.get();
	}

	private int a(int color) {
		return fade >= 0.999f ? color : ColorUtil.mulAlpha(color, fade);
	}

	private static int accent() {
		return GuiStyleModule.accent();
	}

	private static int radius() {
		return Math.max(0, Math.min(7, Theme.radius()));
	}

	private static int panelColor() {
		return Theme.background();
	}

	private static int sideColor() {
		return ColorUtil.darken(Theme.background(), 0.22f);
	}

	private static int fieldColor(boolean active) {
		return active ? 0x66000000 : 0x40000000;
	}

	private static int line() {
		return ColorUtil.withAlpha(Theme.text(), 0x1A);
	}

	private static boolean blink() {
		return (System.currentTimeMillis() / 500) % 2 == 0;
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	private static int clampI(int v, int lo, int hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	private static float easeOut(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	private static String plain(double v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
		String s = String.format(Locale.ROOT, "%.3f", v);
		while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	private String tail(String s, int maxW) {
		if (s == null) return "";
		String t = s;
		while (!t.isEmpty() && Gfx.width(font, t) > maxW) t = t.substring(1);
		return t;
	}

	private String[] wrap(String text, int maxW, int maxLines) {
		if (text == null || text.isBlank()) return new String[0];
		String[] words = text.trim().split("\\s+");
		List<String> lines = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int i = 0;
		while (i < words.length) {
			String cand = cur.length() == 0 ? words[i] : cur + " " + words[i];
			if (Gfx.width(font, cand) <= maxW) {
				cur.setLength(0);
				cur.append(cand);
				i++;
				continue;
			}
			if (cur.length() == 0) {
				cur.append(words[i]);
				i++;
			}
			lines.add(cur.toString());
			cur.setLength(0);
			if (lines.size() == maxLines) break;
		}
		if (cur.length() > 0 && lines.size() < maxLines) {
			lines.add(cur.toString());
			cur.setLength(0);
		}
		if (lines.isEmpty()) return new String[0];
		int last = lines.size() - 1;
		if (i < words.length) {
			StringBuilder rest = new StringBuilder(lines.get(last));
			for (int k = i; k < words.length; k++) rest.append(' ').append(words[k]);
			lines.set(last, rest.toString());
		}
		for (int k = 0; k < lines.size(); k++) lines.set(k, Gfx.fit(font, lines.get(k), maxW));
		return lines.toArray(new String[0]);
	}

	private static String clipboard() {
		try {
			Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
			long window = (long) glfw.getMethod("glfwGetCurrentContext").invoke(null);
			Object s = glfw.getMethod("glfwGetClipboardString", long.class).invoke(null, window);
			return s == null ? "" : s.toString().replace("\n", " ").replace("\r", "");
		} catch (Throwable t) {
			return "";
		}
	}

	// ------------------------------------------------------------------ icons (pixel glyphs)

	private static final String[] SWORD = {
		".....##", "....###", "...###.", "#.###..", ".###...", "..#....", ".#.#..."};
	private static final String[] EYE = {
		".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..", "......."};
	private static final String[] LAYOUT = {
		"#######", "#..#..#", "#..#..#", "####..#", "#..####", "#.....#", "#######"};
	private static final String[] PERSON = {
		"..###..", "..###..", "...#...", ".#####.", "...#...", "..#.#..", ".#...#."};
	private static final String[] COG = {
		"...#...", ".#####.", ".##.##.", "###.###", ".##.##.", ".#####.", "...#..."};
	private static final String[] STAR = {
		"...#...", "...#...", "#######", ".#####.", "..###..", ".##.##.", ".#...#."};
	private static final String[] SLIDERS = {
		".#...#.", ".#..###", "###..#.", ".#...#.", ".#...#.", ".#..###", ".#...#."};
	private static final String[] DOT = {
		".......", "..###..", ".#####.", ".#####.", ".#####.", "..###..", "......."};
	private static final String[] GEAR = {
		".#.#.", "#####", "##.##", "#####", ".#.#."};
	private static final String[] SEARCH = {
		".###..", "#...#.", "#...#.", ".###..", "....#.", ".....#"};
	private static final String[] BACK = {
		"..#", ".#.", "#..", ".#.", "..#"};
	private static final String[] LEFT = {
		".#", "#.", "#.", ".#"};
	private static final String[] RIGHT = {
		"#.", ".#", ".#", "#."};

	private static String[] icon(String label) {
		String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
		if (l.contains("pvp") || l.contains("combat")) return SWORD;
		if (l.contains("visual")) return EYE;
		if (l.contains("hud")) return LAYOUT;
		if (l.contains("player")) return PERSON;
		if (l.contains("misc")) return COG;
		if (l.contains("extra")) return STAR;
		if (l.contains("config")) return SLIDERS;
		return DOT;
	}

	private void glyph(String[] rows, int x, int y, int color) {
		for (int r = 0; r < rows.length; r++) {
			String row = rows[r];
			int c = 0;
			while (c < row.length()) {
				if (row.charAt(c) == '#') {
					int s = c;
					while (c < row.length() && row.charAt(c) == '#') c++;
					Gfx.rect(g, x + s, y + r, c - s, 1, color);
				} else {
					c++;
				}
			}
		}
	}
}
