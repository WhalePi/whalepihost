package whalepihost.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What a delete achieved, whichever kind of card it ran against.
 *
 * @param files    regular files removed
 * @param folders  folders removed
 * @param bytes    space handed back to the card
 * @param problems anything that had to be left behind, in words for the user
 */
public record DeleteResult(long files, long folders, long bytes, List<String> problems) {

    public static final DeleteResult NOTHING = new DeleteResult(0, 0, 0, List.of());

    public DeleteResult plus(DeleteResult other) {
        List<String> combined = new ArrayList<>(problems);
        combined.addAll(other.problems);
        return new DeleteResult(files + other.files, folders + other.folders,
                bytes + other.bytes, List.copyOf(combined));
    }
}
