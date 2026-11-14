package net.visualclient.discord;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.visualclient.util.Log;
import net.visualclient.util.MiniJson;

/**
 * A tiny Discord Rich Presence client that talks to the local Discord app over its IPC pipe.
 * No external library and no network access: the data only goes to the Discord app on your PC.
 */
public final class DiscordRpc {
	/** What Discord should show. Blank texts are left out. */
	public record Activity(String details, String state, long startEpochSeconds, String largeImage, String largeText) {
		Map<String, Object> toMap() {
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("type", 0);
			if (details != null && details.length() >= 2) {
				a.put("details", cut(details));
			}
			if (state != null && state.length() >= 2) {
				a.put("state", cut(state));
			}
			if (startEpochSeconds > 0) {
				Map<String, Object> ts = new LinkedHashMap<>();
				ts.put("start", startEpochSeconds);
				a.put("timestamps", ts);
			}
			if (largeImage != null && !largeImage.isBlank()) {
				Map<String, Object> assets = new LinkedHashMap<>();
				assets.put("large_image", largeImage);
				if (largeText != null && largeText.length() >= 2) {
					assets.put("large_text", cut(largeText));
				}
				a.put("assets", assets);
			}
			return a;
		}

		private static String cut(String s) {
			return s.length() > 128 ? s.substring(0, 128) : s;
		}
	}

	/** One IPC frame: 4 byte opcode, 4 byte length (both little endian), JSON text. */
	public record Frame(int opcode, String json) {
	}

	/** Minimal byte pipe so tests can run without Discord. */
	public interface Pipe extends Closeable {
		void write(byte[] data) throws IOException;

		int read(byte[] buffer, int offset, int length) throws IOException;
	}

	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;
	private static final int OP_CLOSE = 2;
	private static final int OP_PING = 3;
	private static final int OP_PONG = 4;

	private static volatile boolean running;
	private static volatile String clientId = "";
	private static volatile Activity wanted;
	private static volatile String status = "Off";
	private static Thread worker;

	private DiscordRpc() {
	}

	public static String status() {
		return status;
	}

	public static void update(Activity activity) {
		wanted = activity;
	}

	public static synchronized void start(String applicationId) {
		stop();
		clientId = applicationId == null ? "" : applicationId.trim();
		running = true;
		status = clientId.isEmpty() ? "Add your Discord application ID." : "Connecting...";
		worker = new Thread(DiscordRpc::loop, "VisualClient-DiscordRPC");
		worker.setDaemon(true);
		worker.start();
	}

	public static synchronized void stop() {
		running = false;
		status = "Off";
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
	}

	// ---- codec ------------------------------------------------------------------------------------

	public static byte[] encodeFrame(int opcode, String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(opcode).putInt(body.length).put(body);
		return buf.array();
	}

	public static Frame readFrame(Pipe pipe) throws IOException {
		byte[] header = new byte[8];
		readFully(pipe, header);
		ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int opcode = hb.getInt();
		int length = hb.getInt();
		if (length < 0 || length > 1_000_000) {
			throw new IOException("Bad frame length " + length);
		}
		byte[] body = new byte[length];
		readFully(pipe, body);
		return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
	}

	private static void readFully(Pipe pipe, byte[] target) throws IOException {
		int done = 0;
		while (done < target.length) {
			int n = pipe.read(target, done, target.length - done);
			if (n < 0) {
				throw new IOException("Discord closed the connection");
			}
			done += n;
		}
	}

	static String handshakeJson(String applicationId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("v", 1);
		m.put("client_id", applicationId);
		return MiniJson.stringify(m);
	}

