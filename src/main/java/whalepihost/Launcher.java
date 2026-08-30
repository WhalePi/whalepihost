package whalepihost;

import javafx.application.Application;

/**
 * The application's only entry point.
 *
 * <p>A main class that does <em>not</em> extend {@code Application} lets the JVM
 * start normally when JavaFX is supplied on the class path rather than the module
 * path. Launching {@link WhalePiHostApp} directly instead fails with "JavaFX
 * runtime components are missing, and are required to run this application" –
 * which is what an IDE such as Eclipse does if the Application subclass has a
 * {@code main} of its own, so this class deliberately holds the only one.
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        Application.launch(WhalePiHostApp.class, args);
    }
}
