import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs standalone checks for persistence failures and empty-state restoration.
 * <p>Each check uses a fresh ledger. The missing-file check creates and removes
 * a temporary directory; the other checks operate on JSON strings in memory.
 * No application save file is used.</p>
 */

public class PersistenceFailureCheck {

    /**
     * Runs all four checks and prints a final message if they complete successfully.
     * @param args command-line arguments; unused
     * @throws IOException if temporary-directory setup or cleanup fails
     * @throws AssertionError if an expected outcome is not observed
     */

    public static void main(String[] args) throws IOException {
        testMissingFile();
        testMalformedJson();
        testNegativeMinutes();
        testEmptyRestore();

        System.out.println("All four persistence checks passed!");
    }

    /**
     * Creates an independent baseline ledger for a check.
     * @return a ledger containing ID 1, topic Original, duration 10, and next ID 2
     */

    private static PracticeLedger createLedger() {
        PracticeLedger ledger = new PracticeLedger();
        ledger.addSession("Original", 10);
        return ledger;
    }

    /**
     * Checks that a rejected operation preserved the baseline state.
     * @param ledger ledger that should still contain the baseline session and counter
     * @throws AssertionError if the session count, values, or counter changed
     */

    private static void checkUnchanged(PracticeLedger ledger) {
        List<Session> sessions = ledger.listSessions();

        if (sessions.size() != 1) {
            throw new AssertionError("Session count changed.");
        }

        Session session = sessions.get(0);

        if (session.getId() != 1
                || !session.getTopic().equals("Original")
                || session.getMinutes() != 10
                || ledger.getNextId() != 2) {
            throw new AssertionError("Existing ledger data changed.");
        }
    }

    /**
     * Checks that loading a nonexistent file throws and preserves the baseline ledger.
     * @throws IOException if temporary-directory creation or cleanup fails
     * @throws AssertionError if the expected read failure or unchanged state is not observed
     */

    private static void testMissingFile() throws IOException {
        PracticeLedger ledger = createLedger();

        // This new folder is empty, so missing.json doesn't exist.
        Path folder = Files.createTempDirectory("ledger-check-");
        Path missingFile = folder.resolve("missing.json");

        try {
            try {
                LedgerFileStore.load(ledger, missingFile);
                throw new AssertionError("Expected IOException.");
            } catch (IOException expected) {
                // Expected: the file doesn't exist.
            }

            checkUnchanged(ledger);
        } finally {
            Files.delete(folder);
        }

        System.out.println("PASS: Missing file preserves the ledger.");
    }

    /**
     * Checks that incomplete JSON is rejected without changing the baseline ledger.
     * @throws AssertionError if the expected parsing failure or unchanged state is not observed
     */

    private static void testMalformedJson() {
        PracticeLedger ledger = createLedger();

        try {
            LedgerFileStore.restoreFromJson(ledger, "{");
            throw new AssertionError("Expected JsonParseException.");
        } catch (JsonParseException expected) {
            // Expected: the JSON object is incomplete.
        }

        checkUnchanged(ledger);
        System.out.println("PASS: Malformed JSON preserves the ledger.");
    }

    /**
     * Checks that a negative session duration is rejected without changing the ledger.
     * @throws AssertionError if the expected validation failure or unchanged state is not observed
     */

    private static void testNegativeMinutes() {
        PracticeLedger ledger = createLedger();

        String json = """
            {
              "nextId": 3,
              "sessions": [
                {"id": 2, "topic": "Arrays", "minutes": -5}
              ]
            }
            """;

        try {
            LedgerFileStore.restoreFromJson(ledger, json);
            throw new AssertionError("Expected IllegalArgumentException.");
        } catch (IllegalArgumentException expected) {
            // Expected: Session rejects negative minutes.
        }

        checkUnchanged(ledger);
        System.out.println("PASS: Invalid minutes preserve the ledger.");
    }

    /**
     * Checks that empty saved state replaces existing sessions and restores its counter.
     * <p>A subsequent addition must use the restored ID and then advance the counter.</p>
     * @throws AssertionError if empty-state restoration or subsequent ID allocation is incorrect
     */

    private static void testEmptyRestore() {
        PracticeLedger ledger = createLedger();

        String json = """
            {
              "nextId": 8,
              "sessions": []
            }
            """;

        LedgerFileStore.restoreFromJson(ledger, json);

        if (!ledger.listSessions().isEmpty()) {
            throw new AssertionError("Expected an empty ledger.");
        }

        if (ledger.getNextId() != 8) {
            throw new AssertionError("Expected nextId to be 8.");
        }

        ledger.addSession("Hashing", 20);

        if (ledger.listSessions().get(0).getId() != 8
                || ledger.getNextId() != 9) {
            throw new AssertionError("Restored counter wasn't used correctly.");
        }

        System.out.println("PASS: Empty restore preserves the saved counter.");
    }
}