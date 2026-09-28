import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs a standalone save/load round-trip check.
 * <p>The check verifies restored session values, persistence of an ID counter
 * after deletion, and ID allocation after loading. It creates or overwrites
 * {@code ledger-roundtrip-test.json} in the working directory and leaves that
 * test file in place.</p>
 */

public class PersistenceCheck {
    /**
     * Executes the round-trip assertions and prints a success message.
     * @param args command-line arguments; unused
     * @throws IOException if the test file cannot be written or read
     * @throws AssertionError if a checked result differs from the expected value
     */

    public static void main(String[] args) throws IOException {
        PracticeLedger original = new PracticeLedger();
        original.addSession("Arrays", 20); // ID 1
        original.addSession("Graphs", 35); // ID 2
        original.deleteSession(2);         // nextId remains 3

        Path path = Path.of("ledger-roundtrip-test.json");
        LedgerFileStore.save(original, path);

        // A separate ledger verifies that we're actually loading the file.
        PracticeLedger restored = new PracticeLedger();
        LedgerFileStore.load(restored, path);

        List<Session> sessions = restored.listSessions();

        if (sessions.size() != 1) {
            throw new AssertionError("Expected one restored session.");
        }

        Session session = sessions.get(0);

        if (session.getId() != 1
                || !session.getTopic().equals("Arrays")
                || session.getMinutes() != 20) {
            throw new AssertionError("Restored session data is incorrect.");
        }

        if (restored.getNextId() != 3) {
            throw new AssertionError("Expected nextId to remain 3.");
        }

        restored.addSession("Hashing", 20);

        if (restored.listSessions().get(1).getId() != 3) {
            throw new AssertionError("New session should receive ID 3.");
        }

        if (restored.totalMinutes() != 40) {
            throw new AssertionError("Expected 40 total minutes.");
        }

        System.out.println("Save/load round-trip passed!");
    }
}
