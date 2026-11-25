import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Builds visual-client-1.20.9.jar from the 1.20.8 bundle (or another step, see the arguments): every class in classesDir/<nested jar name>/ is added
 * to that nested jar or replaces the existing one (old inner classes of a replaced class are dropped), and the
 * versions go 1.20.8.x -> 1.20.9.x (outer and nested fabric.mod.json). Everything else is copied unchanged.
 *   java Swap.java in.jar out.jar classesDir [fromVersion toVersion]
 */
public class Swap {
	static String FROM = "1.20.8", TO = "1.20.9";

	public static void main(String[] a) throws Exception {
		Path classes = Path.of(a[2]);
		if (a.length >= 5) {
			FROM = a[3];
			TO = a[4];
		}
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (var e : read(Files.readAllBytes(Path.of(a[0]))).entrySet()) {
			String n = e.getKey();
			byte[] d = e.getValue();
			if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) d = inner(n, d, classes);
			else if (n.equals("fabric.mod.json")) d = version(d);
			out.put(n, d);
		}
		Files.write(Path.of(a[1]), write(out));
		System.out.println("Wrote " + a[1]);
	}

	static byte[] inner(String name, byte[] jar, Path classes) throws Exception {
		Map<String, byte[]> m = read(jar);
		Path dir = classes.resolve(name.substring(name.lastIndexOf('/') + 1, name.length() - 4));
		Map<String, byte[]> add = new TreeMap<>();
		try (var w = Files.walk(dir)) {
			for (Path p : w.filter(p -> p.toString().endsWith(".class")).toList())
				add.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
		}
		Set<String> outers = new HashSet<>();
		for (String k : add.keySet()) outers.add(k.replaceAll("\\$.*$", "").replaceAll("\\.class$", ""));
		m.keySet().removeIf(k -> k.endsWith(".class") && k.contains("$") && outers.contains(k.replaceAll("\\$.*$", "")) && !add.containsKey(k));
		m.putAll(add);
		m.put("fabric.mod.json", version(m.get("fabric.mod.json")));
		System.out.println("  " + name + ": " + add.keySet());
		return write(m);
	}

	static byte[] version(byte[] json) {
		String s = new String(json, StandardCharsets.UTF_8);
		String n = s.replaceFirst("\"version\"(\\s*):(\\s*)\"" + FROM.replace(".", "\\."), "\"version\"$1:$2\"" + TO);
		if (n.equals(s)) throw new IllegalStateException("version " + FROM + " not found");
		return n.getBytes(StandardCharsets.UTF_8);
	}

	static Map<String, byte[]> read(byte[] jar) throws IOException {
		Map<String, byte[]> m = new LinkedHashMap<>();
		try (var in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) m.put(e.getName(), e.isDirectory() ? null : in.readAllBytes());
		}
		return m;
	}

	static byte[] write(Map<String, byte[]> m) throws IOException {
		var bo = new ByteArrayOutputStream();
		try (var out = new ZipOutputStream(bo)) {
			for (var e : m.entrySet()) {
				ZipEntry z = new ZipEntry(e.getKey());
				z.setTime(315532800000L + 86400000L);
				out.putNextEntry(z);
				if (e.getValue() != null) out.write(e.getValue());
				out.closeEntry();
			}
		}
		return bo.toByteArray();
	}
}
