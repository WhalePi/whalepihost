package whalepihost.ui;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * The Material icons used by the WhalePi phone app, as scalable JavaFX shapes so
 * the desktop app reads as part of the same family.
 */
public final class Icons {

    public static final String SD_CARD =
            "M18 2h-8L4.02 8 4 20c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 6H8V4h2v4zm3 "
            + "0h-2V4h2v4zm3 0h-2V4h2v4z";

    public static final String GRAPHIC_EQ =
            "M7 18h2V6H7v12zm4 4h2V2h-2v20zm-8-8h2v-4H3v4zm12 4h2V6h-2v12zm4-4h2v-4h-2v4z";

    public static final String STORAGE =
            "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z";

    public static final String DESCRIPTION =
            "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0"
            + "-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z";

    /** hexagon_outlined – the icon the phone app uses for its hex/binary view. */
    public static final String HEXAGON =
            "M17.2 3H6.8l-5.2 9 5.2 9h10.4l5.2-9-5.2-9zM16.05 19H7.95l-4.04-7 4.04-7h8.09l4.04 7-4.03 7z";

    public static final String FOLDER =
            "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z";

    public static final String FOLDER_OPEN =
            "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 "
            + "12H4V8h16v10z";

    public static final String FILE =
            "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z";

    public static final String DOWNLOAD =
            "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z";

    public static final String REFRESH =
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.57 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c"
            + "-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 "
            + "2.35z";

    public static final String SEARCH =
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 "
            + "9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 "
            + "9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";

    public static final String STOP = "M6 6h12v12H6z";

    private Icons() { }

    /** An icon at the given pixel size, in the given colour. */
    public static Group of(String path, double size, Color colour) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.setFill(colour);
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        // Wrapping in a Group makes the scaled shape report its scaled bounds,
        // so surrounding layouts reserve the right amount of space.
        return new Group(svg);
    }
}
