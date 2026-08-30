package whalepihost.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameters WhalePi Host offers to edit, in the order the form shows them.
 *
 * <p>This mirrors {@code whalepidog.settings.WhalePIDogSettings}. Anything in the file
 * that is not listed here is left exactly as it was found, so an unfamiliar key from a
 * newer WhalePiDog survives a save untouched.
 */
public final class SettingsSchema {

    public static final String UNIT = "Unit";
    public static final String RECORDING = "Recording";
    public static final String PAMGUARD = "PAMGuard";
    public static final String WATCHDOG = "Watchdog";
    public static final String BLUETOOTH = "Bluetooth";

    private static final List<SettingsField> FIELDS = List.of(

            // ── Unit ────────────────────────────────────────────────────────────
            SettingsField.text("bluetoothSettings.identification", "Serial number", UNIT,
                    "Identifies this unit. The phone app sees it as whlPi_<serial>. "
                    + "Four characters at most.", 4),
            SettingsField.text("recordingPrefix", "Recording prefix", UNIT,
                    "Start of every recording's file name, e.g. PAM1A gives "
                    + "PAM1A_20260830_141500.sud."),

            // ── Recording ───────────────────────────────────────────────────────
            SettingsField.choice("recordingType", "File type", RECORDING,
                    "Format PAMGuard writes. sud is compressed; wav is not.",
                    List.of("sud", "wav", "x3", "aiff", "au", "snd")),
            SettingsField.path("recordingFolder", "Recordings folder", RECORDING,
                    "Where recordings are written on the Pi."),
            SettingsField.path("database", "Database file", RECORDING,
                    "The SQLite database PAMGuard writes detections to."),
            SettingsField.text("soundCardName", "Sound card", RECORDING,
                    "Name of the sound card to use. Leave empty to let PAMGuard choose."),

            // ── PAMGuard ────────────────────────────────────────────────────────
            SettingsField.path("pamguardJar", "PAMGuard jar", PAMGUARD,
                    "The PAMGuard program to run."),
            SettingsField.path("psfxFile", "Configuration (.psfx)", PAMGUARD,
                    "The PAMGuard configuration to load."),
            SettingsField.path("libFolder", "Native library folder", PAMGUARD,
                    "Folder of native libraries, e.g. liblinux."),
            SettingsField.text("jre", "Java command", PAMGUARD,
                    "Java executable used to launch PAMGuard. Usually just java."),
            SettingsField.path("workingFolder", "Working folder", PAMGUARD,
                    "Working directory for PAMGuard. Empty means the jar's own folder."),
            SettingsField.integer("mxMemory", "Maximum memory (MB)", PAMGUARD,
                    "Java -Xmx. A Pi Zero 2 W has 512 MB in total, so keep this modest.",
                    16, 65536),
            SettingsField.integer("msMemory", "Starting memory (MB)", PAMGUARD,
                    "Java -Xms.", 8, 65536),
            SettingsField.text("otherVMOptions", "Extra Java options", PAMGUARD,
                    "Added to the Java command line. Leave empty unless you need them."),
            SettingsField.text("otherOptions", "Extra PAMGuard options", PAMGUARD,
                    "Added to PAMGuard's command line."),
            SettingsField.bool("noGui", "Run headless", PAMGUARD,
                    "Launch PAMGuard with -nogui. Leave on for a deployed Pi."),
            SettingsField.bool("deploy", "Start recording automatically", PAMGUARD,
                    "Send PAMGuard the start command once it has launched. This is what "
                    + "makes an unattended deployment record."),

            // ── Watchdog ────────────────────────────────────────────────────────
            SettingsField.integer("checkIntervalSeconds", "Health check every (s)", WATCHDOG,
                    "How often the watchdog pings PAMGuard.", 1, 3600),
            SettingsField.integer("startWaitSeconds", "Wait after launch (s)", WATCHDOG,
                    "Grace period before the first health check, so PAMGuard can start up.",
                    0, 3600),
            SettingsField.integer("summaryIntervalSeconds", "Summary every (s)", WATCHDOG,
                    "How often the status summary refreshes.", 1, 3600),
            SettingsField.integer("maxRestartsBeforeReboot", "Restarts before reboot", WATCHDOG,
                    "Consecutive PAMGuard restarts allowed before the Pi reboots. "
                    + "0 disables rebooting.", 0, 100),
            SettingsField.integer("udpPort", "UDP port", WATCHDOG,
                    "Port the watchdog uses to talk to PAMGuard.", 1, 65535),

            // ── Bluetooth ───────────────────────────────────────────────────────
            SettingsField.bool("bluetoothSettings.bluetoothEnabled", "Bluetooth enabled",
                    BLUETOOTH, "Lets the phone app connect to this unit."),
            SettingsField.choice("bluetoothSettings.bluetoothMode", "Mode", BLUETOOTH,
                    "BLE works with the WhalePi phone app. SERIAL is for older terminal apps.",
                    List.of("BLE", "SERIAL")),
            SettingsField.bool("bluetoothSettings.bluetoothPairing", "Allow pairing", BLUETOOTH,
                    "Make the unit pairable when it starts."),
            SettingsField.bool("bluetoothSettings.verbose", "Verbose logging", BLUETOOTH,
                    "Log Bluetooth activity in detail. Useful when diagnosing a connection."));

    private SettingsSchema() { }

    public static List<SettingsField> fields() {
        return FIELDS;
    }

    /** The groups, in display order, each with its fields. */
    public static Map<String, List<SettingsField>> byGroup() {
        Map<String, List<SettingsField>> groups = new LinkedHashMap<>();
        for (SettingsField field : FIELDS) {
            groups.computeIfAbsent(field.group(), g -> new ArrayList<>()).add(field);
        }
        return groups;
    }
}
