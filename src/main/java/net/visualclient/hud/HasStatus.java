package net.visualclient.hud;

/** Modules that can tell the player what is going on (e.g. "Could not reach Discord"). Shown in the menu. */
public interface HasStatus {
	String statusLine();
}
