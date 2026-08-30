package whalepihost.model;

import java.io.IOException;

import org.json.JSONObject;

/**
 * Reads {@code whalepidog_settings.json} into a {@link WhalePiSettings}.
 *
 * <p>Key lookup is deliberately case-insensitive and accepts a few historic
 * aliases, so settings files written by different WhalePiDog versions all load.
 */
public final class SettingsReader {

    /** The file name written by WhalePiDog on the Pi. */
    public static final String SETTINGS_FILE_NAME = "whalepidog_settings.json";

    private SettingsReader() { }

    /**
     * @param json  the file's contents
     * @param where the file's location, used only in error messages
     */
    public static WhalePiSettings parse(String json, String where) throws IOException {
        JSONObject jo;
        try {
            jo = new JSONObject(json);
        } catch (RuntimeException e) {
            throw new IOException("Not a valid WhalePi settings file: " + where, e);
        }

        WhalePiSettings s = new WhalePiSettings();
        s.setSettingsPath(where);

        s.setRecordingFolder(string(jo, "recordingFolder", "wavFolder"));
        s.setDatabase(string(jo, "database", "databaseFile"));
        s.setPsfxFile(string(jo, "psfxFile", "psfFile"));
        s.setPamBinaryFolder(string(jo, "pambinaryfolder", "pamBinaryFolder", "binaryFolder"));
        s.setRecordingPrefix(string(jo, "recordingPrefix"));
        s.setRecordingType(string(jo, "recordingType"));

        JSONObject bt = object(jo, "bluetoothSettings");
        if (bt != null) {
            s.setIdentification(string(bt, "identification", "serialNumber", "serial"));
        }
        if (s.getIdentification().isBlank()) {
            // Some settings files carry the id at the top level instead.
            s.setIdentification(string(jo, "identification", "serialNumber", "serial"));
        }
        return s;
    }

    /** First non-blank string among {@code names}, matched ignoring case. Never null. */
    private static String string(JSONObject jo, String... names) {
        for (String name : names) {
            String key = findKey(jo, name);
            if (key == null || jo.isNull(key)) continue;
            Object v = jo.get(key);
            if (v instanceof String str && !str.isBlank()) return str.trim();
        }
        return "";
    }

    private static JSONObject object(JSONObject jo, String name) {
        String key = findKey(jo, name);
        if (key == null || jo.isNull(key)) return null;
        Object v = jo.get(key);
        return (v instanceof JSONObject obj) ? obj : null;
    }

    private static String findKey(JSONObject jo, String name) {
        if (jo.has(name)) return name;
        for (String key : jo.keySet()) {
            if (key.equalsIgnoreCase(name)) return key;
        }
        return null;
    }
}
