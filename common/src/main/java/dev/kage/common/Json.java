package dev.kage.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny dependency free JSON reader/writer (objects, arrays, strings, numbers, booleans, null). */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) { this.src = src; }

    public static Object parse(String s) {
        if (s == null) return null;
        Json p = new Json(s);
        p.ws();
        Object v = p.value();
        return v;
    }

    /** Convenience: parse an object, never returns null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        return new ArrayList<Object>();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String v = str(m, key);
        return v == null ? def : v;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception ignored) { }
        }
        return def;
    }

    public static long lng(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (Exception ignored) { }
        }
        return def;
    }

    // --------------------------------------------------------------- writing

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, o);
        return sb.toString();
    }

    public static Map<String, Object> obj() { return new LinkedHashMap<String, Object>(); }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object i : items) l.add(i);
        return l;
    }

    private static void writeValue(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { writeString(sb, (String) o); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(String.valueOf(o)); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object i : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, i);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(o));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // --------------------------------------------------------------- parsing

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private Object value() {
        ws();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (src.startsWith(word, pos)) pos += word.length();
        else pos = src.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++; // {
        ws();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return m; }
        while (pos < src.length()) {
            ws();
            String key = string();
            ws();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            Object v = value();
            m.put(key, v);
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
            break;
        }
        return m;
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        pos++; // [
        ws();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return l; }
        while (pos < src.length()) {
            l.add(value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
            break;
        }
        return l;
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        if (pos < src.length() && src.charAt(pos) == '"') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
            else break;
        }
        String num = src.substring(start, pos);
        try {
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                return Long.parseLong(num);
            }
            return Double.parseDouble(num);
        } catch (Exception e) {
            return num;
        }
    }
}
