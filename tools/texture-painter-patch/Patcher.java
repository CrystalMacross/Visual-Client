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
 *  - "Configs" module (ConfigsModule): named configs + shareable config codes
 *  - "No Explosion Particles" module (Extra) via FpsBoostModule.dropParticle
 *  - Totem Tweaks: "No Pop Animation" setting; NoPopAnimationMixin cancels the pop animation
 *  - "Configs" tab in the menu: new Category CONFIGS + 7th tab in VisualGuiScreen (Configs module lives there)
 *  - Sakura forest title-screen panorama (resources/assets/minecraft/textures/gui/title/background)
 * Steps that are already present in the input jar are skipped, so patched jars can be patched again.
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
	static final String MODULES = "net/visualclient/modules/Modules";
	static final String CONFIGS = "net/visualclient/modules/misc/ConfigsModule";
	static final String FPS_BOOST = "net/visualclient/modules/visuals/FpsBoostModule";
	static final String NO_EXPLOSION = "net/visualclient/modules/extra/NoExplosionParticlesModule";
	static final String TOTEM_TWEAKS = "net/visualclient/modules/combat/TotemTweaksModule";
	static final String EXTRA_SETTINGS = "net/visualclient/core/ExtraSettings";
	static final String NUMBER_SETTING = "net/visualclient/core/setting/NumberSetting";

	static final String NO_POP_MIXIN = "net/visualclient/mixin/NoPopAnimationMixin";
	static final String CATEGORY = "net/visualclient/core/Category";
	static final String GUI_SCREEN = "net/visualclient/gui/VisualGuiScreen";

	/** Adds the enum constant Category.CONFIGS("Configs") as the last constant. */
	static byte[] patchCategory(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		if (cn.fields.stream().anyMatch(f -> f.name.equals("CONFIGS"))) { System.out.println("  Configs category already present"); return bytes; }
		String desc = "L" + CATEGORY + ";";
		int ordinal = (int) cn.fields.stream().filter(f -> (f.access & ACC_ENUM) != 0).count();
		cn.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "CONFIGS", desc, null, null));
		MethodNode clinit = method(cn, "<clinit>");
		MethodInsnNode values = null;
		for (AbstractInsnNode i : clinit.instructions) {
			if (i instanceof MethodInsnNode m && m.name.equals("$values")) { values = m; break; }
		}
		if (values == null) throw new IllegalStateException("Category: $values call not found");
		InsnList make = new InsnList();
		make.add(new TypeInsnNode(NEW, CATEGORY));
		make.add(new InsnNode(DUP));
		make.add(new LdcInsnNode("CONFIGS"));
		make.add(new IntInsnNode(BIPUSH, ordinal));
		make.add(new LdcInsnNode("Configs"));
		make.add(new MethodInsnNode(INVOKESPECIAL, CATEGORY, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", false));
		make.add(new FieldInsnNode(PUTSTATIC, CATEGORY, "CONFIGS", desc));
		clinit.instructions.insertBefore(values, make);
		// $values(): one more slot, CONFIGS at the end
		MethodNode vm = method(cn, "$values");
		AbstractInsnNode areturn = null;
		for (AbstractInsnNode i : vm.instructions) {
			if (i.getOpcode() == ANEWARRAY) vm.instructions.set(i.getPrevious(), new IntInsnNode(BIPUSH, ordinal + 1));
			if (i.getOpcode() == ARETURN) areturn = i;
		}
		InsnList add = new InsnList();
		add.add(new InsnNode(DUP));
		add.add(new IntInsnNode(BIPUSH, ordinal));
		add.add(new FieldInsnNode(GETSTATIC, CATEGORY, "CONFIGS", desc));
		add.add(new InsnNode(AASTORE));
		vm.instructions.insertBefore(areturn, add);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** VisualGuiScreen: TAB_LABELS += "Configs", TAB_CATEGORY += Category.CONFIGS. */
	static byte[] patchGuiTabs(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode clinit = method(cn, "<clinit>");
		for (AbstractInsnNode i : clinit.instructions) {
			if (i instanceof FieldInsnNode f && f.getOpcode() == GETSTATIC && f.name.equals("CONFIGS")) {
				System.out.println("  Configs tab already present");
				return bytes;
			}
		}
		growArray(clinit, "TAB_LABELS", new LdcInsnNode("Configs"));
		growArray(clinit, "TAB_CATEGORY", new FieldInsnNode(GETSTATIC, CATEGORY, "CONFIGS", "L" + CATEGORY + ";"));
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** For "size; anewarray; (dup; idx; value; aastore)*; putstatic field": size+1 and one more element. */
	static void growArray(MethodNode m, String field, AbstractInsnNode value) {
		FieldInsnNode put = null;
		for (AbstractInsnNode i : m.instructions) {
			if (i instanceof FieldInsnNode f && f.getOpcode() == PUTSTATIC && f.name.equals(field)) { put = f; break; }
		}
		if (put == null) throw new IllegalStateException("putstatic " + field + " not found");
		AbstractInsnNode a = put;
		int stores = 0;
		while (a != null && a.getOpcode() != ANEWARRAY) { if (a.getOpcode() == AASTORE) stores++; a = a.getPrevious(); }
		if (a == null) throw new IllegalStateException(field + ": anewarray not found");
		AbstractInsnNode size = a.getPrevious();
		int n = size instanceof IntInsnNode in ? in.operand : size.getOpcode() - ICONST_0;
		if (n != stores) throw new IllegalStateException(field + ": unexpected array layout " + n + "/" + stores);
		m.instructions.set(size, new IntInsnNode(BIPUSH, n + 1));
		InsnList add = new InsnList();
		add.add(new InsnNode(DUP));
		add.add(new IntInsnNode(BIPUSH, n));
		add.add(value);
		add.add(new InsnNode(AASTORE));
		m.instructions.insertBefore(put, add);
	}

	/** Adds a mixin class name to the "client" list of visualclient.mixins.json (once). */
	static byte[] addMixin(byte[] json, String mixin) {
		String s = new String(json, StandardCharsets.UTF_8);
		if (s.contains("\"" + mixin + "\"")) return json;
		int client = s.indexOf("\"client\"");
		int open = s.indexOf('[', client);
		if (client < 0 || open < 0) throw new IllegalStateException("mixins json: no client list");
		s = s.substring(0, open + 1) + "\n    \"" + mixin + "\"," + s.substring(open + 1);
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Builds the mixin that cancels GameRenderer.displayItemActivation (the totem pop animation)
	 * while "No Pop Animation" is on:
	 *   @Mixin(GameRenderer.class) abstract class NoPopAnimationMixin {
	 *     @Inject(method = "...", at = @At("HEAD"), cancellable = true, require = 0)
	 *     private void visualclient$noPopAnimation(CallbackInfo ci) { if (ExtraSettings.noPopActive()) ci.cancel(); }
	 *   }
	 * 1.21.x builds use intermediary names (class_757.method_3189), 26.x the official ones.
	 */
	static byte[] noPopMixin(boolean official) {
		String gameRenderer = official ? "net/minecraft/client/renderer/GameRenderer" : "net/minecraft/class_757";
		String target = official ? "displayItemActivation(Lnet/minecraft/world/item/ItemStack;)V" : "method_3189(Lnet/minecraft/class_1799;)V";
		String ci = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, NO_POP_MIXIN, null, "java/lang/Object", null);
		cw.visitSource("NoPopAnimationMixin.java", null);
		var mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		var targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(gameRenderer));
		targets.visitEnd();
		mixin.visitEnd();

		var ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(ALOAD, 0);
		ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		var m = cw.visitMethod(ACC_PRIVATE, "visualclient$noPopAnimation", "(L" + ci + ";)V", null, null);
		var inject = m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		var methods = inject.visitArray("method");
		methods.visit(null, target);
		methods.visitEnd();
		var at = inject.visitArray("at");
		var head = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		head.visit("value", "HEAD");
		head.visitEnd();
		at.visitEnd();
		inject.visit("cancellable", Boolean.TRUE);
		inject.visit("require", 0);
		inject.visitEnd();
		m.visitCode();
		var skip = new jdk.internal.org.objectweb.asm.Label();
		m.visitMethodInsn(INVOKESTATIC, EXTRA_SETTINGS, "noPopActive", "()Z", false);
		m.visitJumpInsn(IFEQ, skip);
		m.visitVarInsn(ALOAD, 1);
		m.visitMethodInsn(INVOKEVIRTUAL, ci, "cancel", "()V", false);
		m.visitLabel(skip);
		m.visitFrame(F_SAME, 0, null, 0, null);
		m.visitInsn(RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	static boolean references(MethodNode m, String owner) {
		for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.owner.equals(owner)) return true;
		return false;
	}

	/** Replaces "getfield <field>; invokevirtual <getter>" with "getfield <field>; invokestatic helper". */
	static int redirectGetter(MethodNode m, String field, String getter, String owner, String helper, String desc) {
		int n = 0;
		for (AbstractInsnNode i : m.instructions.toArray()) {
			if (i instanceof FieldInsnNode f && f.getOpcode() == GETFIELD && f.name.equals(field)
				&& f.getNext() instanceof MethodInsnNode call && call.name.equals(getter) && call.owner.equals(NUMBER_SETTING)) {
				m.instructions.set(call, new MethodInsnNode(INVOKESTATIC, owner, helper, desc, false));
				n++;
			}
		}
		return n;
	}

	/** dropParticle: explosion particles go through NoExplosionParticlesModule, the rest keeps FPS Boost's logic. */
	static byte[] patchFpsBoost(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode m = method(cn, "dropParticle");
		if (references(m, NO_EXPLOSION)) { System.out.println("  No Explosion Particles already present"); return bytes; }
		MethodInsnNode enabled = null;
		for (AbstractInsnNode i : m.instructions) {
			if (i instanceof MethodInsnNode mi && mi.name.equals("isEnabled") && mi.desc.equals("()Z")) { enabled = mi; break; }
		}
		if (enabled == null) throw new IllegalStateException("dropParticle: isEnabled not found");
		m.instructions.set(enabled, new MethodInsnNode(INVOKESTATIC, NO_EXPLOSION, "filterActive", "(Lnet/visualclient/core/ClientModule;)Z", false));
		String d = "(L" + NUMBER_SETTING + ";)D";
		int a = redirectGetter(m, "explosionParticles", "asDouble", NO_EXPLOSION, "explosionPercent", d);
		int b = redirectGetter(m, "particles", "asDouble", NO_EXPLOSION, "otherPercent", d);
		int c = redirectGetter(m, "totemParticles", "asDouble", NO_EXPLOSION, "otherPercent", d);
		if (a != 1 || b != 1 || c != 1) throw new IllegalStateException("dropParticle: unexpected pattern " + a + b + c);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** Totem Tweaks: register "No Pop Animation" and use it in popFactor. */
	static byte[] patchTotemTweaks(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode init = method(cn, "<init>");
		if (references(init, EXTRA_SETTINGS)) { System.out.println("  No Pop Animation already present"); return bytes; }
		AbstractInsnNode ret = null;
		for (AbstractInsnNode i : init.instructions) if (i.getOpcode() == RETURN) ret = i;
		if (ret == null) throw new IllegalStateException("TotemTweaks <init>: no return");
		InsnList add = new InsnList();
		add.add(new VarInsnNode(ALOAD, 0));
		add.add(new MethodInsnNode(INVOKESTATIC, EXTRA_SETTINGS, "totemTweaks", "(Lnet/visualclient/core/ClientModule;)V", false));
		init.instructions.insertBefore(ret, add);
		int n = redirectGetter(method(cn, "popFactor"), "popSize", "asFloat", EXTRA_SETTINGS, "popScale", "(L" + NUMBER_SETTING + ";)F");
		if (n != 1) throw new IllegalStateException("popFactor: unexpected pattern " + n);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** Modules.ALL = List.of(...)  ->  ConfigsModule.withConfigs(...) (adds the Configs module). */
	static byte[] patchModules(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode clinit = method(cn, "<clinit>");
		for (AbstractInsnNode n : clinit.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(CONFIGS)) {
				System.out.println("  Configs module already present");
				return bytes;
			}
		}
		for (AbstractInsnNode n : clinit.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("java/util/List") && m.name.equals("of")
				&& m.desc.equals("([Ljava/lang/Object;)Ljava/util/List;")
				&& m.getNext() instanceof FieldInsnNode f && f.name.equals("ALL")) {
				clinit.instructions.set(m, new MethodInsnNode(INVOKESTATIC, CONFIGS, "withConfigs", "([Ljava/lang/Object;)Ljava/util/List;", false));
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(cw);
				return cw.toByteArray();
			}
		}
		throw new IllegalStateException("Modules.ALL = List.of(...) not found");
	}

	public static void main(String[] args) throws Exception {
		Path in = Path.of(args[0]), out = Path.of(args[1]), extras = Path.of(args[2]);
		String version = args[3];
		Map<String, byte[]> extraClasses = new TreeMap<>();
		try (var walk = Files.walk(extras)) {
			// compiled helper classes plus any resources (e.g. the sakura title-screen panorama)
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				extraClasses.put(extras.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
			}
		}
		byte[] patched = patchBundle(Files.readAllBytes(in), extraClasses, version);
		Files.write(out, patched);
		System.out.println("Wrote " + out + " (" + patched.length + " bytes)");
	}

	// ------------------------------------------------------------------ jars

	static byte[] patchBundle(byte[] jar, Map<String, byte[]> extras, String version) throws IOException {
		// assets/ go into the bundle jar once (it is a Fabric mod too, so its resources are loaded);
		// classes go into every Minecraft build
		Map<String, byte[]> assets = new TreeMap<>(), classes = new TreeMap<>();
		for (var e : extras.entrySet()) (e.getKey().startsWith("assets/") ? assets : classes).put(e.getKey(), e.getValue());
		return rewriteJar(jar, (name, data) -> {
			if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
				System.out.println("Patching " + name);
				return patchInner(name, data, classes, version);
			}
			if (name.equals("fabric.mod.json")) {
				return setVersion(data, version);
			}
			return data;
		}, assets);
	}

	static byte[] patchInner(String jarName, byte[] jar, Map<String, byte[]> extraClasses, String version) throws IOException {
		boolean[] found = {false};
		boolean official = jarName.contains("-mc26.");
		Map<String, byte[]> add = new TreeMap<>(extraClasses);
		add.put(NO_POP_MIXIN + ".class", noPopMixin(official));
		byte[] result = rewriteJar(jar, (name, data) -> {
			if (name.equals(SCREEN + ".class")) {
				found[0] = true;
				return patchScreen(data);
			}
			if (name.equals(MODULES + ".class")) {
				return patchModules(data);
			}
			if (name.equals(FPS_BOOST + ".class")) {
				return patchFpsBoost(data);
			}
			if (name.equals(TOTEM_TWEAKS + ".class")) {
				return patchTotemTweaks(data);
			}
			if (name.equals(CATEGORY + ".class")) {
				return patchCategory(data);
			}
			if (name.equals(GUI_SCREEN + ".class")) {
				return patchGuiTabs(data);
			}
			if (name.equals("fabric.mod.json")) {
				return setVersion(data, version);
			}
			if (name.equals("visualclient.mixins.json")) {
				return addMixin(data, "NoPopAnimationMixin");
			}
			return data;
		}, add);
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
			System.out.println("  Texture Painter already patched");
			return bytes;
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
