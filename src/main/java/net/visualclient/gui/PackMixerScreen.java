package net.visualclient.gui;

import java.util.ArrayList;
import java.util.Collections;
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
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.visualclient.core.ConfigManager;
import net.visualclient.modules.visuals.PackMixerModule;
import net.visualclient.pack.PackCategory;
import net.visualclient.pack.PackMix;
import net.visualclient.render.Render2D;
import net.visualclient.render.Theme;
import net.visualclient.util.ColorUtil;

/**
 * Mix resource packs from your resourcepacks folder.
 * Left: packs you are not using. Right: your mix, top pack wins. Click a pack in the mix to choose which
 * parts of it are used (blocks, items, armor, interface, sounds, ...).
 */
public final class PackMixerScreen extends Screen {
	private record Info(String id, String title, String description) {
	}

	private static final int ROW = 24;
	private static final int CHIP_H = 12;
	private static final Component TITLE = Component.literal("Pack Mixer").withStyle(ChatFormatting.BOLD);

	private final Screen parent;
	private final Map<String, Info> infos = new LinkedHashMap<>();
	private final List<String> mix = new ArrayList<>();
	private final Map<String, Integer> masks = new LinkedHashMap<>();
	private final Hits hits = new Hits();
	private final TextInput nameInput = new TextInput();

	private boolean typingName;
	private String expanded;
	private float scrollLeft;
	private float scrollRight;
	private int contentLeft;
	private int contentRight;
	private String message = "";
	private String hint = "";
	private int listTop;
	private int listBottom;
	private int leftX;
	private int rightX;
	private int colW;

	public PackMixerScreen(Screen parent) {
		super(Component.literal("Pack Mixer"));
		this.parent = parent;
	}

	// ---- data -------------------------------------------------------------------------------------

	private static String clean(String s) {
		return s == null ? "" : s.replaceAll("\u00a7.", "").replace('\n', ' ').trim();
	}

	private void loadFromGame() {
		PackRepository repo = this.minecraft.getResourcePackRepository();
		repo.reload();
		infos.clear();
		mix.clear();
		for (Pack p : repo.getAvailablePacks()) {
			if (p.isRequired() || p.isFixedPosition()) {
				continue;
			}
			infos.put(p.getId(), new Info(p.getId(), clean(p.getTitle().getString()), clean(p.getDescription().getString())));
		}
		List<String> selected = new ArrayList<>();
		for (Pack p : repo.getSelectedPacks()) {
			if (infos.containsKey(p.getId())) {
				selected.add(p.getId());
			}
		}
		Collections.reverse(selected); // the game lists the lowest priority first, the screen shows the highest on top
		mix.addAll(selected);
		masks.clear();
		masks.putAll(PackMix.masksSnapshot());
	}

	private void apply() {
		Minecraft mc = this.minecraft;
		PackRepository repo = mc.getResourcePackRepository();
		List<String> ids = new ArrayList<>(mix);
		Collections.reverse(ids);
		repo.setSelected(ids);

		Map<String, Integer> active = new LinkedHashMap<>();
		for (String id : mix) {
			Integer m = masks.get(id);
			if (m != null) {
				active.put(id, m);
			}
		}
		PackMix.replaceMasks(active);
		if (!active.isEmpty() && !PackMixerModule.INSTANCE.isEnabled()) {
			PackMixerModule.INSTANCE.setEnabled(true);
		}

		// remember the selection the same way the vanilla pack screen does
		List<String> saved = mc.options.resourcePacks;
		saved.clear();
		for (Pack p : repo.getSelectedPacks()) {
			if (!p.isFixedPosition()) {
				saved.add(p.getId());
			}
		}
		mc.options.save();
		ConfigManager.saveNow();
		mc.reloadResourcePacks();
		message = "Applied. The game is reloading your packs.";
	}

	private void savePreset() {
		String name = nameInput.text().trim();
		if (name.isEmpty()) {
			message = "Type a name for the preset first.";
			return;
		}
		PackMix.savePreset(name, mix, masks);
		typingName = false;
		message = "Saved preset \"" + name + "\".";
	}

	private void loadPreset(PackMix.Preset preset) {
		mix.clear();
		int missing = 0;
		for (String id : preset.order()) {
			if (infos.containsKey(id)) {
				mix.add(id);
			} else {
				missing++;
			}
		}
		masks.clear();
		masks.putAll(preset.masks());
		expanded = null;
		message = "Loaded \"" + preset.name() + "\"" + (missing > 0 ? " (" + missing + " packs are missing)" : "") + ". Press Apply and reload.";
	}

	// ---- screen -----------------------------------------------------------------------------------

