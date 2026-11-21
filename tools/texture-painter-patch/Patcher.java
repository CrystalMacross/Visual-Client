import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Patches the Texture Painter of a Visual Client bundle jar (all Minecraft builds inside it):
 *  - "Export ZIP" button in the footer (TexturePainterExtras.exportZip)
 *  - extra list entry "minecraft:placed_end_crystal" for the placed End Crystal texture
 *  - save() writes through TexturePainterExtras.resolveTexture so that entry lands in textures/entity/...
 *
 * Usage (JDK 21):
 *   java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
 *        --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
 *        Patcher.java <in.jar> <out.jar> <extras-classes-dir> <new-version>
 */
public class Patcher implements Opcodes {
	static final String SCREEN = "net/visualclient/gui/TexturePainterScreen";
	static final String ENTRY = SCREEN + "$ItemEntry";
	static final String EXTRAS = "net/visualclient/gui/TexturePainterExtras";

	public static void main(String[] args) throws Exception {
		Path in = Path.of(args[0]), out = Path.of(args[1]), extras = Path.of(args[2]);
		String version = args[3];
		Map<String, byte[]> extraClasses = new TreeMap<>();
		try (var walk = Files.walk(extras)) {
			for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
				extraClasses.put(extras.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
			}
		}
		byte[] patched = patchBundle(Files.readAllBytes(in), extraClasses, version);
		Files.write(out, patched);
		System.out.println("Wrote " + out + " (" + patched.length + " bytes)");
	}

	// ------------------------------------------------------------------ jars

