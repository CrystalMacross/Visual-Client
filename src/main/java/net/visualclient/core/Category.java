package net.visualclient.core;

/** Module categories, in the order they appear in the menu. */
public enum Category {
	COMBAT("Combat"),
	VISUALS("Visuals"),
	PLAYER("Player"),
	MISC("Misc");

	private final String label;

	Category(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
