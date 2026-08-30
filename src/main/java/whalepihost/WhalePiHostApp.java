package whalepihost;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import whalepihost.ui.MainView;

/**
 * WhalePi Host – reads a WhalePi SD card and copies its data onto a computer.
 *
 * <p>Started from {@link Launcher}, never directly: see the note there.
 */
public class WhalePiHostApp extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView(stage);
        Scene scene = new Scene(view, 1040, 840);

        var css = WhalePiHostApp.class.getResource("/whalepihost/whalepihost.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        var icon = WhalePiHostApp.class.getResource("/whalepihost/whalepi_icon.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));

        stage.setTitle("WhalePi Host");
        stage.setMinWidth(820);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();
    }
}
