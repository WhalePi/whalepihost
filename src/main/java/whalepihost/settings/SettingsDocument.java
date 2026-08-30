package whalepihost.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;

/**
 * The settings file, held as parsed JSON so it can be edited and written back.
 *
 * <p>Editing works on the JSON that was read, not on a fixed set of known fields, so
 * anything WhalePi Host does not recognise — a key from a newer WhalePiDog, a comment
 * field, whatever — is carried through a save untouched.
 */
public class SettingsDocument {

    private final JSONObject root;
    private final String originalText;

    private SettingsDocument(JSONObject root, String originalText) {
        this.root = root;
        this.originalText = originalText;
    }

    public static SettingsDocument parse(String json) throws IOException {
        try {
            return new SettingsDocument(new JSONObject(json), json);
        } catch (RuntimeException e) {
            throw new IOException("The settings file is not valid JSON", e);
        }
    }

    /** The file exactly as it was read. */
    public String originalText() {
        return originalText;
    }

    // -------------------------------------------------------------- read / write

    /** Current value as text, "" when the key is absent. */
    public String get(String key) {
        Object value = raw(key);
        if (value == null || JSONObject.NULL.equals(value)) return "";
        return String.valueOf(value);
    }

    public boolean getBoolean(String key) {
        Object value = raw(key);
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public int getInt(String key, int fallback) {
        Object value = raw(key);
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** True if the file actually contains this key. */
    public boolean has(String key) {
        return raw(key) != null;
    }

    public void set(String key, Object value) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            root.put(key, value);
            return;
        }
        String parent = key.substring(0, dot);
        String child = key.substring(dot + 1);
        JSONObject nested = root.optJSONObject(parent);
        if (nested == null) {
            nested = new JSONObject();
            root.put(parent, nested);
        }
        nested.put(child, value);
    }

    private Object raw(String key) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            return root.has(key) ? root.get(key) : null;
        }
        JSONObject nested = root.optJSONObject(key.substring(0, dot));
        if (nested == null) return null;
        String child = key.substring(dot + 1);
        return nested.has(child) ? nested.get(child) : null;
    }

    // ------------------------------------------------------------- serialisation

    /**
     * Renders the file to a length the card can accept.
     *
     * <p>A file on an unmounted card lives in whole blocks, so it can be rewritten at
     * any length up to {@code maximum} — the space its blocks already hold. Staying at
     * {@code preferred}, the length it has now, is better still, because then the
     * inode does not have to be touched at all; the JSON is padded with trailing
     * whitespace, which every parser ignores, to reach it.
     *
     * <p>Indented output is tried first and compact output after, so long paths shrink
     * the formatting before they exhaust the space.
     *
     * @param preferred the file's current length, kept when the settings still fit
     * @param maximum   the most the file can be grown to without allocating a block
     * @throws IOException if the settings will not fit even written compactly
     */
    public byte[] toBytes(int preferred, int maximum) throws IOException {
        for (int indent : new int[] { 4, 2, 0 }) {
            byte[] rendered = render(indent);
            if (rendered.length <= preferred) {
                byte[] padded = Arrays.copyOf(rendered, preferred);
                Arrays.fill(padded, rendered.length, preferred, (byte) ' ');
                return padded;
            }
            if (rendered.length <= maximum) {
                return rendered; // the file grows to exactly this
            }
        }
        int shortest = render(0).length;
        throw new IOException("These settings need " + shortest + " bytes but the file on the "
                + "card has only " + maximum + " bytes of space, and a card that is not mounted "
                + "cannot have more space allocated to a file.\n\nShorten one of the longer "
                + "paths and try again.");
    }

    /** Bytes this would take written as compactly as possible. */
    public int smallestLength() {
        return render(0).length;
    }

    /** The current settings as JSON text, for copying the document. */
    public String toJsonText() {
        return root.toString();
    }

    /** Renders the file with no length constraint, for a normal filesystem write. */
    public byte[] toBytes() {
        return render(4);
    }

    private byte[] render(int indent) {
        String text = indent > 0 ? root.toString(indent) : root.toString();
        return (text + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ checking

    /** Describes anything wrong with the current values; empty means good to save. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (SettingsField field : SettingsSchema.fields()) {
            if (!has(field.key())) continue;

            switch (field.type()) {
                case INTEGER -> {
                    Object value = raw(field.key());
                    if (!(value instanceof Number number)) {
                        problems.add(field.label() + " must be a whole number.");
                    } else if (number.intValue() < field.min() || number.intValue() > field.max()) {
                        problems.add(field.label() + " must be between " + field.min()
                                + " and " + field.max() + ".");
                    }
                }
                case TEXT -> {
                    if (field.maxLength() > 0 && get(field.key()).length() > field.maxLength()) {
                        problems.add(field.label() + " can be at most " + field.maxLength()
                                + " characters.");
                    }
                }
                case CHOICE -> {
                    if (!field.choices().contains(get(field.key()))) {
                        problems.add(field.label() + " must be one of "
                                + String.join(", ", field.choices()) + ".");
                    }
                }
                default -> { /* free text and paths accept anything */ }
            }
        }
        return problems;
    }
}
