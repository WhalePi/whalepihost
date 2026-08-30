package whalepihost.model;

import java.nio.file.Path;

/**
 * The handful of values WhalePi Host needs out of {@code whalepidog_settings.json}.
 *
 * <p>The paths stored in that file are the paths as seen <em>on the Pi</em>
 * (for example {@code /home/whalepi/PAMRecordings}). They are kept verbatim here;
 * {@link CardScanner} is responsible for translating them onto the mounted card.
 */
public class WhalePiSettings {

    private Path settingsFile;

    private String identification = "";
    private String recordingFolder = "";
    private String database = "";
    private String psfxFile = "";
    private String pamBinaryFolder = "";
    private String recordingPrefix = "";
    private String recordingType = "";

    /** Location of the settings file itself on the mounted card. */
    public Path getSettingsFile() { return settingsFile; }
    public void setSettingsFile(Path settingsFile) { this.settingsFile = settingsFile; }

    /** Bluetooth {@code identification} string – the unit's serial number, e.g. "1A". */
    public String getIdentification() { return identification; }
    public void setIdentification(String identification) { this.identification = identification; }

    public String getRecordingFolder() { return recordingFolder; }
    public void setRecordingFolder(String recordingFolder) { this.recordingFolder = recordingFolder; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getPsfxFile() { return psfxFile; }
    public void setPsfxFile(String psfxFile) { this.psfxFile = psfxFile; }

    /** May be blank – the PAMBinary folder is optional. */
    public String getPamBinaryFolder() { return pamBinaryFolder; }
    public void setPamBinaryFolder(String pamBinaryFolder) { this.pamBinaryFolder = pamBinaryFolder; }

    public String getRecordingPrefix() { return recordingPrefix; }
    public void setRecordingPrefix(String recordingPrefix) { this.recordingPrefix = recordingPrefix; }

    public String getRecordingType() { return recordingType; }
    public void setRecordingType(String recordingType) { this.recordingType = recordingType; }

    /** Serial number for display, falling back to a placeholder when the file had none. */
    public String getDisplaySerial() {
        return (identification == null || identification.isBlank()) ? "UNKNOWN" : identification;
    }
}
