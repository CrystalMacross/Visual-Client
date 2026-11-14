package net.visualclient.modules.misc;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.visualclient.core.Category;
import net.visualclient.core.setting.BoolSetting;
import net.visualclient.core.setting.NumberSetting;
import net.visualclient.hud.HasStatus;
import net.visualclient.hud.HudModule;
import net.visualclient.media.LyricsService;
import net.visualclient.media.MediaInfo;
import net.visualclient.media.MediaTracker;
import net.visualclient.render.Render2D;
import net.visualclient.util.ColorUtil;
import net.visualclient.util.Smooth;
import net.visualclient.util.TextUtil;

/** Shows the song that plays on your PC (Spotify, YouTube Music, any player) with optional synced lyrics. */
public final class MusicHudModule extends HudModule implements HasStatus {
	private final BoolSetting lyrics = add(new BoolSetting("Lyrics", true));
	private final BoolSetting progress = add(new BoolSetting("Progress bar", true));
	private final NumberSetting width = add(new NumberSetting("Width", 180, 130, 300, 5).suffix(" px"));
	private final BoolSetting hideIdle = add(new BoolSetting("Hide when nothing plays", true));

	private final Smooth lineFade = new Smooth(1f, 9f);
	private int lastLine = -2;
	private int lastPosSec = -1;
	private int lastDurSec = -1;
	private String timeText = "";
	private MediaInfo demo;

	private static final String[] DEMO_LYRICS = {"Previous line of the song", "The line that is sung right now", "Next line of the song"};

	public MusicHudModule() {
		super("Music HUD", "Shows the song that is playing on your PC, with lyrics.", Category.MISC);
		lyrics.describe("Shows synced lyrics. Needs internet: song, artist and length are sent to lrclib.net.");
		commonSettings();
	}

	@Override
	protected void onEnable() {
		MediaTracker.start();
	}

	@Override
	protected void onDisable() {
		MediaTracker.stop();
		LyricsService.reset();
	}

	@Override
	public String statusLine() {
		MediaInfo info = MediaTracker.current();
		if (info.hasTrack()) {
			return (info.playing ? "Playing: " : "Paused: ") + TextUtil.clip(info.title, 28);
		}
		String s = MediaTracker.status();
		return s.isEmpty() ? "Nothing is playing." : s;
	}

	@Override
	protected int defaultX(int sw, int sh) {
		return 6;
	}

	@Override
	protected int defaultY(int sw, int sh) {
		return sh - 90;
	}

	@Override
	protected void renderContent(GuiGraphics g, Minecraft mc, boolean editing) {
		MediaInfo info = MediaTracker.current();
		boolean isDemo = false;
		if (!info.hasTrack()) {
			if (editing) {
				if (demo == null) {
					demo = new MediaInfo("Song title", "Artist name", "Album", false, 83d, 215d, System.currentTimeMillis());
				}
				info = demo;
				isDemo = true;
			} else if (hideIdle.on()) {
				contentWidth = 0;
				contentHeight = 0;
				return;
			}
		}
		Font font = mc.font;
		int w = width.asInt();
		int pad = 9;
		int inner = w - pad * 2;

		if (!info.hasTrack()) {
			contentWidth = w;
			contentHeight = 22;
			box(g, w, 22);
			Render2D.text(g, font, Render2D.fit(font, "Nothing is playing", inner), pad, 7, muted());
			return;
		}

		boolean showLyrics = lyrics.on();
		LyricsService.Lyrics lines = LyricsService.Lyrics.MISSING;
		if (showLyrics && !isDemo) {
			lines = LyricsService.request(info);
		}
		double position = isDemo ? 83d : info.livePosition();

		int h = 8 + 10 + 2 + 10 + 7;
		if (progress.on() && info.duration > 0d) {
			h += 3 + 5;
		}
		boolean lyricBlock = showLyrics;
		if (lyricBlock) {
			h += 4 + 3 * 11 + 2;
		}
		contentWidth = w;
		contentHeight = h;
		box(g, w, h);

		int y = 8;
		Render2D.text(g, font, Render2D.fit(font, info.title, inner), pad, y, fg());
		y += 12;
		int posSec = (int) position;
		int durSec = (int) info.duration;
		if (posSec != lastPosSec || durSec != lastDurSec) {
			lastPosSec = posSec;
			lastDurSec = durSec;
			timeText = durSec > 0 ? TextUtil.mmss(posSec) + " / " + TextUtil.mmss(durSec) : TextUtil.mmss(posSec);
		}
		String time = timeText;
		int timeWidth = font.width(time);
		String artist = info.artist.isBlank() ? info.album : info.artist;
		Render2D.text(g, font, Render2D.fit(font, artist, inner - timeWidth - 6), pad, y, muted());
		Render2D.textRight(g, font, time, w - pad, y, muted());
		y += 12;

		if (progress.on() && info.duration > 0d) {
			float fraction = (float) Math.max(0d, Math.min(1d, position / info.duration));
			Render2D.round(g, pad, y, inner, 3, 1, ColorUtil.withAlpha(0xFFFFFF, 40));
			int filled = Math.max(2, Math.round(inner * fraction));
			Render2D.gradientH(g, pad, y, filled, 3, accent(), accent2(), Math.min(filled, 20));
			y += 8;
		}

		if (lyricBlock) {
			y += 2;
			Render2D.rect(g, pad, y, inner, 1, ColorUtil.withAlpha(0xFFFFFF, 28));
			y += 5;
			renderLyrics(g, font, lines, isDemo, position, pad, y, inner);
		}
	}

	private void renderLyrics(GuiGraphics g, Font font, LyricsService.Lyrics lines, boolean isDemo, double position, int x, int y, int maxWidth) {
		if (isDemo) {
			drawLine(g, font, DEMO_LYRICS[0], x, y, maxWidth, muted(), 0.55f);
			drawLine(g, font, DEMO_LYRICS[1], x, y + 11, maxWidth, fg(), 1f);
			drawLine(g, font, DEMO_LYRICS[2], x, y + 22, maxWidth, muted(), 0.55f);
			return;
		}
		if (lines.state == LyricsService.State.LOADING) {
			Render2D.text(g, font, "Loading lyrics...", x, y + 11, muted());
			return;
		}
		if (lines.state == LyricsService.State.MISSING) {
			Render2D.text(g, font, Render2D.fit(font, "No synced lyrics for this song", maxWidth), x, y + 11, muted());
			return;
		}
		List<LyricsService.Line> list = lines.lines;
		int index = lines.indexAt(position + 0.15d);
		if (index != lastLine) {
			lastLine = index;
			lineFade.snap(0f);
			lineFade.set(1f);
		}
		float fade = lineFade.get();
		String previous = index - 1 >= 0 ? list.get(index - 1).text() : "";
		String current = index >= 0 ? list.get(index).text() : "";
		String next = index + 1 < list.size() ? list.get(index + 1).text() : "";
		if (index < 0) {
			next = list.get(0).text();
			current = "";
		}
		drawLine(g, font, previous, x, y, maxWidth, muted(), 0.5f);
		drawLine(g, font, current.isEmpty() && index >= 0 ? "..." : current, x, y + 11, maxWidth, fg(), 0.45f + 0.55f * fade);
		drawLine(g, font, next, x, y + 22, maxWidth, muted(), 0.5f);
	}

	private static void drawLine(GuiGraphics g, Font font, String text, int x, int y, int maxWidth, int color, float alpha) {
		if (text == null || text.isEmpty()) {
			return;
		}
		Render2D.text(g, font, Render2D.fit(font, text, maxWidth), x, y, ColorUtil.mulAlpha(color, alpha));
	}
}
