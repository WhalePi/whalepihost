# WhalePi Host

A small desktop program for getting data off a [WhalePi](https://www.pamguard.org/) SD card.

When a WhalePi deployment finishes, the recordings, database and PAMGuard binary files are all
on the Pi's SD card. Rather than picking through Linux folders by hand with a tool like
DiskInternals, put the card in a card reader, point WhalePi Host at it, and copy what you want
to your computer.

![WhalePi Host](docs/screenshot.png)

---

## What it does

1. **Pick the card** – choose a mounted drive from the list, or browse to any folder holding the
   card's contents.
2. **See what's on it** – WhalePi Host finds `whalepidog_settings.json`, reads the unit's serial
   number and the configured paths from it, then reports the file count and total size of:
   - the **sound recordings** folder, broken down by day folder (`yyyymmdd`)
   - the **SQLite database**
   - the **`.psfx` PAMGuard configuration** (plus the settings file itself)
   - the **PAMBinary folder**, broken down by sub-folder, if the deployment produced one
3. **Download it** – tick the items you want (everything is ticked by default), choose a folder,
   and press Download. The files are copied into a new folder named for the unit and the time,
   for example `WhalePi_1A_20260830_142530/`.

The unit's serial number (the `identification` field in the Bluetooth settings) is shown in the
top right of the window and used to name the download folder.

## Getting the card readable

The WhalePi's data partition is `ext4`, which macOS and Windows cannot read on their own:

| Platform | What to do |
|---|---|
| Windows | Mount the card with a Linux filesystem reader such as DiskInternals Linux Reader, then point WhalePi Host at the mounted folder |
| macOS   | Use an ext4 driver (for example extFS for Mac), or read the card on a Linux machine |
| Linux   | The card mounts natively under `/media` or `/mnt` |

WhalePi Host also works on a plain folder — so if you have already copied the card contents onto
a computer or an external drive, you can browse to that instead.

## Where the paths come from

Every path is read from `whalepidog_settings.json` rather than assumed:

| Settings key | Used for |
|---|---|
| `recordingFolder` (or the legacy `wavFolder`) | the recordings folder |
| `database` | the `.sqlite3` database |
| `psfxFile` | the PAMGuard configuration |
| `pambinaryfolder` | the PAMBinary folder (optional) |
| `bluetoothSettings.identification` | the unit's serial number |

Those paths are recorded as they appear *on the Pi* (`/home/whalepi/PAMRecordings` and so on).
WhalePi Host works out where the settings file sits on the card, uses that to establish which
folder stands in for the Pi's root, and re-anchors every path onto the card. If a path still
does not resolve, it falls back to searching the card for a folder or file of that name — so it
copes with cards that have been partially copied or rearranged.

If `pambinaryfolder` is missing from the settings file, WhalePi Host looks for a folder called
`PAMBinary`; if there isn't one, the Binary files row simply reads *none on this card*.

---

## Building

Requirements: **JDK 17 or later** and **Maven 3.6+**.

Run it during development:

```bash
mvn clean javafx:run
```

Build a runnable jar and the staged application folder:

```bash
mvn clean package
```

### Installers

`jpackage` can only build an installer for the platform it runs on, so build each one on that
platform. The profiles activate automatically by OS.

macOS — produces `target/installer/WhalePi Host-1.0.0.dmg`:

```bash
mvn clean verify
```

Windows — produces `target\installer\WhalePi Host-1.0.0.msi`:

```bash
mvn clean verify
```

Both installers bundle their own Java runtime, so users do not need Java installed.

The macOS build is unsigned. On a Mac that has not seen it before, the first launch needs
right-click → Open, or `xattr -dr com.apple.quarantine "/Applications/WhalePi Host.app"`.

## Project layout

```
src/main/java/whalepihost/
  Launcher.java              entry point used by the installers
  WhalePiHostApp.java        JavaFX application
  model/
    SettingsReader.java      parses whalepidog_settings.json
    WhalePiSettings.java     the values read from it
    CardScanner.java         finds the deployment and re-anchors the Pi paths
    CardContents.java        a scanned card
    DataNode.java            one row of the contents tree
    FolderStats.java         recursive file count and size
    Volume.java              mounted drives to offer as sources
  copy/
    ScanJob.java             background scan
    CopyJob.java             background copy, with byte-level progress
  ui/
    MainView.java            the window
    Icons.java, Palette.java the phone app's icons and colours
src/main/resources/whalepihost/
  whalepihost.css            dark theme matching the phone app
  whalepi_icon.png           app icon
src/main/deploy/
  whalepi.icns, whalepi.ico  installer icons
```

## Related

- [whalepidog](https://github.com/WhalePi/whalepidog) – the watchdog that runs PAMGuard on the Pi
- WhalePi phone app – connects over Bluetooth for live status and the `copywavdata` command
