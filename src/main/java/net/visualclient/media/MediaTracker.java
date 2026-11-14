package net.visualclient.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.visualclient.util.Log;
import net.visualclient.util.MiniJson;

/**
 * Reads "now playing" from the operating system (Windows media session, playerctl on Linux,
 * Spotify / Music on macOS). Everything runs on a background thread; nothing is sent anywhere.
 */
public final class MediaTracker {
	private static volatile MediaInfo current = MediaInfo.NONE;
	private static volatile String status = "Starting...";
	private static volatile boolean running;
	private static Thread worker;
	private static Process process;

	private MediaTracker() {
	}

	public static MediaInfo current() {
		return current;
	}

	/** Human readable reason when nothing can be read, for the HUD. */
	public static String status() {
		return status;
	}

	public static synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		status = "Looking for a media player...";
		worker = new Thread(MediaTracker::run, "VisualClient-Media");
		worker.setDaemon(true);
		worker.start();
	}

	public static synchronized void stop() {
		running = false;
		current = MediaInfo.NONE;
		Process p = process;
		if (p != null) {
			p.destroyForcibly();
			process = null;
		}
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
	}

	// ---------------------------------------------------------------------------------------------

	private static void run() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("win")) {
				windowsLoop();
			} else if (os.contains("mac")) {
				pollLoop(MediaTracker::macPoll);
			} else {
				pollLoop(MediaTracker::linuxPoll);
			}
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			status = "Could not read your media player.";
			Log.error("Media tracker stopped", t);
		}
	}

	private interface Poller {
		MediaInfo poll() throws Exception;
	}

	private static void pollLoop(Poller poller) throws InterruptedException {
		while (running) {
			long wait = 1000L;
			try {
				MediaInfo info = poller.poll();
				current = info;
				status = info.hasTrack() ? "" : "Nothing is playing.";
			} catch (IOException e) {
				current = MediaInfo.NONE;
				wait = 30_000L;
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				current = MediaInfo.NONE;
				status = "Could not read your media player.";
			}
			Thread.sleep(wait);
		}
	}

	// ---- Windows: one long-lived PowerShell process that prints a JSON line per second --------------

	static final String WINDOWS_SCRIPT = """
			$ErrorActionPreference = 'Stop'
			[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
			function Await($op, $type) {
			  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
			  $task.Wait(-1) | Out-Null
			  $task.Result
			}
			[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
			[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
			$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			while ($true) {
			  $line = '{"status":"None"}'
			  try {
			    $session = $manager.GetCurrentSession()
			    if ($session -ne $null) {
			      $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			      $timeline = $session.GetTimelineProperties()
			      $info = $session.GetPlaybackInfo()
			      $updated = 0
			      try { $updated = $timeline.LastUpdatedTime.ToUnixTimeMilliseconds() } catch { $updated = 0 }
			      $line = [ordered]@{
			        status = $info.PlaybackStatus.ToString()
			        title = [string]$props.Title
			        artist = [string]$props.Artist
			        album = [string]$props.AlbumTitle
			        position = $timeline.Position.TotalSeconds
			        start = $timeline.StartTime.TotalSeconds
			        end = $timeline.EndTime.TotalSeconds
			        updated = $updated
			      } | ConvertTo-Json -Compress
			    }
			  } catch { $line = '{"status":"Error"}' }
			  [Console]::Out.WriteLine($line)
			  [Console]::Out.Flush()
			  Start-Sleep -Milliseconds 800
			}
			""";

	private static void windowsLoop() throws Exception {
		String encoded = Base64.getEncoder().encodeToString(WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
		pb.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process p;
		try {
			p = pb.start();
		} catch (IOException e) {
			status = "PowerShell was not found, the Music HUD needs it on Windows.";
			return;
		}
		process = p;
		Runtime.getRuntime().addShutdownHook(new Thread(p::destroyForcibly));
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = reader.readLine()) != null) {
				line = line.replace("\uFEFF", "").trim();
				if (line.isEmpty()) {
					continue;
				}
				try {
					MediaInfo info = parseWindowsLine(line, System.currentTimeMillis());
					current = info;
					status = info.hasTrack() ? "" : "Nothing is playing.";
				} catch (RuntimeException ignored) {
					// ignore a malformed line, the next one will fix it
				}
			}
		} finally {
			p.destroyForcibly();
		}
		if (running) {
			status = "Windows did not report a media session.";
		}
	}

	/** Parses one JSON line printed by {@link #WINDOWS_SCRIPT}. Package-private for tests. */
	static MediaInfo parseWindowsLine(String line, long nowMs) {
		Map<String, Object> m = MiniJson.asMap(MiniJson.parse(line));
		if (m == null) {
			return MediaInfo.NONE;
		}
		String state = MiniJson.str(m.get("status"), "None");
		String title = MiniJson.str(m.get("title"), "");
		if (state.equals("None") || state.equals("Error") || title.isBlank()) {
			return MediaInfo.NONE;
		}
		boolean playing = state.equalsIgnoreCase("Playing");
		double position = Math.max(0d, MiniJson.num(m.get("position"), 0d));
		double length = Math.max(0d, MiniJson.num(m.get("end"), 0d) - MiniJson.num(m.get("start"), 0d));
		long updated = (long) MiniJson.num(m.get("updated"), 0d);
		long captured = nowMs;
		if (updated > 0L && updated <= nowMs && nowMs - updated < 6L * 3600_000L) {
			captured = updated;
		}
		return new MediaInfo(title, MiniJson.str(m.get("artist"), ""), MiniJson.str(m.get("album"), ""),
				playing, position, length, captured);
	}

	// ---- Linux / macOS: simple polling of a command line tool -------------------------------------

	private static final String SEP = "|~|";

	private static MediaInfo linuxPoll() throws Exception {
		String out = exec(List.of("playerctl", "metadata", "--format",
				"{{title}}" + SEP + "{{artist}}" + SEP + "{{album}}" + SEP + "{{mpris:length}}" + SEP + "{{status}}" + SEP + "{{position}}"));
		if (out == null) {
			status = "Nothing is playing.";
			return MediaInfo.NONE;
		}
		return parseFields(out.split(java.util.regex.Pattern.quote(SEP), -1), 1_000_000d, 1_000_000d, System.currentTimeMillis());
	}

	private static final String MAC_SCRIPT = """
			if application "Spotify" is running then
			  tell application "Spotify"
			    if player state is not stopped then
			      return (name of current track) & "|~|" & (artist of current track) & "|~|" & (album of current track) & "|~|" & (duration of current track) & "|~|" & (player state as string) & "|~|" & player position & "|~|spotify"
			    end if
			  end tell
			end if
			if application "Music" is running then
			  tell application "Music"
			    if player state is not stopped then
			      return (name of current track) & "|~|" & (artist of current track) & "|~|" & (album of current track) & "|~|" & (duration of current track) & "|~|" & (player state as string) & "|~|" & player position & "|~|music"
			    end if
			  end tell
			end if
			return ""
			""";

	private static MediaInfo macPoll() throws Exception {
		String out = exec(List.of("osascript", "-e", MAC_SCRIPT));
		if (out == null || out.isBlank()) {
			status = "Nothing is playing.";
			return MediaInfo.NONE;
		}
		String[] f = out.split(java.util.regex.Pattern.quote(SEP), -1);
		double durationDivisor = f.length > 6 && f[6].trim().equals("spotify") ? 1000d : 1d; // Spotify reports milliseconds
		return parseFields(f, durationDivisor, 1d, System.currentTimeMillis());
	}

	/** Fields: title, artist, album, duration, status, position. Package-private for tests. */
	static MediaInfo parseFields(String[] f, double durationDivisor, double positionDivisor, long nowMs) {
		if (f.length < 6 || f[0].isBlank()) {
			return MediaInfo.NONE;
		}
		double duration = number(f[3]) / durationDivisor;
		double position = number(f[5]) / positionDivisor;
		boolean playing = f[4].trim().equalsIgnoreCase("playing");
		return new MediaInfo(f[0].trim(), f[1].trim(), f[2].trim(), playing, position, duration, nowMs);
	}

	private static double number(String raw) {
		try {
			return Double.parseDouble(raw.trim().replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0d;
		}
	}

	/** Runs a command, returns its trimmed output or null when it exits with an error (no player). */
	private static String exec(List<String> command) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		try {
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() == 0 ? out : null;
		} finally {
			p.destroyForcibly();
		}
	}
}