	static byte[] patchBundle(byte[] jar, Map<String, byte[]> extraClasses, String version) throws IOException {
		return rewriteJar(jar, (name, data) -> {
			if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
				System.out.println("Patching " + name);
				return patchInner(data, extraClasses, version);
			}
			if (name.equals("fabric.mod.json")) {
				return setVersion(data, version);
			}
			return data;
		}, Map.of());
	}

	static byte[] patchInner(byte[] jar, Map<String, byte[]> extraClasses, String version) throws IOException {
		boolean[] found = {false};
		byte[] result = rewriteJar(jar, (name, data) -> {
			if (name.equals(SCREEN + ".class")) {
				found[0] = true;
				return patchScreen(data);
			}
			if (name.equals("fabric.mod.json")) {
				return setVersion(data, version);
			}
			return data;
		}, extraClasses);
		if (!found[0]) throw new IllegalStateException("TexturePainterScreen not found");
		return result;
	}

	/** Keeps the build suffix of inner jars (e.g. 1.12.0.261 -> 1.12.1.261). */
	static byte[] setVersion(byte[] json, String version) {
		String s = new String(json, StandardCharsets.UTF_8);
		s = s.replaceFirst("\"version\"\\s*:\\s*\"[0-9]+\\.[0-9]+\\.[0-9]+", "\"version\": \"" + version);
		return s.getBytes(StandardCharsets.UTF_8);
	}

	interface Fn { byte[] apply(String name, byte[] data) throws IOException; }

	static byte[] rewriteJar(byte[] jar, Fn fn, Map<String, byte[]> add) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Set<String> seen = new HashSet<>();
		try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(jar));
		     ZipOutputStream zout = new ZipOutputStream(bos)) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				byte[] data = zin.readAllBytes();
				if (!e.isDirectory()) data = fn.apply(e.getName(), data);
				if (add.containsKey(e.getName())) data = add.get(e.getName());
				ZipEntry ne = new ZipEntry(e.getName());
				ne.setTime(e.getTime());
				zout.putNextEntry(ne);
				zout.write(data);
				zout.closeEntry();
				seen.add(e.getName());
			}
			for (var a : add.entrySet()) {
				if (seen.contains(a.getKey())) continue;
				zout.putNextEntry(new ZipEntry(a.getKey()));
				zout.write(a.getValue());
				zout.closeEntry();
			}
		}
		return bos.toByteArray();
	}

	// ------------------------------------------------------------------ class

	static byte[] patchScreen(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		if (cn.methods.stream().anyMatch(m -> m.name.equals("vc$addExtra"))) {
			throw new IllegalStateException("Already patched");
		}
		MethodNode allItems = method(cn, "allItems");
		MethodNode save = method(cn, "save");
		MethodNode footer = method(cn, "drawFooter");

		// ---- collect version specific Minecraft references from allItems()
		MethodInsnNode entryInit = null, stackInit = null, idOf = null, getNs = null, getPath = null, listAdd = null;
		for (AbstractInsnNode n : allItems.instructions) {
			if (!(n instanceof MethodInsnNode m)) continue;
			if (m.getOpcode() == INVOKESPECIAL && m.owner.equals(ENTRY) && m.name.equals("<init>")) entryInit = m;
			else if (m.getOpcode() == INVOKESPECIAL && m.name.equals("<init>") && !m.owner.startsWith("java/") && entryInit == null) stackInit = m;
			else if (m.getOpcode() == INVOKESTATIC && m.desc.startsWith("(Ljava/lang/String;Ljava/lang/String;)L") && idOf == null) idOf = m;
			else if (m.getOpcode() == INVOKEVIRTUAL && m.desc.equals("()Ljava/lang/String;") && getNs == null) getNs = m;
			else if (m.getOpcode() == INVOKEVIRTUAL && m.desc.equals("()Ljava/lang/String;") && getPath == null) getPath = m;
			else if (m.getOpcode() == INVOKEVIRTUAL && m.owner.equals("java/util/ArrayList") && m.name.equals("add") && entryInit != null && listAdd == null) listAdd = m;
		}
		require(entryInit, stackInit, idOf, getNs, getPath, listAdd);
		Type[] entryArgs = Type.getArgumentTypes(entryInit.desc); // Item, String, Identifier, ItemStack
		String item = entryArgs[0].getInternalName();
		String ident = entryArgs[2].getInternalName();
		if (!ident.equals(Type.getReturnType(idOf.desc).getInternalName())) throw new IllegalStateException("Identifier mismatch");

		// local slots: list (aload before NEW ItemEntry), item (aload after DUP), key (aload before getNamespace)
		int listVar = -1, itemVar = -1, keyVar = ((VarInsnNode) getNs.getPrevious()).var;
		for (AbstractInsnNode n : allItems.instructions) {
			if (n instanceof TypeInsnNode t && t.getOpcode() == NEW && t.desc.equals(ENTRY)) {
				listVar = ((VarInsnNode) t.getPrevious()).var;
				itemVar = ((VarInsnNode) t.getNext().getNext()).var;
				break;
			}
		}
		if (listVar < 0) throw new IllegalStateException("list var");

		// ---- new method: private static void vc$addExtra(ArrayList, Item, Identifier)
		String addDesc = "(Ljava/util/ArrayList;L" + item + ";L" + ident + ";)V";
		MethodNode add = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "vc$addExtra", addDesc, null, null);
		InsnList il = add.instructions;
		LabelNode skip = new LabelNode();
		il.add(new VarInsnNode(ALOAD, 2));
		il.add(getNs.clone(null));
		il.add(new VarInsnNode(ALOAD, 2));
		il.add(getPath.clone(null));
		il.add(new MethodInsnNode(INVOKESTATIC, EXTRAS, "extraEntry", "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;", false));
		il.add(new VarInsnNode(ASTORE, 3));
		il.add(new VarInsnNode(ALOAD, 3));
		il.add(new JumpInsnNode(IFNULL, skip));
		il.add(new VarInsnNode(ALOAD, 0));
		il.add(new TypeInsnNode(NEW, ENTRY));
		il.add(new InsnNode(DUP));
		il.add(new VarInsnNode(ALOAD, 1));
		il.add(new VarInsnNode(ALOAD, 3));
		il.add(new InsnNode(ICONST_0));
		il.add(new InsnNode(AALOAD));
		il.add(new VarInsnNode(ALOAD, 2));
		il.add(getNs.clone(null));
		il.add(new VarInsnNode(ALOAD, 3));
		il.add(new InsnNode(ICONST_1));
		il.add(new InsnNode(AALOAD));
		il.add(idOf.clone(null));
		il.add(new TypeInsnNode(NEW, stackInit.owner));
		il.add(new InsnNode(DUP));
		il.add(new VarInsnNode(ALOAD, 1));
		il.add(stackInit.clone(null));
		il.add(entryInit.clone(null));
		il.add(listAdd.clone(null));
		il.add(new InsnNode(POP));
		il.add(skip);
		il.add(new FrameNode(F_NEW, 4, new Object[] {"java/util/ArrayList", item, ident, "[Ljava/lang/String;"}, 0, new Object[0]));
		il.add(new InsnNode(RETURN));
		add.maxLocals = 4;
		add.maxStack = 8;
		cn.methods.add(add);

		// call it right after the normal entry was added: list.add(entry); pop;
		InsnList call = new InsnList();
		call.add(new VarInsnNode(ALOAD, listVar));
		call.add(new VarInsnNode(ALOAD, itemVar));
		call.add(new VarInsnNode(ALOAD, keyVar));
		call.add(new MethodInsnNode(INVOKESTATIC, SCREEN, "vc$addExtra", addDesc, false));
		AbstractInsnNode pop = listAdd.getNext();
		if (pop.getOpcode() != POP) throw new IllegalStateException("pop");
		allItems.instructions.insert(pop, call);
		allItems.maxStack = Math.max(allItems.maxStack, 3);

		// ---- save(): textures.resolve("item").resolve(path + ".png") -> TexturePainterExtras.resolveTexture(textures, path)
		LdcInsnNode itemLdc = null;
		for (AbstractInsnNode n : save.instructions) {
			if (n instanceof LdcInsnNode l && "item".equals(l.cst)) { itemLdc = l; break; }
		}
		if (itemLdc == null) throw new IllegalStateException("save: ldc item");
		AbstractInsnNode r1 = itemLdc.getNext(), pathLoad = r1.getNext(), concat = pathLoad.getNext(), r2 = concat.getNext();
		if (!(r1 instanceof MethodInsnNode m1 && m1.name.equals("resolve"))
			|| !(pathLoad instanceof VarInsnNode)
			|| !(concat instanceof InvokeDynamicInsnNode)
			|| !(r2 instanceof MethodInsnNode m2 && m2.name.equals("resolve"))) {
			throw new IllegalStateException("save: unexpected pattern");
		}
		save.instructions.remove(itemLdc);
		save.instructions.remove(r1);
		save.instructions.remove(concat);
		save.instructions.set(r2, new MethodInsnNode(INVOKESTATIC, EXTRAS, "resolveTexture",
			"(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/nio/file/Path;", false));

		// ---- new lambda body: private void vc$exportZip() { TexturePainterExtras.exportZip(this); }
		MethodNode export = new MethodNode(ACC_PRIVATE | ACC_SYNTHETIC, "vc$exportZip", "()V", null, null);
		export.instructions.add(new VarInsnNode(ALOAD, 0));
		export.instructions.add(new MethodInsnNode(INVOKESTATIC, EXTRAS, "exportZip", "(Ljava/lang/Object;)V", false));
		export.instructions.add(new InsnNode(RETURN));
		export.maxLocals = 1;
		export.maxStack = 1;
		cn.methods.add(export);

		// ---- drawFooter(): clone the "Save" button call as "Export ZIP" right after it
		LdcInsnNode saveLdc = null;
		for (AbstractInsnNode n : footer.instructions) {
			if (n instanceof LdcInsnNode l && "Save".equals(l.cst)) { saveLdc = l; break; }
		}
		if (saveLdc == null) throw new IllegalStateException("footer: Save");
		AbstractInsnNode first = saveLdc;
		for (int i = 0; i < 5; i++) first = first.getPrevious(); // aload0 aload1 aload2 iload x iload y
		if (first.getOpcode() != ALOAD || ((VarInsnNode) first).var != 0) throw new IllegalStateException("footer: start");
		AbstractInsnNode last = saveLdc;
		while (last.getOpcode() != ISTORE) last = last.getNext();
		InsnList btn = new InsnList();
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		for (AbstractInsnNode n = first; ; n = n.getNext()) {
			AbstractInsnNode c = n.clone(labels);
			if (c instanceof LdcInsnNode l && "Save".equals(l.cst)) l.cst = "Export ZIP";
			if (c instanceof InvokeDynamicInsnNode indy) {
				Object[] bsm = indy.bsmArgs.clone();
				Handle h = (Handle) bsm[1];
				bsm[1] = new Handle(H_INVOKESPECIAL, SCREEN, "vc$exportZip", "()V", false);
				if (h.getTag() == H_INVOKEVIRTUAL) bsm[1] = new Handle(H_INVOKEVIRTUAL, SCREEN, "vc$exportZip", "()V", false);
				indy = new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, bsm);
				c = indy;
			}
			btn.add(c);
			if (n == last) break;
		}
		footer.instructions.insert(last, btn);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static MethodNode method(ClassNode cn, String name) {
		return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst()
			.orElseThrow(() -> new IllegalStateException("method " + name));
	}

	static void require(Object... o) {
		for (int i = 0; i < o.length; i++) if (o[i] == null) throw new IllegalStateException("missing ref #" + i);
	}
}
