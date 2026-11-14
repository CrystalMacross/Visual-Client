package net.visualclient.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer.
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Double}.
 */
public final class MiniJson {
	private MiniJson() {
	}

	public static Object parse(String text) {
		Parser p = new Parser(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.pos != text.length()) {
			throw p.error("Unexpected trailing characters");
		}
		return v;
	}

	public static String stringify(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, -1);
		return sb.toString();
	}

	public static String pretty(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, 0);
		return sb.toString();
	}

	// ---- typed access helpers -------------------------------------------------

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object o) {
		return o instanceof List ? (List<Object>) o : null;
	}

	public static String str(Object o, String def) {
		return o instanceof String s ? s : def;
	}

	public static double num(Object o, double def) {
		return o instanceof Number n ? n.doubleValue() : def;
	}

	public static boolean bool(Object o, boolean def) {
		return o instanceof Boolean b ? b : def;
	}

	// ---- writer ---------------------------------------------------------------

	private static void write(StringBuilder sb, Object v, int indent) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String s) {
			quote(sb, s);
		} else if (v instanceof Boolean b) {
			sb.append(b.booleanValue());
		} else if (v instanceof Number n) {
			number(sb, n);
		} else if (v instanceof Map<?, ?> m) {
			if (m.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				newline(sb, indent + 1);
				quote(sb, String.valueOf(e.getKey()));
				sb.append(indent >= 0 ? ": " : ":");
				write(sb, e.getValue(), indent >= 0 ? indent + 1 : -1);
			}
			newline(sb, indent);
			sb.append('}');
		} else if (v instanceof Iterable<?> it) {
			boolean any = false;
			for (Object ignored : it) {
				any = true;
				break;
			}
			if (!any) {
				sb.append("[]");
				return;
			}
			sb.append('[');
			boolean first = true;
			for (Object o : it) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				newline(sb, indent + 1);
				write(sb, o, indent >= 0 ? indent + 1 : -1);
			}
			newline(sb, indent);
			sb.append(']');
		} else {
			quote(sb, String.valueOf(v));
		}
	}

	private static void newline(StringBuilder sb, int indent) {
		if (indent < 0) {
			return;
		}
		sb.append('\n');
		for (int i = 0; i < indent; i++) {
			sb.append("  ");
		}
	}

	private static void number(StringBuilder sb, Number n) {
		double d = n.doubleValue();
		if (Double.isNaN(d) || Double.isInfinite(d)) {
			sb.append('0');
		} else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			sb.append((long) d);
		} else {
			sb.append(d);
		}
	}

	private static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// ---- parser ---------------------------------------------------------------

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		RuntimeException error(String msg) {
			return new IllegalArgumentException(msg + " at position " + pos);
		}

		void ws() {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
					pos++;
				} else {
					break;
				}
			}
		}

		Object value() {
			if (pos >= s.length()) {
				throw error("Unexpected end");
			}
			char c = s.charAt(pos);
			switch (c) {
				case '{':
					return object();
				case '[':
					return array();
				case '"':
					return string();
				case 't':
					expect("true");
					return Boolean.TRUE;
				case 'f':
					expect("false");
					return Boolean.FALSE;
				case 'n':
					expect("null");
					return null;
				default:
					return number();
			}
		}

		void expect(String word) {
			if (!s.startsWith(word, pos)) {
				throw error("Expected " + word);
			}
			pos += word.length();
		}

		Map<String, Object> object() {
			Map<String, Object> map = new LinkedHashMap<>();
			pos++; // {
			ws();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				ws();
				if (peek() != '"') {
					throw error("Expected string key");
				}
				String key = string();
				ws();
				if (peek() != ':') {
					throw error("Expected ':'");
				}
				pos++;
				ws();
				map.put(key, value());
				ws();
				char c = peek();
				pos++;
				if (c == ',') {
					continue;
				}
				if (c == '}') {
					return map;
				}
				throw error("Expected ',' or '}'");
			}
		}

		List<Object> array() {
			List<Object> list = new ArrayList<>();
			pos++; // [
			ws();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				ws();
				list.add(value());
				ws();
				char c = peek();
				pos++;
				if (c == ',') {
					continue;
				}
				if (c == ']') {
					return list;
				}
				throw error("Expected ',' or ']'");
			}
		}

		char peek() {
			if (pos >= s.length()) {
				throw error("Unexpected end");
			}
			return s.charAt(pos);
		}

		String string() {
			StringBuilder sb = new StringBuilder();
			pos++; // opening quote
			while (true) {
				if (pos >= s.length()) {
					throw error("Unterminated string");
				}
				char c = s.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				if (pos >= s.length()) {
					throw error("Bad escape");
				}
				char e = s.charAt(pos++);
				switch (e) {
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					case '/' -> sb.append('/');
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (pos + 4 > s.length()) {
							throw error("Bad unicode escape");
						}
						sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
						pos += 4;
					}
					default -> throw error("Bad escape");
				}
			}
		}

		Double number() {
			int start = pos;
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
					pos++;
				} else {
					break;
				}
			}
			if (start == pos) {
				throw error("Unexpected character '" + s.charAt(pos) + "'");
			}
			try {
				return Double.parseDouble(s.substring(start, pos));
			} catch (NumberFormatException ex) {
				throw error("Bad number");
			}
		}
	}
}
