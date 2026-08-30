package whalepihost.settings;

import java.util.List;

/**
 * One editable parameter in {@code whalepidog_settings.json}.
 *
 * <p>The Settings form is built from this list rather than hand-wired, so adding a
 * parameter to WhalePiDog means adding one line to {@link SettingsSchema}.
 *
 * @param key     JSON key; a dotted key such as {@code bluetoothSettings.identification}
 *                addresses a nested object
 * @param label   what the form calls it
 * @param group   the section it belongs to
 * @param type    which control to use
 * @param help    one line explaining what it does, shown under the control
 * @param choices the permitted values, for {@link Type#CHOICE}
 * @param min     lowest accepted value, for {@link Type#INTEGER}
 * @param max     highest accepted value, for {@link Type#INTEGER}
 * @param maxLength longest accepted text, or 0 for no limit
 */
public record SettingsField(String key, String label, String group, Type type, String help,
                            List<String> choices, int min, int max, int maxLength) {

    public enum Type { TEXT, PATH, INTEGER, BOOLEAN, CHOICE }

    public static SettingsField text(String key, String label, String group, String help) {
        return new SettingsField(key, label, group, Type.TEXT, help, List.of(), 0, 0, 0);
    }

    public static SettingsField text(String key, String label, String group, String help,
                                     int maxLength) {
        return new SettingsField(key, label, group, Type.TEXT, help, List.of(), 0, 0, maxLength);
    }

    public static SettingsField path(String key, String label, String group, String help) {
        return new SettingsField(key, label, group, Type.PATH, help, List.of(), 0, 0, 0);
    }

    public static SettingsField integer(String key, String label, String group, String help,
                                        int min, int max) {
        return new SettingsField(key, label, group, Type.INTEGER, help, List.of(), min, max, 0);
    }

    public static SettingsField bool(String key, String label, String group, String help) {
        return new SettingsField(key, label, group, Type.BOOLEAN, help, List.of(), 0, 0, 0);
    }

    public static SettingsField choice(String key, String label, String group, String help,
                                       List<String> choices) {
        return new SettingsField(key, label, group, Type.CHOICE, help, choices, 0, 0, 0);
    }

    /** The last segment of the key, used when reporting problems. */
    public String shortKey() {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }
}
