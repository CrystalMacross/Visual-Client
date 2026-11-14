package net.visualclient.pack;

/** Groups of resources a pack can contribute. Used by the Pack Mixer filters. */
public enum PackCategory {
	BLOCKS("Blocks"),
	ITEMS("Items"),
	ARMOR("Armor"),
	MOBS("Mobs"),
	INTERFACE("Interface"),
	SKY("Sky and weather"),
	PARTICLES("Particles"),
	SOUNDS("Sounds"),
	TEXT("Text and language"),
	SHADERS("Shaders"),
	OTHER("Other");

	/** Bit mask with every category set. */
	public static final int ALL = (1 << values().length) - 1;

	private final String label;

	PackCategory(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public int bit() {
		return 1 << ordinal();
	}

	/** Classifies a resource path that is relative to its namespace, e.g. "textures/block/stone.png". */
	public static PackCategory of(String path) {
		if (path.startsWith("textures/entity/equipment/") || path.startsWith("equipment/")
				|| path.startsWith("textures/models/armor/") || path.startsWith("textures/trims/")
				|| path.startsWith("trims/")) {
			return ARMOR;
		}
		if (path.startsWith("textures/block/") || path.startsWith("textures/blocks/") || path.startsWith("models/block/")
				|| path.startsWith("blockstates/") || path.startsWith("textures/colormap/")) {
			return BLOCKS;
		}
		if (path.startsWith("textures/item/") || path.startsWith("textures/items/") || path.startsWith("models/item/")
				|| path.startsWith("items/")) {
			return ITEMS;
		}
		if (path.startsWith("textures/entity/") || path.startsWith("models/entity/") || path.startsWith("textures/painting/")
				|| path.startsWith("textures/entity_model/")) {
			return MOBS;
		}
		if (path.startsWith("textures/gui/") || path.startsWith("textures/mob_effect/") || path.startsWith("textures/map/")
				|| path.startsWith("textures/misc/")) {
			return INTERFACE;
		}
		if (path.startsWith("textures/environment/") || path.startsWith("environment/")) {
			return SKY;
		}
		if (path.startsWith("textures/particle/") || path.startsWith("particles/")) {
			return PARTICLES;
		}
		if (path.startsWith("sounds/") || path.equals("sounds.json")) {
			return SOUNDS;
		}
		if (path.startsWith("font/") || path.startsWith("textures/font/") || path.startsWith("lang/") || path.startsWith("texts/")) {
			return TEXT;
		}
		if (path.startsWith("shaders/") || path.startsWith("post_effect/")) {
			return SHADERS;
		}
		return OTHER;
	}
}
