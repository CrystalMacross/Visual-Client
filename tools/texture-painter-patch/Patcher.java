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
 *  - New menu ("UI: New" in GUI Style): NewGui + a generated NewGuiScreen per build, GuiOpener picks Old/New
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
	static final String NEW_SCREEN = "net/visualclient/gui/NewGuiScreen";
	static final String NEW_GUI = "net/visualclient/gui/NewGui";
	static final String COMPAT_SCREEN = "net/visualclient/compat/CompatScreen";
	static final String COMPAT = "net/visualclient/compat/Compat";
	static final String GUI_OPENER = "net/visualclient/gui/GuiOpener";
	static final String GUI_STYLE = "net/visualclient/modules/misc/GuiStyleModule";
	static final String CLIENT_MODULE = "net/visualclient/core/ClientModule";

	static Map<String, byte[]> readJar(byte[] jar) throws IOException {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				if (!e.isDirectory()) out.put(e.getName(), zin.readAllBytes());
			}
		}
		return out;
	}

	static ClassNode readClass(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	static MethodNode findMethod(ClassNode cn, java.util.function.Predicate<MethodNode> p, String what) {
		List<MethodNode> found = cn.methods.stream().filter(p).toList();
		if (found.size() != 1) throw new IllegalStateException(cn.name + ": expected one " + what + ", found " + found.size());
		return found.get(0);
	}

	static boolean is(MethodNode m, int flag) {
		return (m.access & flag) != 0;
	}

	/**
	 * Generates NewGuiScreen (extends CompatScreen): forwards init/render/input/scroll/close to NewGui
	 * and offers a few static bridges (scissor, current screen, open menu, close). Every Minecraft
	 * name is taken from the old menu (VisualGuiScreen) of the same build, so it fits each build.
	 */
	/** GuiGraphics.renderItem(ItemStack, int, int) as the mod itself calls it (ArmorHud, ...). */
	static MethodInsnNode findItemDraw(Map<String, byte[]> entries) {
		for (var e : entries.entrySet()) {
			if (!e.getKey().startsWith("net/visualclient/") || !e.getKey().endsWith(".class")) continue;
			ClassNode cn = readClass(e.getValue());
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode n : m.instructions) {
					if (n instanceof MethodInsnNode mi && mi.getOpcode() == INVOKEVIRTUAL
						&& (mi.desc.equals("(Lnet/minecraft/class_1799;II)V") || mi.desc.equals("(Lnet/minecraft/world/item/ItemStack;II)V"))
						&& (mi.owner.equals("net/minecraft/class_332") || mi.owner.startsWith("net/minecraft/client/gui/GuiGraphics"))) {
						return mi;
					}
				}
			}
		}
		throw new IllegalStateException("no item draw call found");
	}

	static byte[] newGuiScreen(byte[] vgsBytes, byte[] compatBytes, byte[] openerBytes) {
		return newGuiScreen(vgsBytes, compatBytes, openerBytes, null);
	}

	static byte[] newGuiScreen(byte[] vgsBytes, byte[] compatBytes, byte[] openerBytes, MethodInsnNode itemDraw) {
		ClassNode vgs = readClass(vgsBytes);
		ClassNode compat = readClass(compatBytes);
		ClassNode opener = readClass(openerBytes);

		// constructor pieces: Component.literal("Visual Client") and the CompatScreen super call
		MethodNode vctor = method(vgs, "<init>");
		MethodInsnNode literal = null, superInit = null;
		for (AbstractInsnNode n : vctor.instructions) {
			if (literal == null && n instanceof LdcInsnNode l && "Visual Client".equals(l.cst) && n.getNext() instanceof MethodInsnNode m) literal = m;
			if (superInit == null && n instanceof MethodInsnNode m && m.getOpcode() == INVOKESPECIAL && m.owner.equals(COMPAT_SCREEN) && m.name.equals("<init>")) superInit = m;
		}
		require(literal, superInit);

		MethodNode init = findMethod(vgs, m -> is(m, ACC_PROTECTED) && !is(m, ACC_STATIC) && m.desc.equals("()V") && !m.name.startsWith("<") && !m.name.startsWith("lambda$"), "init()");
		MethodNode pause = findMethod(vgs, m -> is(m, ACC_PUBLIC) && !is(m, ACC_STATIC) && m.desc.equals("()Z"), "isPauseScreen()");
		MethodNode scroll = findMethod(vgs, m -> is(m, ACC_PUBLIC) && !is(m, ACC_STATIC) && m.desc.equals("(DDDD)Z"), "mouseScrolled()");
		MethodNode close = findMethod(vgs, m -> is(m, ACC_PUBLIC) && !is(m, ACC_STATIC) && m.desc.equals("()V") && !m.name.startsWith("<"), "onClose()");
		List<MethodNode> draws = vgs.methods.stream().filter(m -> is(m, ACC_PUBLIC) && !is(m, ACC_STATIC) && m.desc.endsWith("IIF)V")
			&& Type.getArgumentTypes(m.desc).length == 4).toList();
		if (draws.size() != 2) throw new IllegalStateException("VisualGuiScreen: expected background + render, found " + draws.size());
		MethodNode background = draws.stream().filter(m -> callsMethod(m, "blurBackground")).findFirst().orElseThrow();
		MethodNode render = draws.get(0) == background ? draws.get(1) : draws.get(0);
		String gfx = Type.getArgumentTypes(render.desc)[0].getInternalName();

		// font = Minecraft.getInstance().font ; width/height fields ; scissor calls
		MethodInsnNode getInstance = null;
		FieldInsnNode fontField = null;
		for (AbstractInsnNode n : render.instructions) {
			if (n instanceof MethodInsnNode m && m.getOpcode() == INVOKESTATIC && n.getNext() instanceof FieldInsnNode f && f.getOpcode() == GETFIELD) {
				getInstance = m;
				fontField = f;
				break;
			}
		}
		require(getInstance, fontField);
		FieldInsnNode widthField = intField(vgs, "width", "field_22789");
		FieldInsnNode heightField = intField(vgs, "height", "field_22790");
		// enableScissor(IIII) and the first no-argument call on the graphics object after it (disableScissor)
		MethodInsnNode scissorOn = null, scissorOff = null;
		for (MethodNode m : vgs.methods) {
			MethodInsnNode on = null;
			for (AbstractInsnNode n : m.instructions) {
				if (n instanceof MethodInsnNode mi && mi.owner.equals(gfx) && mi.getOpcode() == INVOKEVIRTUAL) {
					if (mi.desc.equals("(IIII)V")) on = mi;
					else if (on != null && mi.desc.equals("()V")) { scissorOn = on; scissorOff = mi; break; }
				}
			}
			if (scissorOff != null) break;
		}
		require(scissorOn, scissorOff);
		MethodNode screenOf = method(compat, "screen");
		MethodNode setScreen = method(compat, "setScreen");
		MethodNode open = method(opener, "open");

		ClassNode cn = new ClassNode();
		cn.version = vgs.version;
		cn.access = ACC_PUBLIC | ACC_SUPER;
		cn.name = NEW_SCREEN;
		cn.superName = COMPAT_SCREEN;
		cn.sourceFile = "NewGuiScreen.java";
		String guiDesc = "L" + NEW_GUI + ";";
		cn.fields.add(new FieldNode(ACC_PRIVATE | ACC_FINAL, "gui", guiDesc, null, null));

		MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
		InsnList c = ctor.instructions;
		c.add(new VarInsnNode(ALOAD, 0));
		c.add(new LdcInsnNode("Visual Client"));
		c.add(literal.clone(null));
		c.add(superInit.clone(null));
		c.add(new VarInsnNode(ALOAD, 0));
		c.add(new TypeInsnNode(NEW, NEW_GUI));
		c.add(new InsnNode(DUP));
		c.add(new VarInsnNode(ALOAD, 0));
		c.add(new MethodInsnNode(INVOKESPECIAL, NEW_GUI, "<init>", "(Ljava/lang/Object;)V", false));
		c.add(new FieldInsnNode(PUTFIELD, NEW_SCREEN, "gui", guiDesc));
		c.add(new InsnNode(RETURN));
		cn.methods.add(ctor);

		MethodNode mInit = new MethodNode(ACC_PROTECTED, init.name, "()V", null, null);
		gui(mInit.instructions, guiDesc);
		mInit.instructions.add(new VarInsnNode(ALOAD, 0));
		mInit.instructions.add(owned(widthField));
		mInit.instructions.add(new VarInsnNode(ALOAD, 0));
		mInit.instructions.add(owned(heightField));
		mInit.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, NEW_GUI, "init", "(II)V", false));
		mInit.instructions.add(new InsnNode(RETURN));
		cn.methods.add(mInit);

		MethodNode mPause = new MethodNode(ACC_PUBLIC, pause.name, "()Z", null, null);
		mPause.instructions.add(new InsnNode(ICONST_0));
		mPause.instructions.add(new InsnNode(IRETURN));
		cn.methods.add(mPause);

		cn.methods.add(copyRemapped(background, GUI_SCREEN, NEW_SCREEN));

		MethodNode mRender = new MethodNode(ACC_PUBLIC, render.name, render.desc, null, null);
		InsnList rd = mRender.instructions;
		gui(rd, guiDesc);
		rd.add(new VarInsnNode(ALOAD, 1));
		rd.add(getInstance.clone(null));
		rd.add(fontField.clone(null));
		rd.add(new VarInsnNode(ALOAD, 0));
		rd.add(owned(widthField));
		rd.add(new VarInsnNode(ALOAD, 0));
		rd.add(owned(heightField));
		rd.add(new VarInsnNode(ILOAD, 2));
		rd.add(new VarInsnNode(ILOAD, 3));
		rd.add(new VarInsnNode(FLOAD, 4));
		rd.add(new MethodInsnNode(INVOKEVIRTUAL, NEW_GUI, "render", "(Ljava/lang/Object;Ljava/lang/Object;IIIIF)V", false));
		rd.add(new InsnNode(RETURN));
		cn.methods.add(mRender);

		cn.methods.add(forward(ACC_PROTECTED, "onKey", "(III)Z", "key", guiDesc));
		cn.methods.add(forward(ACC_PROTECTED, "onChar", "(Ljava/lang/String;)Z", "chr", guiDesc));
		cn.methods.add(forward(ACC_PROTECTED, "onMouseDown", "(DDI)Z", "mouseDown", guiDesc));
		cn.methods.add(forward(ACC_PROTECTED, "onMouseDrag", "(DDIDD)Z", "mouseDrag", guiDesc));
		cn.methods.add(forward(ACC_PROTECTED, "onMouseUp", "(DDI)Z", "mouseUp", guiDesc));
		cn.methods.add(forward(ACC_PUBLIC, scroll.name, "(DDDD)Z", "scroll", guiDesc));

		MethodNode mClose = new MethodNode(ACC_PUBLIC, close.name, "()V", null, null);
		gui(mClose.instructions, guiDesc);
		mClose.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, NEW_GUI, "close", "()V", false));
		mClose.instructions.add(new VarInsnNode(ALOAD, 0));
		mClose.instructions.add(new MethodInsnNode(INVOKESPECIAL, COMPAT_SCREEN, close.name, "()V", false));
		mClose.instructions.add(new InsnNode(RETURN));
		cn.methods.add(mClose);

		// static bridges used by NewGui / ExtraSettings
		MethodNode bOn = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$scissor", "(Ljava/lang/Object;IIII)V", null, null);
		bOn.instructions.add(new VarInsnNode(ALOAD, 0));
		bOn.instructions.add(new TypeInsnNode(CHECKCAST, gfx));
		for (int i = 1; i <= 4; i++) bOn.instructions.add(new VarInsnNode(ILOAD, i));
		bOn.instructions.add(scissorOn.clone(null));
		bOn.instructions.add(new InsnNode(RETURN));
		cn.methods.add(bOn);

		MethodNode bOff = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$unscissor", "(Ljava/lang/Object;)V", null, null);
		bOff.instructions.add(new VarInsnNode(ALOAD, 0));
		bOff.instructions.add(new TypeInsnNode(CHECKCAST, gfx));
		bOff.instructions.add(scissorOff.clone(null));
		bOff.instructions.add(new InsnNode(RETURN));
		cn.methods.add(bOff);

		MethodNode bCur = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$currentScreen", "()Ljava/lang/Object;", null, null);
		bCur.instructions.add(getInstance.clone(null));
		bCur.instructions.add(new MethodInsnNode(INVOKESTATIC, COMPAT, "screen", screenOf.desc, false));
		bCur.instructions.add(new InsnNode(ARETURN));
		cn.methods.add(bCur);

		MethodNode bOpen = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$openMenu", "()V", null, null);
		bOpen.instructions.add(getInstance.clone(null));
		bOpen.instructions.add(new MethodInsnNode(INVOKESTATIC, GUI_OPENER, "open", open.desc, false));
		bOpen.instructions.add(new MethodInsnNode(INVOKESTATIC, COMPAT, "setScreen", setScreen.desc, false));
		bOpen.instructions.add(new InsnNode(RETURN));
		cn.methods.add(bOpen);

		if (itemDraw != null) {
			MethodNode bItem = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$item", "(Ljava/lang/Object;Ljava/lang/Object;II)V", null, null);
			bItem.instructions.add(new VarInsnNode(ALOAD, 0));
			bItem.instructions.add(new TypeInsnNode(CHECKCAST, itemDraw.owner));
			bItem.instructions.add(new VarInsnNode(ALOAD, 1));
			bItem.instructions.add(new TypeInsnNode(CHECKCAST, Type.getArgumentTypes(itemDraw.desc)[0].getInternalName()));
			bItem.instructions.add(new VarInsnNode(ILOAD, 2));
			bItem.instructions.add(new VarInsnNode(ILOAD, 3));
			bItem.instructions.add(itemDraw.clone(null));
			bItem.instructions.add(new InsnNode(RETURN));
			cn.methods.add(bItem);
		}

		MethodNode bNew = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$openNew", "()V", null, null);
		bNew.instructions.add(getInstance.clone(null));
		bNew.instructions.add(new TypeInsnNode(NEW, NEW_SCREEN));
		bNew.instructions.add(new InsnNode(DUP));
		bNew.instructions.add(new MethodInsnNode(INVOKESPECIAL, NEW_SCREEN, "<init>", "()V", false));
		bNew.instructions.add(new MethodInsnNode(INVOKESTATIC, COMPAT, "setScreen", setScreen.desc, false));
		bNew.instructions.add(new InsnNode(RETURN));
		cn.methods.add(bNew);

		MethodNode bClose = new MethodNode(ACC_PUBLIC | ACC_STATIC, "vc$close", "(Ljava/lang/Object;)V", null, null);
		bClose.instructions.add(new VarInsnNode(ALOAD, 0));
		bClose.instructions.add(new TypeInsnNode(CHECKCAST, NEW_SCREEN));
		bClose.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, NEW_SCREEN, close.name, "()V", false));
		bClose.instructions.add(new InsnNode(RETURN));
		cn.methods.add(bClose);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static void gui(InsnList l, String guiDesc) {
		l.add(new VarInsnNode(ALOAD, 0));
		l.add(new FieldInsnNode(GETFIELD, NEW_SCREEN, "gui", guiDesc));
	}

	static MethodNode forward(int access, String name, String desc, String target, String guiDesc) {
		MethodNode m = new MethodNode(access, name, desc, null, null);
		gui(m.instructions, guiDesc);
		int slot = 1;
		for (Type t : Type.getArgumentTypes(desc)) {
			m.instructions.add(new VarInsnNode(t.getOpcode(ILOAD), slot));
			slot += t.getSize();
		}
		m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, NEW_GUI, target, desc, false));
		m.instructions.add(new InsnNode(Type.getReturnType(desc).getOpcode(IRETURN)));
		return m;
	}

	static FieldInsnNode intField(ClassNode vgs, String official, String intermediary) {
		for (MethodNode m : vgs.methods) {
			for (AbstractInsnNode n : m.instructions) {
				if (n instanceof FieldInsnNode f && f.getOpcode() == GETFIELD && f.desc.equals("I")
					&& (f.name.equals(official) || f.name.equals(intermediary))) return f;
			}
		}
		throw new IllegalStateException("VisualGuiScreen: field " + official + " not found");
	}

	static FieldInsnNode owned(FieldInsnNode f) {
		return new FieldInsnNode(GETFIELD, NEW_SCREEN, f.name, f.desc);
	}

	static boolean callsMethod(MethodNode m, String name) {
		for (AbstractInsnNode n : m.instructions) if (n instanceof MethodInsnNode mi && mi.name.equals(name)) return true;
		return false;
	}

	/** Copies a method and replaces every reference to one class by another (owners, descriptors, frames). */
	static MethodNode copyRemapped(MethodNode src, String from, String to) {
		MethodNode m = new MethodNode(src.access, src.name, src.desc, src.signature, src.exceptions.toArray(new String[0]));
		src.accept(m);
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof MethodInsnNode mi) {
				if (mi.owner.equals(from)) mi.owner = to;
				mi.desc = mi.desc.replace("L" + from + ";", "L" + to + ";");
			} else if (n instanceof FieldInsnNode fi) {
				if (fi.owner.equals(from)) fi.owner = to;
				fi.desc = fi.desc.replace("L" + from + ";", "L" + to + ";");
			} else if (n instanceof TypeInsnNode ti) {
				if (ti.desc.equals(from)) ti.desc = to;
			} else if (n instanceof FrameNode fr) {
				if (fr.local != null) fr.local.replaceAll(o -> from.equals(o) ? to : o);
				if (fr.stack != null) fr.stack.replaceAll(o -> from.equals(o) ? to : o);
			}
		}
		if (m.localVariables != null) {
			for (LocalVariableNode lv : m.localVariables) lv.desc = lv.desc.replace("L" + from + ";", "L" + to + ";");
		}
		return m;
	}

	/** GuiOpener.open(): "UI: New" -> new NewGuiScreen(), otherwise the old menu. */
	static byte[] patchGuiOpener(byte[] bytes) {
		ClassNode cn = readClass(bytes);
		MethodNode open = method(cn, "open");
		for (AbstractInsnNode n : open.instructions) {
			if (n instanceof TypeInsnNode t && t.desc.equals(NEW_SCREEN)) { System.out.println("  New UI already present"); return bytes; }
		}
		InsnList pre = new InsnList();
		LabelNode old = new LabelNode();
		pre.add(new MethodInsnNode(INVOKESTATIC, EXTRA_SETTINGS, "useNewUi", "()Z", false));
		pre.add(new JumpInsnNode(IFEQ, old));
		pre.add(new TypeInsnNode(NEW, NEW_SCREEN));
		pre.add(new InsnNode(DUP));
		pre.add(new MethodInsnNode(INVOKESPECIAL, NEW_SCREEN, "<init>", "()V", false));
		pre.add(new InsnNode(ARETURN));
		pre.add(old);
		pre.add(new FrameNode(F_SAME, 0, null, 0, null));
		open.instructions.insert(pre);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** GuiStyleModule: register "UI" (Old/New) as its first setting. */
	static byte[] patchGuiStyle(byte[] bytes) {
		ClassNode cn = readClass(bytes);
		MethodNode init = method(cn, "<init>");
		if (callsMethod(init, "guiStyle")) { System.out.println("  UI setting already present"); return bytes; }
		MethodInsnNode sup = null;
		for (AbstractInsnNode n : init.instructions) {
			if (n instanceof MethodInsnNode m && m.getOpcode() == INVOKESPECIAL && m.owner.equals(CLIENT_MODULE) && m.name.equals("<init>")) { sup = m; break; }
		}
		if (sup == null) throw new IllegalStateException("GuiStyleModule: super constructor not found");
		InsnList add = new InsnList();
		add.add(new VarInsnNode(ALOAD, 0));
		add.add(new MethodInsnNode(INVOKESTATIC, EXTRA_SETTINGS, "guiStyle", "(L" + CLIENT_MODULE + ";)V", false));
		init.instructions.insert(sup, add);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

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

	static final String XP_ORB_MIXIN = "net/visualclient/mixin/XpOrbRendererMixin";
	static final String ITEM_SIZE_MIXIN = "net/visualclient/mixin/ItemSizeMixin";
	static final String ITEM_SIZE = "net/visualclient/modules/extra/ItemSizeModule";

	/**
	 * Builds ItemSizeMixin from the mod's own XpOrbRendererMixin of the same build: that mixin
	 * hooks ExperienceOrbRenderer.extractRenderState (capture) and submit/render (scale) with the
	 * right names and descriptors for the build. Swapping orb -> item entity, orb state -> item
	 * state and orb renderer -> item renderer, and routing its calls to ItemSizeModule, gives the
	 * same two hooks for ItemEntityRenderer.
	 */
	static byte[] itemSizeMixin(byte[] xpMixin, boolean official) {
		if (xpMixin == null) throw new IllegalStateException("XpOrbRendererMixin not found");
		Map<String, String> map = new java.util.LinkedHashMap<>();
		map.put(XP_ORB_MIXIN, ITEM_SIZE_MIXIN);
		if (official) {
			map.put("net/minecraft/client/renderer/entity/state/ExperienceOrbRenderState", "net/minecraft/client/renderer/entity/state/ItemEntityRenderState");
			map.put("net/minecraft/client/renderer/entity/ExperienceOrbRenderer", "net/minecraft/client/renderer/entity/ItemEntityRenderer");
			map.put("net/minecraft/world/entity/ExperienceOrb", "net/minecraft/world/entity/item/ItemEntity");
		} else {
			map.put("net/minecraft/class_10022", "net/minecraft/class_10039");
			map.put("net/minecraft/class_902", "net/minecraft/class_916");
			map.put("net/minecraft/class_1303", "net/minecraft/class_1542");
		}
		java.util.function.UnaryOperator<String> sw = v -> {
			if (v == null) return null;
			for (var e : map.entrySet()) v = v.replace(e.getKey() + ";", e.getValue() + ";").replace(e.getKey() + ".", e.getValue() + ".");
			if (!official) {
				// EntityRenderer.render / updateRenderState in intermediary (yarn: method_3936, method_62354)
				v = v.replace("method_3966(", "method_3936(").replace("method_62434(", "method_62354(");
			}
			String exact = map.get(v);
			return exact != null ? exact : v;
		};
		ClassNode cn = new ClassNode();
		new ClassReader(xpMixin).accept(cn, 0);
		cn.name = ITEM_SIZE_MIXIN;
		cn.sourceFile = "ItemSizeMixin.java";
		cn.visibleAnnotations = remapAnnotations(cn.visibleAnnotations, sw);
		cn.invisibleAnnotations = remapAnnotations(cn.invisibleAnnotations, sw);
		int hooks = 0;
		for (MethodNode m : cn.methods) {
			m.desc = sw.apply(m.desc);
			m.signature = sw.apply(m.signature);
			m.visibleAnnotations = remapAnnotations(m.visibleAnnotations, sw);
			m.invisibleAnnotations = remapAnnotations(m.invisibleAnnotations, sw);
			if (m.localVariables != null) for (var lv : m.localVariables) lv.desc = sw.apply(lv.desc);
			for (AbstractInsnNode in : m.instructions) {
				if (in instanceof MethodInsnNode mi) {
					if (mi.owner.startsWith("net/visualclient/modules/") && mi.getOpcode() == INVOKESTATIC) {
						mi.owner = ITEM_SIZE;
						mi.name = mi.name.contains("capture") ? "captureState" : "scale";
						mi.desc = "(Ljava/lang/Object;Ljava/lang/Object;)V";
						hooks++;
					} else {
						mi.owner = sw.apply(mi.owner);
						mi.desc = sw.apply(mi.desc);
					}
				} else if (in instanceof TypeInsnNode ti) {
					ti.desc = sw.apply(ti.desc);
				} else if (in instanceof FieldInsnNode fi) {
					fi.owner = sw.apply(fi.owner);
					fi.desc = sw.apply(fi.desc);
				}
			}
		}
		if (hooks != 2) throw new IllegalStateException("XpOrbRendererMixin: expected 2 hooks, found " + hooks);
		// The injectors are not used any more: VisualMixinPlugin finds the renderer's methods itself
		// (no method names needed). Only the empty @Mixin(ItemEntityRenderer) stays, so the plugin's
		// preApply runs for that class.
		cn.methods.removeIf(m -> !m.name.equals("<init>"));
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static List<AnnotationNode> remapAnnotations(List<AnnotationNode> list, java.util.function.UnaryOperator<String> sw) {
		if (list == null) return null;
		for (AnnotationNode a : list) a.values = remapValues(a.values, sw);
		return list;
	}

	@SuppressWarnings("unchecked")
	static List<Object> remapValues(List<Object> values, java.util.function.UnaryOperator<String> sw) {
		if (values == null) return null;
		List<Object> out = new ArrayList<>();
		for (Object v : values) out.add(remapValue(v, sw));
		return out;
	}

	@SuppressWarnings("unchecked")
	static Object remapValue(Object v, java.util.function.UnaryOperator<String> sw) {
		if (v instanceof String s) return sw.apply(s);
		if (v instanceof Type t) return t.getSort() == Type.OBJECT ? Type.getObjectType(sw.apply(t.getInternalName())) : t;
		if (v instanceof AnnotationNode a) { a.values = remapValues(a.values, sw); return a; }
		if (v instanceof List<?> l) return remapValues((List<Object>) l, sw);
		return v;
	}

	static final String MUSIC_HUD = "net/visualclient/modules/misc/MusicHudModule";

	/** Modules.<clinit>: "new MusicHudModule()" becomes null (ConfigsModule.withConfigs skips nulls). */
	static byte[] removeMusicHud(byte[] bytes) {
		ClassNode cn = readClass(bytes);
		MethodNode clinit = method(cn, "<clinit>");
		int removed = 0;
		for (AbstractInsnNode n = clinit.instructions.getFirst(); n != null; ) {
			AbstractInsnNode next = n.getNext();
			if (n instanceof TypeInsnNode t && t.getOpcode() == NEW && t.desc.equals(MUSIC_HUD)
				&& next != null && next.getOpcode() == DUP && next.getNext() instanceof MethodInsnNode init
				&& init.owner.equals(MUSIC_HUD) && init.name.equals("<init>") && init.desc.equals("()V")) {
				AbstractInsnNode after = init.getNext();
				clinit.instructions.insertBefore(n, new InsnNode(ACONST_NULL));
				clinit.instructions.remove(init);
				clinit.instructions.remove(next);
				clinit.instructions.remove(n);
				removed++;
				n = after;
				continue;
			}
			n = next;
		}
		for (AbstractInsnNode n : clinit.instructions) {
			if (n instanceof TypeInsnNode t && t.desc.equals(MUSIC_HUD) || n instanceof MethodInsnNode m && m.owner.equals(MUSIC_HUD)) {
				throw new IllegalStateException("Modules still references MusicHudModule");
			}
		}
		System.out.println(removed > 0 ? "  Music HUD removed" : "  Music HUD already removed");
		if (removed == 0) return bytes;
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** VisualClient.onTick: drop the CoverArt.tick() call (part of the removed Music HUD). */
	static byte[] removeCoverArtTick(byte[] bytes) {
		ClassNode cn = readClass(bytes);
		int removed = 0;
		for (MethodNode m : cn.methods) {
			for (AbstractInsnNode n = m.instructions.getFirst(); n != null; ) {
				AbstractInsnNode next = n.getNext();
				if (n instanceof MethodInsnNode mi && mi.owner.startsWith("net/visualclient/media/")) {
					if (mi.getOpcode() != INVOKESTATIC || !mi.desc.equals("()V")) throw new IllegalStateException("unexpected media call " + mi.name + mi.desc);
					m.instructions.remove(n);
					removed++;
				}
				n = next;
			}
		}
		if (removed == 0) return bytes;
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** Registers VisualMixinPlugin in visualclient.mixins.json (once). */
	static byte[] addPlugin(byte[] json) {
		String s = new String(json, StandardCharsets.UTF_8);
		if (s.contains("\"plugin\"")) {
			if (!s.contains("VisualMixinPlugin")) throw new IllegalStateException("mixins json already has another plugin");
			return json;
		}
		int pkg = s.indexOf("\"package\"");
		if (pkg < 0) throw new IllegalStateException("mixins json: no package");
		s = s.substring(0, pkg) + "\"plugin\": \"net.visualclient.mixin.VisualMixinPlugin\",\n  " + s.substring(pkg);
		return s.getBytes(StandardCharsets.UTF_8);
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
	static final String NO_EQUIP_MIXIN = "net/visualclient/mixin/NoEquipAnimationMixin";

	/** Empty mixin on ItemInHandRenderer: VisualMixinPlugin finds tick() by its shape and patches it. */
	static byte[] noEquipMixin(byte[] shieldMixin) {
		// the hand renderer of this build, as the mod's own ShieldMixin names it; in 26.3+ the hand
		// items and heights moved from the renderer into net.minecraft.client.player.FirstPersonHandsAndItems
		ClassNode shield = new ClassNode();
		new ClassReader(shieldMixin).accept(shield, ClassReader.SKIP_CODE);
		String target = null;
		for (AnnotationNode an : shield.invisibleAnnotations)
			if (an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
				for (int i = 0; i < an.values.size(); i += 2)
					if (an.values.get(i).equals("value")) target = ((Type) ((List<?>) an.values.get(i + 1)).get(0)).getInternalName();
		if (target == null) throw new IllegalStateException("ShieldMixin target not found");
		if (target.endsWith("/FirstPersonHandsAndItemsRenderer")) target = "net/minecraft/client/player/FirstPersonHandsAndItems";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, NO_EQUIP_MIXIN, null, "java/lang/Object", null);
		cw.visitSource("NoEquipAnimationMixin.java", null);
		var mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		var targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		mixin.visitEnd();
		var ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(ALOAD, 0);
		ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

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
		
		Map<String, byte[]> entries = readJar(jar);
		add.put(NO_EQUIP_MIXIN + ".class", noEquipMixin(entries.get("net/visualclient/mixin/ShieldMixin.class")));
		add.put(ITEM_SIZE_MIXIN + ".class", itemSizeMixin(entries.get(XP_ORB_MIXIN + ".class"), official));
		add.put(NEW_SCREEN + ".class", newGuiScreen(entries.get(GUI_SCREEN + ".class"), entries.get(COMPAT + ".class"),
			entries.get(GUI_OPENER + ".class"), findItemDraw(entries)));
		byte[] result = rewriteJar(jar, (name, data) -> {
			if (name.equals(SCREEN + ".class")) {
				found[0] = true;
				return patchScreen(data);
			}
			if (name.equals(MODULES + ".class")) {
				return removeMusicHud(patchModules(data));
			}
			if (name.startsWith(MUSIC_HUD) || name.startsWith("net/visualclient/media/")) {
				return null; // Music HUD removed completely (it sent song data to lrclib.net)
			}
			if (name.equals("net/visualclient/VisualClient.class")) {
				return removeCoverArtTick(data);
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
			if (name.equals(GUI_OPENER + ".class")) {
				return patchGuiOpener(data);
			}
			if (name.equals(GUI_STYLE + ".class")) {
				return patchGuiStyle(data);
			}
			if (name.equals("fabric.mod.json")) {
				return setVersion(data, version);
			}
			if (name.equals("visualclient.mixins.json")) {
				return addPlugin(addMixin(addMixin(addMixin(data, "NoPopAnimationMixin"), "ItemSizeMixin"), "NoEquipAnimationMixin"));
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
				if (data == null) continue; // entry removed
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
