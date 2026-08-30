package whalepihost;

/**
 * Entry point for the packaged application.
 *
 * <p>A main class that does <em>not</em> extend {@code Application} lets the JVM
 * start normally when JavaFX is supplied on the class path, which is how the
 * jpackage installers ship it.
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        WhalePiHostApp.main(args);
    }
}
