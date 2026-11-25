import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Builds visual-client-1.20.8.jar from the 1.20.7 bundle: in each of the eight nested jars
 *   - adds NoEquipAnimationModule (compiled classes dir) and the generated NoEquipAnimationMixin,
 *   - replaces VisualMixinPlugin and ConfigsModule (unchanged since 1.20.3 apart from this change),
 *   - registers NoEquipAnimationMixin in visualclient.mixins.json,
 *   - sets the version 1.20.7.x -> 1.20.8.x (outer and nested fabric.mod.json).
 * Every other entry is copied unchanged.
 * java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
 *      --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED Swap.java in.jar out.jar classesDir
 */
public class Swap {
	static final String FROM = "1.20.7", TO = "1.20.8";
	static final String MIXIN = "net/visualclient/mixin/NoEquipAnimationMixin";
	static final String[] CLASSES = {
		"net/visualclient/modules/extra/NoEquipAnimationModule.class",
		"net/visualclient/mixin/VisualMixinPlugin.class",
		"net/visualclient/modules/misc/ConfigsModule.class",
	};

	public static void main(String[] a) throws Exception {
		Path classes = Path.of(a[2]);
		Map<String, byte[]> outer = read(Files.readAllBytes(Path.of(a[0])));
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (var e : outer.entrySet()) {
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
		// classes from the 1.20.3 patch sources, compiled per build (they use no Minecraft names)
		Path dir = classes.resolve(name.substring(name.lastIndexOf('/') + 1, name.length() - 4));
		for (String c : CLASSES) {
			// inner classes of the replaced outer classes come from the new build only
			String outerName = c.substring(0, c.length() - 6);
			m.keySet().removeIf(k -> k.startsWith(outerName + "$") && k.endsWith(".class"));
			m.put(c, Files.readAllBytes(dir.resolve(c)));
			try (var w = Files.walk(dir.resolve(c).getParent())) {
				for (Path p : w.filter(p -> p.getFileName().toString().startsWith(Path.of(outerName).getFileName() + "$")).toList())
					m.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
			}
		}
		m.put(MIXIN + ".class", mixin(m.get("net/visualclient/mixin/ShieldMixin.class")));
		String json = new String(m.get("visualclient.mixins.json"), StandardCharsets.UTF_8);
		if (!json.contains("\"NoEquipAnimationMixin\"")) {
			int open = json.indexOf('[', json.indexOf("\"client\""));
			json = json.substring(0, open + 1) + "\n    \"NoEquipAnimationMixin\"," + json.substring(open + 1);
			m.put("visualclient.mixins.json", json.getBytes(StandardCharsets.UTF_8));
		}
		m.put("fabric.mod.json", version(m.get("fabric.mod.json")));
		System.out.println("  " + name + ": hand class " + target(m.get("net/visualclient/mixin/ShieldMixin.class")));
		return write(m);
	}

	static byte[] version(byte[] json) {
		String s = new String(json, StandardCharsets.UTF_8);
		String n = s.replaceFirst("\"version\"(\\s*):(\\s*)\"" + FROM.replace(".", "\\."), "\"version\"$1:$2\"" + TO);
		if (n.equals(s)) throw new IllegalStateException("version " + FROM + " not found");
		return n.getBytes(StandardCharsets.UTF_8);
	}

	/** The class holding the hand items: ShieldMixin's target, FirstPersonHandsAndItems in 26.3+. */
	static String target(byte[] shieldMixin) {
		ClassNode cn = new ClassNode();
		new ClassReader(shieldMixin).accept(cn, ClassReader.SKIP_CODE);
		for (AnnotationNode an : cn.invisibleAnnotations)
			if (an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
				for (int i = 0; i < an.values.size(); i += 2)
					if (an.values.get(i).equals("value")) {
						String t = ((Type) ((List<?>) an.values.get(i + 1)).get(0)).getInternalName();
						return t.endsWith("/FirstPersonHandsAndItemsRenderer") ? "net/minecraft/client/player/FirstPersonHandsAndItems" : t;
					}
		throw new IllegalStateException("ShieldMixin target not found");
	}

	/** Empty @Mixin(target) class: VisualMixinPlugin patches the target when this mixin is applied. */
	static byte[] mixin(byte[] shieldMixin) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT, MIXIN, null, "java/lang/Object", null);
		cw.visitSource("NoEquipAnimationMixin.java", null);
		var an = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		var arr = an.visitArray("value");
		arr.visit(null, Type.getObjectType(target(shieldMixin)));
		arr.visitEnd();
		an.visitEnd();
		var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
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
