package net.visualclient.media;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.visualclient.util.Log;
import net.visualclient.util.MiniJson;

/**
 * Fetches time-synced lyrics from lrclib.net (free, no account). Only artist, title, album and length
 * of the current song are sent, and only while the Lyrics option of the Music HUD is on.
 */
public final class LyricsService {
	public enum State { LOADING, FOUND, MISSING }

	public record Line(double time, String text) {
	}

	public static final class Lyrics {
		public static final Lyrics LOADING = new Lyrics(State.LOADING, List.of());
		public static final Lyrics MISSING = new Lyrics(State.MISSING, List.of());

		public final State state;
		public final List<Line> lines;

		Lyrics(State state, List<Line> lines) {
			this.state = state;
			this.lines = lines;
		}

		/** Index of the line that is being sung at the given time, or -1 before the first line. */
		public int indexAt(double seconds) {
			int lo = 0;
			int hi = lines.size() - 1;
			int result = -1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (lines.get(mid).time() <= seconds) {
					result = mid;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			return result;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VisualClient-Lyrics");
		t.setDaemon(true);
		return t;
	});
	private static final Map<String, Lyrics> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
			return size() > 40;
		}
	};

	private static String currentKey = "";
	private static volatile Lyrics current = Lyrics.MISSING;

	private LyricsService() {
	}

	/** Returns lyrics for the track. Starts a background download the first time a track is seen. */
	public static Lyrics request(MediaInfo info) {
		if (!info.hasTrack()) {
			return Lyrics.MISSING;
		}
		String key = info.key();
		synchronized (CACHE) {
			if (key.equals(currentKey)) {
				return current;
			}
			currentKey = key;
			Lyrics cached = CACHE.get(key);
			if (cached != null) {
				current = cached;
				return cached;
			}
			current = Lyrics.LOADING;
		}
		POOL.execute(() -> {
			Lyrics result;
			try {
				result = download(info);
			} catch (Exception e) {
				Log.warn("Lyrics download failed: {}", e.toString());
				result = Lyrics.MISSING;
			}
			synchronized (CACHE) {
				CACHE.put(key, result);
				if (key.equals(currentKey)) {
					current = result;
				}
			}
		});
		return Lyrics.LOADING;
	}

	public static void reset() {
		synchronized (CACHE) {
			currentKey = "";
			current = Lyrics.MISSING;
		}
	}

	// ---------------------------------------------------------------------------------------------

	private static Lyrics download(MediaInfo info) throws Exception {
		String lrc = null;
		if (!info.album.isBlank() && info.duration > 0d) {
			String url = "https://lrclib.net/api/get?track_name=" + enc(info.title)
					+ "&artist_name=" + enc(info.artist)
					+ "&album_name=" + enc(info.album)
					+ "&duration=" + Math.round(info.duration);
			String body = httpGet(url);
			if (body != null) {
				lrc = syncedFrom(MiniJson.asMap(MiniJson.parse(body)));
			}
		}
		if (lrc == null) {
			String url = "https://lrclib.net/api/search?track_name=" + enc(info.title) + "&artist_name=" + enc(info.artist);
			String body = httpGet(url);
			if (body != null) {
				lrc = pickBest(MiniJson.asList(MiniJson.parse(body)), info.duration);
			}
		}
		return lrc == null ? Lyrics.MISSING : parseLrc(lrc);
	}

	private static String httpGet(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", "VisualClient/1.0 (Minecraft Fabric mod)")
				.GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return response.statusCode() == 200 ? response.body() : null;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String syncedFrom(Map<String, Object> entry) {
		if (entry == null) {
			return null;
		}
		String lrc = MiniJson.str(entry.get("syncedLyrics"), "");
		return lrc.isBlank() ? null : lrc;
	}

	/** From a search result list pick the entry with synced lyrics whose length is closest to ours. */
	static String pickBest(List<Object> results, double wantedDuration) {
		if (results == null) {
			return null;
		}
		String best = null;
		double bestDiff = Double.MAX_VALUE;
		for (Object o : results) {
			Map<String, Object> entry = MiniJson.asMap(o);
			String lrc = syncedFrom(entry);
			if (lrc == null) {
				continue;
			}
			double diff = wantedDuration > 0d ? Math.abs(MiniJson.num(entry.get("duration"), wantedDuration) - wantedDuration) : 0d;
			if (diff < bestDiff) {
				bestDiff = diff;
				best = lrc;
			}
		}
		return best;
	}

	/** Parses "[mm:ss.xx] text" lines. Lines with several time tags are repeated. */
	static Lyrics parseLrc(String lrc) {
		List<Line> lines = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			List<Double> times = new ArrayList<>();
			int i = 0;
			boolean metadata = false;
			while (i < raw.length() && raw.charAt(i) == '[') {
				int close = raw.indexOf(']', i);
				if (close < 0) {
					break;
				}
				Double t = parseTag(raw.substring(i + 1, close));
				if (t == null) {
					metadata = true;
					break;
				}
				times.add(t);
				i = close + 1;
			}
			if (metadata || times.isEmpty()) {
				continue;
			}
			String text = raw.substring(i).trim();
			for (double t : times) {
				lines.add(new Line(t, text));
			}
		}
		lines.sort(Comparator.comparingDouble(Line::time));
		return lines.isEmpty() ? Lyrics.MISSING : new Lyrics(State.FOUND, lines);
	}

	private static Double parseTag(String tag) {
		int colon = tag.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		try {
			int minutes = Integer.parseInt(tag.substring(0, colon).trim());
			double seconds = Double.parseDouble(tag.substring(colon + 1).trim().replace(',', '.'));
			return minutes * 60d + seconds;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
