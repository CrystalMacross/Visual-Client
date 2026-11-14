package net.visualclient.gui;

import java.util.ArrayList;
import java.util.List;

/** Click targets collected while a screen draws. The next mouse event looks them up, topmost first. */
final class Hits {
	interface Action {
		void run(double mouseX, double mouseY);
	}

	static final class Hit {
		final int x;
		final int y;
		final int w;
		final int h;
		int clipTop = Integer.MIN_VALUE;
		int clipBottom = Integer.MAX_VALUE;
		Action left;
		Action right;
		Action drag;

		Hit(int x, int y, int w, int h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		Hit clip(int top, int bottom) {
			this.clipTop = top;
			this.clipBottom = bottom;
			return this;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= Math.max(y, clipTop) && my < Math.min(y + h, clipBottom);
		}
	}

	private final List<Hit> list = new ArrayList<>();

	void clear() {
		list.clear();
	}

	Hit add(int x, int y, int w, int h) {
		Hit hit = new Hit(x, y, w, h);
		list.add(hit);
		return hit;
	}

	Hit find(double mx, double my) {
		for (int i = list.size() - 1; i >= 0; i--) {
			Hit hit = list.get(i);
			if (hit.contains(mx, my)) {
				return hit;
			}
		}
		return null;
	}

	static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
