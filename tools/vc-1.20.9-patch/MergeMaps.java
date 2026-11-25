import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Java port of tools/vc-1.20.9-patch/merge.py: Mojang ProGuard + intermediary tiny v2 -> tiny v2 official/intermediary/named. */
public class MergeMaps {
	static final Map<String, String> PRIM = Map.of("void", "V", "boolean", "Z", "byte", "B", "char", "C", "short", "S", "int", "I", "long", "J", "float", "F", "double", "D");
	static final Map<String, String> n2o = new HashMap<>();

	static String desc(String t) {
		int a = 0;
		while (t.endsWith("[]")) { t = t.substring(0, t.length() - 2); a++; }
		String d = PRIM.get(t);
		if (d == null) { String n = t.replace('.', '/'); d = "L" + n2o.getOrDefault(n, n) + ";"; }
		return "[".repeat(a) + d;
	}

	public static void main(String[] a) throws Exception {
		List<String[]> cls = new ArrayList<>();
		List<List<String>> mem = new ArrayList<>();
		for (String line : Files.readAllLines(Path.of(a[0]), StandardCharsets.UTF_8)) {
			if (line.startsWith("#") || line.isBlank()) continue;
			if (!line.startsWith(" ")) {
				String[] p = line.replaceAll(":$", "").split(" -> ");
				String[] c = {p[0].replace('.', '/'), p[1].replace('.', '/')};
				cls.add(c); mem.add(new ArrayList<>()); n2o.put(c[0], c[1]);
			} else mem.get(mem.size() - 1).add(line.trim());
		}
		Map<String, String> ic = new HashMap<>(), im = new HashMap<>(), ifd = new HashMap<>();
		String cc = null;
		for (String line : Files.readAllLines(Path.of(a[1]), StandardCharsets.UTF_8)) {
			String[] p = line.split("\t", -1);
			if (p[0].equals("c")) { cc = p[1]; ic.put(p[1], p[2]); }
			else if (p.length > 4 && p[1].equals("m")) im.put(cc + " " + p[3] + " " + p[2], p[4]);
			else if (p.length > 4 && p[1].equals("f")) ifd.put(cc + " " + p[3] + " " + p[2], p[4]);
		}
		Pattern pre = Pattern.compile("^\\d+:\\d+:"), post = Pattern.compile(":\\d+:\\d+$");
		try (var o = new PrintWriter(Files.newBufferedWriter(Path.of(a[2]), StandardCharsets.UTF_8))) {
			o.print("tiny\t2\t0\tofficial\tintermediary\tnamed\n");
			for (int i = 0; i < cls.size(); i++) {
				String n = cls.get(i)[0], ob = cls.get(i)[1];
				o.print("c\t" + ob + "\t" + ic.getOrDefault(ob, ob) + "\t" + n + "\n");
				for (String m : mem.get(i)) {
					String[] lr = m.split(" -> ");
					String lhs = post.matcher(pre.matcher(lr[0]).replaceFirst("")).replaceFirst(""), obn = lr[1];
					if (lhs.contains("(")) {
						int sp = lhs.indexOf(' ');
						String t = lhs.substring(0, sp), rest = lhs.substring(sp + 1);
						String name = rest.substring(0, rest.indexOf('(')), args = rest.substring(rest.indexOf('(') + 1).replaceAll("\\)$", "");
						StringBuilder d = new StringBuilder("(");
						for (String x : args.split(",")) if (!x.isEmpty()) d.append(desc(x));
						d.append(')').append(desc(t));
						String iv = im.get(ob + " " + obn + " " + d);
						if (iv == null) continue;
						o.print("\tm\t" + d + "\t" + obn + "\t" + iv + "\t" + name + "\n");
					} else {
						String[] tn = lhs.split(" ");
						String d = desc(tn[0]);
						String iv = ifd.get(ob + " " + obn + " " + d);
						if (iv == null) continue;
						o.print("\tf\t" + d + "\t" + obn + "\t" + iv + "\t" + tn[1] + "\n");
					}
				}
			}
		}
	}
}