	static String activityJson(Activity activity, long pid, String nonce) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("pid", pid);
		args.put("activity", activity.toMap());
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("cmd", "SET_ACTIVITY");
		m.put("args", args);
		m.put("nonce", nonce);
		return MiniJson.stringify(m);
	}

	// ---- connection loop --------------------------------------------------------------------------

	private static void loop() {
		long backoff = 5_000L;
		while (running) {
			if (clientId.isEmpty()) {
				status = "Add your Discord application ID.";
				sleep(1000L);
				continue;
			}
			Pipe pipe = null;
			try {
				pipe = open();
				handshake(pipe);
				status = "Connected";
				backoff = 5_000L;
				session(pipe);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				if (running) {
					status = "Could not reach Discord. Is it running?";
					Log.warn("Discord RPC: {}", e.toString());
				}
			} finally {
				closeQuietly(pipe);
			}
			if (!sleep(backoff)) {
				return;
			}
			backoff = Math.min(backoff * 2, 60_000L);
		}
	}

	private static void handshake(Pipe pipe) throws IOException {
		boolean[] done = {false};
		Thread watchdog = new Thread(() -> {
			if (sleep(8000L) && !done[0]) {
				closeQuietly(pipe);
			}
		}, "VisualClient-DiscordRPC-Watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		try {
			pipe.write(encodeFrame(OP_HANDSHAKE, handshakeJson(clientId)));
			Frame reply = readFrame(pipe);
			Map<String, Object> m = MiniJson.asMap(MiniJson.parse(reply.json()));
			String evt = m == null ? "" : MiniJson.str(m.get("evt"), "");
			if (evt.equals("ERROR") || reply.opcode() == OP_CLOSE) {
				throw new IOException("Discord rejected the application ID");
			}
			done[0] = true;
		} finally {
			watchdog.interrupt();
		}
	}

	private static void session(Pipe pipe) throws Exception {
		boolean[] alive = {true};
		Thread reader = new Thread(() -> {
			try {
				while (alive[0] && running) {
					Frame f = readFrame(pipe);
					if (f.opcode() == OP_PING) {
						synchronized (pipe) {
							pipe.write(encodeFrame(OP_PONG, f.json()));
						}
					} else if (f.opcode() == OP_CLOSE) {
						break;
					}
				}
			} catch (Exception ignored) {
				// connection ended
			}
			alive[0] = false;
		}, "VisualClient-DiscordRPC-Reader");
		reader.setDaemon(true);
		reader.start();

		Activity last = null;
		long lastSent = 0L;
		long pid = ProcessHandle.current().pid();
		while (running && alive[0]) {
			Activity want = wanted;
			long now = System.currentTimeMillis();
			if (want != null && (lastSent == 0L || (!want.equals(last) && now - lastSent >= 15_000L))) {
				String json = activityJson(want, pid, UUID.randomUUID().toString());
				synchronized (pipe) {
					pipe.write(encodeFrame(OP_FRAME, json));
				}
				last = want;
				lastSent = now;
			}
			Thread.sleep(500L);
		}
		alive[0] = false;
		if (running) {
			throw new IOException("Discord closed the connection");
		}
	}

	// ---- transports -------------------------------------------------------------------------------

	private static final class FilePipe implements Pipe {
		private final RandomAccessFile file;

		FilePipe(RandomAccessFile file) {
			this.file = file;
		}

		@Override
		public void write(byte[] data) throws IOException {
			file.write(data);
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			return file.read(buffer, offset, length);
		}

		@Override
		public void close() throws IOException {
			file.close();
		}
	}

	private static final class ChannelPipe implements Pipe {
		private final SocketChannel channel;

		ChannelPipe(SocketChannel channel) {
			this.channel = channel;
		}

		@Override
		public void write(byte[] data) throws IOException {
			ByteBuffer bb = ByteBuffer.wrap(data);
			while (bb.hasRemaining()) {
				channel.write(bb);
			}
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			return channel.read(ByteBuffer.wrap(buffer, offset, length));
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}

	private static Pipe open() throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		IOException last = null;
		if (os.contains("win")) {
			for (int i = 0; i < 10; i++) {
				try {
					return new FilePipe(new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
				} catch (IOException e) {
					last = e;
				}
			}
		} else {
			List<String> dirs = new ArrayList<>();
			for (String key : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
				String value = System.getenv(key);
				if (value != null && !value.isBlank()) {
					dirs.add(value);
				}
			}
			dirs.add("/tmp");
			String[] subs = {"", "app/com.discordapp.Discord/", "snap.discord/", "snap.discord-canary/", "app/com.discordapp.DiscordCanary/"};
			for (String dir : dirs) {
				for (String sub : subs) {
					for (int i = 0; i < 10; i++) {
						Path p = Path.of(dir, sub + "discord-ipc-" + i);
						if (!Files.exists(p)) {
							continue;
						}
						try {
							SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
							channel.connect(UnixDomainSocketAddress.of(p));
							return new ChannelPipe(channel);
						} catch (IOException e) {
							last = e;
						}
					}
				}
			}
		}
		throw last != null ? last : new IOException("Discord IPC pipe not found");
	}

	private static void closeQuietly(Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (IOException ignored) {
				// already closed
			}
		}
	}

	/** Sleeps; returns false if interrupted or stopped. */
	private static boolean sleep(long ms) {
		try {
			Thread.sleep(ms);
			return running;
		} catch (InterruptedException e) {
			return false;
		}
	}
}
