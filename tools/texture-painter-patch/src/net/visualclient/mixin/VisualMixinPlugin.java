package net.visualclient.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
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
}
