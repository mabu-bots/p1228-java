package p1228;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== 1. Json (軽量JSONパーサ/生成・外部依存なし) =====
// aegis-java の Json.java を土台に、任意の Map/List 構造を書き出す
// stringify と数値ヘルパーを追加したもの。値は Map/List/String/Long/
// Double/Boolean/null で表現する。パーサは寛容な実装で、入力の厳密な
// 検証は行わず、読めない数値は 0 として扱うなど、可能な範囲で値を返す。
public final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    // ----- 1.1 Parse -----
    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        return v;
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private Object readValue() {
        if (pos >= s.length()) return null;
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> { pos += 4; yield null; }
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ':') pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
            if (pos < s.length() && s.charAt(pos) == '}') pos++;
            break;
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
            if (pos < s.length() && s.charAt(pos) == ']') pos++;
            break;
        }
        return list;
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // "
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < s.length()) {
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (pos + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean readBoolean() {
        if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        pos += 5; // false
        return Boolean.FALSE;
    }

    private Number readNumber() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        String num = s.substring(start, pos);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        } catch (Exception ex) {
            try { return Double.parseDouble(num); } catch (Exception ex2) { return 0; }
        }
    }

    // ----- 1.2 Access helpers -----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static String str(Object o, String def) {
        String v = str(o);
        return v.isEmpty() ? def : v;
    }

    public static long lng(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o).trim()); } catch (Exception e) { return def; }
    }

    public static int integer(Object o, int def) {
        return (int) lng(o, def);
    }

    public static double dbl(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(str(o)); } catch (Exception e) { return def; }
    }

    public static boolean bool(Object o) {
        return o instanceof Boolean b ? b : Boolean.parseBoolean(str(o));
    }

    public static boolean has(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key) && map.get(key) != null;
    }

    // ----- 1.3 Stringify helpers -----
    public static String escape(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String quote(String v) {
        return "\"" + escape(v) + "\"";
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String str) {
            sb.append(quote(str));
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
                sb.append("0");
            } else if (n instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
                // VOICEVOX等の数値は 1.0 のような表記を保つ方が安全なため小数表記にする
                sb.append(d);
            } else {
                sb.append(n);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }
}