	@Override
	protected void init() {
		if (infos.isEmpty()) {
			loadFromGame();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		ConfigManager.saveNow();
		this.minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (Theme.dimBackground()) {
			super.render(g, mouseX, mouseY, partialTick);
		}
		hits.clear();
		hint = "";
		Font font = this.font;
		colW = Math.max(150, Math.min(240, (this.width - 48) / 2));
		leftX = this.width / 2 - colW - 6;
		rightX = this.width / 2 + 6;
		listTop = 56;
		listBottom = Math.max(listTop + 50, this.height - 92);

		g.drawString(font, TITLE, leftX, 14, Theme.text(), false);
		Render2D.text(g, font, Render2D.fit(font, "Pick packs, put them in order (top wins) and choose which parts of each pack to use.", colW * 2 + 12), leftX, 27, Theme.muted());

		int r = Theme.radius();
		Render2D.round(g, leftX - 6, listTop - 18, colW + 12, listBottom - listTop + 24, r, Theme.background());
		Render2D.round(g, rightX - 6, listTop - 18, colW + 12, listBottom - listTop + 24, r, Theme.background());
		Render2D.gradientH(g, leftX - 6, listTop - 4, colW + 12, 1, Theme.accent(), Theme.accent2(), 10);
		Render2D.gradientH(g, rightX - 6, listTop - 4, colW + 12, 1, Theme.accent(), Theme.accent2(), 10);

		drawAvailable(g, mouseX, mouseY);
		drawMix(g, mouseX, mouseY);
		drawFooter(g, mouseX, mouseY);
	}

	private void drawAvailable(GuiGraphics g, int mx, int my) {
		Font font = this.font;
		int count = 0;
		for (Info i : infos.values()) {
			if (!mix.contains(i.id())) {
				count++;
			}
		}
		Render2D.text(g, font, "Available (" + count + ")", leftX, listTop - 13, Theme.text());
		scrollLeft = Math.max(0f, Math.min(scrollLeft, Math.max(0, contentLeft - (listBottom - listTop))));
		int y = listTop - (int) scrollLeft;
		int start = y;
		g.enableScissor(leftX - 4, listTop, leftX + colW + 4, listBottom);
		for (Info info : infos.values()) {
			if (mix.contains(info.id())) {
				continue;
			}
			boolean hover = Hits.inside(mx, my, leftX, y, colW, ROW - 2) && my >= listTop && my < listBottom;
			if (hover) {
				hint = info.description().isEmpty() ? info.title() : info.description();
			}
			Render2D.round(g, leftX, y, colW, ROW - 2, 3, hover ? Theme.rowHover() : Theme.row());
			Render2D.text(g, font, Render2D.fit(font, info.title(), colW - 34), leftX + 7, y + 3, Theme.text());
			Render2D.text(g, font, Render2D.fit(font, info.description(), colW - 34), leftX + 7, y + 13, Theme.muted());
			int px = leftX + colW - 17;
			Render2D.rect(g, px, y + 9, 9, 2, Theme.accent());
			Render2D.rect(g, px + 3, y + 6, 2, 8, Theme.accent());
			String id = info.id();
			hits.add(leftX, y, colW, ROW - 2).clip(listTop, listBottom).left = (a, b) -> {
				mix.add(0, id);
				message = "";
			};
			y += ROW;
		}
		if (count == 0) {
			Render2D.text(g, font, infos.isEmpty() ? "No packs found in your resourcepacks folder." : "Every pack is already in your mix.", leftX + 4, listTop + 6, Theme.muted());
		}
		g.disableScissor();
		contentLeft = y - start;
	}

	private void drawMix(GuiGraphics g, int mx, int my) {
		Font font = this.font;
		Render2D.text(g, font, "Your mix (" + mix.size() + ")  top wins", rightX, listTop - 13, Theme.text());
		scrollRight = Math.max(0f, Math.min(scrollRight, Math.max(0, contentRight - (listBottom - listTop))));
		int y = listTop - (int) scrollRight;
		int start = y;
		g.enableScissor(rightX - 4, listTop, rightX + colW + 4, listBottom);
		for (int i = 0; i < mix.size(); i++) {
			String id = mix.get(i);
			Info info = infos.get(id);
			if (info == null) {
				continue;
			}
			final int index = i;
			boolean open = id.equals(expanded);
			int controlsX = rightX + colW - 6 - 14 * 3;
			boolean hover = Hits.inside(mx, my, rightX, y, colW, ROW - 2) && my >= listTop && my < listBottom;
			if (hover) {
				hint = "Click to choose which parts of this pack are used.";
			}
			Render2D.round(g, rightX, y, colW, ROW - 2, 3, open || hover ? Theme.rowHover() : Theme.row());
			Render2D.text(g, font, Integer.toString(i + 1), rightX + 6, y + 8, Theme.accent());
			Render2D.text(g, font, Render2D.fit(font, info.title(), controlsX - rightX - 26), rightX + 20, y + 3, Theme.text());
			boolean custom = masks.containsKey(id);
			Render2D.text(g, font, custom ? "Custom parts" : "All parts", rightX + 20, y + 13, custom ? Theme.accent2() : Theme.muted());

			hits.add(rightX, y, controlsX - rightX, ROW - 2).clip(listTop, listBottom).left = (a, b) -> expanded = id.equals(expanded) ? null : id;

			drawButton(g, controlsX, y + 5, 12, true, mx, my, index > 0, () -> {
				Collections.swap(mix, index, index - 1);
			});
			drawButton(g, controlsX + 14, y + 5, 12, false, mx, my, index < mix.size() - 1, () -> {
				Collections.swap(mix, index, index + 1);
			});
			drawRemove(g, controlsX + 28, y + 5, 12, mx, my, () -> {
				mix.remove(index);
				if (id.equals(expanded)) {
					expanded = null;
				}
			});
			y += ROW;
			if (open) {
				y += drawChips(g, id, rightX, y, colW, mx, my);
			}
		}
		if (mix.isEmpty()) {
			Render2D.text(g, font, "Click a pack on the left to add it.", rightX + 4, listTop + 6, Theme.muted());
		}
		g.disableScissor();
		contentRight = y - start;
	}

	private void drawButton(GuiGraphics g, int x, int y, int size, boolean up, int mx, int my, boolean enabled, Runnable action) {
		boolean hover = enabled && Hits.inside(mx, my, x, y, size, size) && my >= listTop && my < listBottom;
		Render2D.round(g, x, y, size, size, 2, hover ? ColorUtil.withAlpha(0xFFFFFF, 60) : ColorUtil.withAlpha(0xFFFFFF, 24));
		int color = enabled ? Theme.text() : ColorUtil.withAlpha(Theme.text(), 70);
		Render2D.chevron(g, x + size / 2, y + size / 2, !up, color);
		if (enabled) {
			hits.add(x, y, size, size).clip(listTop, listBottom).left = (a, b) -> action.run();
		}
	}

	private void drawRemove(GuiGraphics g, int x, int y, int size, int mx, int my, Runnable action) {
		boolean hover = Hits.inside(mx, my, x, y, size, size) && my >= listTop && my < listBottom;
		Render2D.round(g, x, y, size, size, 2, hover ? ColorUtil.withAlpha(Theme.BAD, 150) : ColorUtil.withAlpha(0xFFFFFF, 24));
		Render2D.cross(g, x + 3, y + 3, 6, Theme.text());
		hits.add(x, y, size, size).clip(listTop, listBottom).left = (a, b) -> action.run();
	}

	/** Draws the part chips under an opened pack. Returns the height used. */
	private int drawChips(GuiGraphics g, String id, int x, int y, int w, int mx, int my) {
		Font font = this.font;
		int mask = masks.getOrDefault(id, PackCategory.ALL);
		int cx = x + 4;
		int cy = y + 1;
		int maxX = x + w - 4;
		PackCategory[] cats = PackCategory.values();
		for (int i = -2; i < cats.length; i++) {
			String label = i == -2 ? "All" : i == -1 ? "None" : cats[i].label();
			boolean on = i == -2 ? mask == PackCategory.ALL : i == -1 ? mask == 0 : (mask & cats[i].bit()) != 0;
			int cw = font.width(label) + 8;
			if (cx + cw > maxX) {
				cx = x + 4;
				cy += CHIP_H + 2;
			}
			boolean hover = Hits.inside(mx, my, cx, cy, cw, CHIP_H) && my >= listTop && my < listBottom;
			int bg = on ? Theme.accent() : ColorUtil.withAlpha(0xFFFFFF, hover ? 60 : 28);
			Render2D.round(g, cx, cy, cw, CHIP_H, 2, bg);
			Render2D.text(g, font, label, cx + 4, cy + 2, on ? 0xFF101018 : Theme.muted());
			final int which = i;
			hits.add(cx, cy, cw, CHIP_H).clip(listTop, listBottom).left = (a, b) -> {
				int current = masks.getOrDefault(id, PackCategory.ALL);
				int next = which == -2 ? PackCategory.ALL : which == -1 ? 0 : current ^ cats[which].bit();
				if (next == PackCategory.ALL) {
					masks.remove(id);
				} else {
					masks.put(id, next);
				}
			};
			cx += cw + 3;
		}
		return cy + CHIP_H + 5 - y;
	}

	private void drawFooter(GuiGraphics g, int mx, int my) {
		Font font = this.font;
		int fy = listBottom + 12;
		String line = message.isEmpty() ? hint : message;
		if (!line.isEmpty()) {
			Render2D.text(g, font, Render2D.fit(font, line, colW * 2 + 12), leftX, fy, message.isEmpty() ? Theme.muted() : Theme.accent2());
		}

		int by = fy + 14;
		int bh = 16;
		int x = leftX;
		int applyW = 118;
		boolean hoverApply = Hits.inside(mx, my, x, by, applyW, bh);
		Render2D.round(g, x, by, applyW, bh, 3, hoverApply ? ColorUtil.brighten(Theme.accent(), 0.15f) : Theme.accent());
		Render2D.textCenter(g, font, "Apply and reload", x + applyW / 2, by + 4, 0xFF101018);
		hits.add(x, by, applyW, bh).left = (a, b) -> apply();
		x += applyW + 6;
		x = smallButton(g, x, by, "Reset", mx, my, () -> {
			loadFromGame();
			expanded = null;
			message = "Back to your current packs.";
		});
		x = smallButton(g, x, by, "Close", mx, my, this::onClose);

		int py = by + bh + 8;
		Render2D.text(g, font, "Presets", leftX, py + 3, Theme.muted());
		int cx = leftX + font.width("Presets") + 10;
		int fieldW = 110;
		int saveW = 44;
		int fieldX = rightX + colW - fieldW - saveW - 4;
		for (PackMix.Preset preset : PackMix.presets()) {
			int cw = font.width(preset.name()) + 10;
			if (cx + cw > fieldX - 8) {
				break;
			}
			boolean hover = Hits.inside(mx, my, cx, py, cw, 14);
			if (hover) {
				hint = "Click to load. Right click to delete.";
			}
			Render2D.round(g, cx, py, cw, 14, 3, hover ? ColorUtil.withAlpha(0xFFFFFF, 60) : ColorUtil.withAlpha(0xFFFFFF, 28));
			Render2D.text(g, font, preset.name(), cx + 5, py + 3, Theme.text());
			Hits.Hit hit = hits.add(cx, py, cw, 14);
			hit.left = (a, b) -> loadPreset(preset);
			hit.right = (a, b) -> {
				PackMix.deletePreset(preset.name());
				message = "Deleted preset \"" + preset.name() + "\".";
			};
			cx += cw + 4;
		}

		Render2D.round(g, fieldX, py, fieldW, 14, 2, 0x55000000);
		if (typingName) {
			Render2D.outline(g, fieldX, py, fieldW, 14, Theme.accent());
		}
		String shown = typingName ? nameInput.text() + ((System.currentTimeMillis() / 450L) % 2L == 0L ? "|" : "") : "";
		if (shown.isEmpty()) {
			Render2D.text(g, font, "Preset name", fieldX + 4, py + 3, ColorUtil.mulAlpha(Theme.muted(), 0.6f));
		} else {
			Render2D.text(g, font, Render2D.fit(font, shown, fieldW - 8), fieldX + 4, py + 3, Theme.text());
		}
		hits.add(fieldX, py, fieldW, 14).left = (a, b) -> {
			if (!typingName) {
				typingName = true;
				nameInput.begin("", 24, cp -> Character.isLetterOrDigit(cp) || cp == ' ' || cp == '-' || cp == '_');
			}
		};
		smallButton(g, fieldX + fieldW + 4, py - 1, "Save", mx, my, this::savePreset, saveW, 16);
	}

	private int smallButton(GuiGraphics g, int x, int y, String label, int mx, int my, Runnable action) {
		return smallButton(g, x, y, label, mx, my, action, this.font.width(label) + 16, 16);
	}

	private int smallButton(GuiGraphics g, int x, int y, String label, int mx, int my, Runnable action, int w, int h) {
		boolean hover = Hits.inside(mx, my, x, y, w, h);
		Render2D.round(g, x, y, w, h, 3, hover ? Theme.rowHover() : Theme.row());
		Render2D.textCenter(g, this.font, label, x + w / 2, y + 4, Theme.text());
		hits.add(x, y, w, h).left = (a, b) -> action.run();
		return x + w + 6;
	}

	// ---- input ------------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (typingName) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				typingName = false;
			} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				savePreset();
			} else if (key == GLFW.GLFW_KEY_BACKSPACE) {
				nameInput.backspace();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (typingName) {
			nameInput.append(event.codepointAsString());
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();
		Hits.Hit hit = hits.find(mx, my);
		if (typingName && (hit == null || hit.left == null || button != 0)) {
			typingName = false;
		}
		if (hit == null) {
			return super.mouseClicked(event, doubleClick);
		}
		Hits.Action action = button == 0 ? hit.left : button == 1 ? hit.right : null;
		if (action != null) {
			action.run(mx, my);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY >= listTop - 16 && mouseY <= listBottom + 6) {
			if (mouseX < this.width / 2.0) {
				scrollLeft -= (float) (scrollY * 18d);
			} else {
				scrollRight -= (float) (scrollY * 18d);
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
