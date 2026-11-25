package net.visualclient.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin config plugin of Visual Client (added by the patcher).
 *
 * Item Size: instead of naming ItemEntityRenderer's methods (names differ per Minecraft build),
 * the renderer class is searched when it is loaded:
 *   - render/submit(state, PoseStack, ...): non-static, not synthetic, PoseStack as 2nd parameter
 *     -> ItemSizeModule.scale(state, poseStack) at the start
 *   - extractRenderState(itemEntity, state, float): non-static, not synthetic, (Object, state, F)
 *     -> ItemSizeModule.captureState(itemEntity, state) at the start
 * The module classes are only referenced from the inserted bytecode, never loaded here.
 */
public final class VisualMixinPlugin implements IMixinConfigPlugin {

	private static final String ITEM_SIZE = "net/visualclient/modules/extra/ItemSizeModule";
	private static final String[] POSE_STACK = {"com/mojang/blaze3d/vertex/PoseStack", "net/minecraft/class_4587"};
	private static final String NO_EQUIP = "net/visualclient/modules/extra/NoEquipAnimationModule";
	private static final String[] RENDERER = {"net.minecraft.client.renderer.entity.ItemEntityRenderer", "net.minecraft.class_916"};

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (mixinClassName.endsWith(".NoEquipAnimationMixin")) {
			// the mixin targets only the class holding the hand items (ItemInHandRenderer, or
			// FirstPersonHandsAndItems in 26.3+), chosen per build by the patcher
			try {
				patchHandRenderer(targetClass);
			} catch (Throwable t) {
				System.setProperty("visualclient.noequip.hooks", "error: " + t);
			}
			return;
		}
		if (!mixinClassName.endsWith(".ItemSizeMixin")) return;
		boolean renderer = false;
		for (String r : RENDERER) renderer |= r.equals(targetClassName);
		if (!renderer) return;
		try {
			patchItemRenderer(targetClass);
		} catch (Throwable t) {
			System.setProperty("visualclient.itemsize.hooks", "error: " + t);
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	private static boolean isPoseStack(Type t) {
		if (t.getSort() != Type.OBJECT) return false;
		for (String p : POSE_STACK) if (p.equals(t.getInternalName())) return true;
		return false;
	}

	static void patchItemRenderer(ClassNode cn) {
		String stateType = null;
		StringBuilder log = new StringBuilder();
		for (MethodNode m : cn.methods) {
			if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT)) != 0) continue;
			if (!Type.getReturnType(m.desc).equals(Type.VOID_TYPE)) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			if (args.length >= 2 && args[0].getSort() == Type.OBJECT && isPoseStack(args[1])) {
				InsnList head = new InsnList();
				head.add(new VarInsnNode(Opcodes.ALOAD, 1));
				head.add(new VarInsnNode(Opcodes.ALOAD, 2));
				head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ITEM_SIZE, "scale", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
				m.instructions.insert(head);
				stateType = args[0].getInternalName();
				log.append("scale:").append(m.name).append(' ');
			}
		}
		if (stateType != null) {
			for (MethodNode m : cn.methods) {
				if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT)) != 0) continue;
				Type[] args = Type.getArgumentTypes(m.desc);
				if (args.length == 3 && args[0].getSort() == Type.OBJECT && args[1].getSort() == Type.OBJECT
					&& args[1].getInternalName().equals(stateType) && args[2].equals(Type.FLOAT_TYPE)
					&& Type.getReturnType(m.desc).equals(Type.VOID_TYPE)) {
					InsnList head = new InsnList();
					head.add(new VarInsnNode(Opcodes.ALOAD, 1));
					head.add(new VarInsnNode(Opcodes.ALOAD, 2));
					head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ITEM_SIZE, "captureState", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
					m.instructions.insert(head);
					log.append("capture:").append(m.name).append(' ');
				}
			}
		}
		System.setProperty("visualclient.itemsize.hooks", log.length() == 0 ? "none found" : log.toString().trim());
		System.out.println("[Visual Client] Item Size hooks in " + cn.name + ": " + System.getProperty("visualclient.itemsize.hooks"));
	}

	/**
	 * No Equipment Animation: ItemInHandRenderer keeps the shown hand items and their "height"
	 * (mainHandHeight, oMainHandHeight, offHandHeight, oOffHandHeight: its only instance float fields).
	 * tick() (no arguments, or the player in 26.3+) is the method that copies the player's held stacks into the item fields
	 * (aload 0, aload n, putfield). Before each return of every method that writes a height field,
	 * the heights go through NoEquipAnimationModule.height (1.0 while active) and, in tick(), the
	 * shown items through NoEquipAnimationModule.stack (the held stack while active), so a new
	 * item is shown at once at full height. Branch-free code: no stack map frames are needed.
	 */
	static void patchHandRenderer(ClassNode cn) {
		java.util.List<FieldNode> heights = new java.util.ArrayList<>();
		for (FieldNode f : cn.fields) if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("F")) heights.add(f);
		StringBuilder log = new StringBuilder();
		if (heights.isEmpty() || heights.size() > 6) {
			System.setProperty("visualclient.noequip.hooks", "no height fields (" + heights.size() + ")");
			return;
		}
		java.util.Set<String> heightNames = new java.util.HashSet<>();
		for (FieldNode f : heights) heightNames.add(f.name);
		for (MethodNode m : cn.methods) {
			if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0 || m.name.startsWith("<")) continue;
			boolean writesHeight = false;
			for (AbstractInsnNode in : m.instructions)
				if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode fi && fi.owner.equals(cn.name) && heightNames.contains(fi.name)) writesHeight = true;
			if (!writesHeight) continue;
			// item fields set from a local in a no-argument method: tick()
			java.util.Map<String, Integer> stackVar = new java.util.LinkedHashMap<>();
			java.util.Map<String, String> stackDesc = new java.util.HashMap<>();
			if (m.desc.endsWith(")V") && Type.getArgumentTypes(m.desc).length <= 1) {
				// locals stored exactly once, before the first jump: set on every path to a return
				java.util.Map<Integer, Integer> stores = new java.util.HashMap<>();
				java.util.Set<Integer> early = new java.util.HashSet<>();
				boolean jumped = false;
				for (AbstractInsnNode in : m.instructions) {
					if (in instanceof org.objectweb.asm.tree.JumpInsnNode || in instanceof org.objectweb.asm.tree.TableSwitchInsnNode
						|| in instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) jumped = true;
					if (in.getOpcode() == Opcodes.ASTORE) {
						stores.merge(((VarInsnNode) in).var, 1, Integer::sum);
						if (!jumped) early.add(((VarInsnNode) in).var);
					}
				}
				if (m.tryCatchBlocks != null && !m.tryCatchBlocks.isEmpty()) early.clear();
				for (AbstractInsnNode in : m.instructions) {
					if (in.getOpcode() != Opcodes.PUTFIELD || !(in instanceof FieldInsnNode fi) || !fi.owner.equals(cn.name) || !fi.desc.startsWith("L")) continue;
					AbstractInsnNode v = prev(in), self = v == null ? null : prev(v);
					if (v == null || self == null || v.getOpcode() != Opcodes.ALOAD || self.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) self).var != 0) continue;
					int var = ((VarInsnNode) v).var;
					if (var == 0 || stores.getOrDefault(var, 0) != 1 || !early.contains(var)) continue;
					Integer old = stackVar.putIfAbsent(fi.name, var);
					if (old != null && old != var) stackVar.put(fi.name, -1);
					stackDesc.put(fi.name, fi.desc);
				}
				stackVar.values().removeIf(v -> v < 0);
			}
			int hooks = 0;
			for (AbstractInsnNode in : m.instructions.toArray()) {
				if (in.getOpcode() != Opcodes.RETURN) continue;
				InsnList fix = new InsnList();
				for (var e : stackVar.entrySet()) {
					String desc = stackDesc.get(e.getKey());
					fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
					fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
					fix.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, e.getKey(), desc));
					fix.add(new VarInsnNode(Opcodes.ALOAD, e.getValue()));
					fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NO_EQUIP, "stack", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
					fix.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(desc).getInternalName()));
					fix.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, e.getKey(), desc));
				}
				for (FieldNode f : heights) {
					fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
					fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
					fix.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, f.name, "F"));
					fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NO_EQUIP, "height", "(F)F", false));
					fix.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, f.name, "F"));
				}
				m.instructions.insertBefore(in, fix);
				hooks++;
			}
			if (hooks > 0) {
				m.maxStack = Math.max(m.maxStack, 4);
				log.append(m.name).append(m.desc).append(stackVar.isEmpty() ? "" : "+items" + stackVar.size()).append(' ');
			}
		}
		System.setProperty("visualclient.noequip.hooks", log.length() == 0 ? "none found" : log.toString().trim());
		System.out.println("[Visual Client] No Equipment Animation hooks in " + cn.name + ": " + System.getProperty("visualclient.noequip.hooks"));
	}

	private static AbstractInsnNode prev(AbstractInsnNode in) {
		AbstractInsnNode p = in.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}
}
