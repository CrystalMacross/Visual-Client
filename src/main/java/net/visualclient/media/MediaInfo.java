package net.visualclient.media;

/** A snapshot of what the operating system says is playing right now. */
public final class MediaInfo {
	public static final MediaInfo NONE = new MediaInfo("", "", "", false, 0d, 0d, 0L);

	public final String title;
	public final String artist;
	public final String album;
	public final boolean playing;
	/** Playback position in seconds at {@link #capturedAt}. */
	public final double position;
	/** Track length in seconds, 0 when unknown. */
	public final double duration;
	/** Epoch milliseconds at which {@link #position} was valid. */
	public final long capturedAt;

	public MediaInfo(String title, String artist, String album, boolean playing, double position, double duration, long capturedAt) {
		this.title = title == null ? "" : title;
		this.artist = artist == null ? "" : artist;
		this.album = album == null ? "" : album;
		this.playing = playing;
		this.position = position;
		this.duration = duration;
		this.capturedAt = capturedAt;
	}

	public boolean hasTrack() {
		return !title.isBlank();
	}

	/** Position right now, extrapolated while the track is playing. */
	public double livePosition() {
		double p = position;
		if (playing) {
			p += Math.max(0L, System.currentTimeMillis() - capturedAt) / 1000d;
		}
		if (duration > 0d) {
			p = Math.min(p, duration);
		}
		return Math.max(0d, p);
	}

	private String keyCache;

	/** Identifies the track (not the position), used for caching lyrics. Built once per snapshot. */
	public String key() {
		String k = keyCache;
		if (k == null) {
			k = artist + "\u0000" + title + "\u0000" + album + "\u0000" + Math.round(duration);
			keyCache = k;
		}
		return k;
	}
}
