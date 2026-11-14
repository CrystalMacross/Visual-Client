package net.visualclient.gui;

import java.util.function.IntPredicate;

/** The text being typed into a menu field. */
final class TextInput {
	private final StringBuilder text = new StringBuilder();
	private int maxLength = 64;
	private IntPredicate filter = cp -> true;

	void begin(String initial, int maxLength, IntPredicate filter) {
		this.text.setLength(0);
		this.maxLength = maxLength;
		this.filter = filter;
		append(initial == null ? "" : initial);
	}

	String text() {
		return text.toString();
	}

	void append(String s) {
		if (s == null) {
			return;
		}
		s.codePoints().forEach(cp -> {
			if (cp >= 32 && cp != 127 && filter.test(cp) && text.codePointCount(0, text.length()) < maxLength) {
				text.appendCodePoint(cp);
			}
		});
	}

	void backspace() {
		if (text.length() > 0) {
			int last = text.offsetByCodePoints(text.length(), -1);
			text.setLength(last);
		}
	}

	void clear() {
		text.setLength(0);
	}
}
