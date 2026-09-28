import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts ledger state to JSON and reads or writes UTF-8 files.
 * <p>The saved object contains {@code nextId} and a {@code sessions} array.
 * Each session contains {@code id}, {@code topic}, and {@code minutes}.
 * Loading constructs validated {@link Session} objects before committing the
 * complete state through {@link PracticeLedger#restoreSessions(List, int)}.</p>
 * <p>These methods perform synchronous work. The GUI schedules file access
 * on a background worker. Callers must supply non-null ledgers and paths.</p>
 */

public class LedgerFileStore {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    /**
     * Serializes the current sessions and ID counter as indented JSON.
     * <p>Session order is preserved and the ledger is not modified.</p>
     * @param ledger non-null ledger to serialize
     * @return the JSON representation of the ledger
     */

    public static String toJson(PracticeLedger ledger) {
        JsonObject root = new JsonObject();

        root.addProperty("nextId", ledger.getNextId());

        JsonArray sessionArray = new JsonArray();

        for (Session session : ledger.listSessions()) {
            JsonObject item = new JsonObject();

            item.addProperty("id", session.getId());
            item.addProperty("topic", session.getTopic());
            item.addProperty("minutes", session.getMinutes());
            sessionArray.add(item);
        }

        root.add("sessions", sessionArray);
        return GSON.toJson(root);
    }

    /**
     * Writes the ledger as UTF-8 JSON, creating or replacing the destination file.
     * <p>The parent directory must already exist. This method writes directly to
     * the destination; it does not itself provide an atomic replacement or backup.</p>
     * @param ledger non-null ledger to save
     * @param path non-null destination path; relative paths use the working directory
     * @throws IOException if writing the file fails
     */

    public static void save(PracticeLedger ledger, Path path)
            throws IOException {
        Files.writeString(path, toJson(ledger), StandardCharsets.UTF_8);
    }

    /**
     * Parses saved JSON and replaces the destination ledger with validated state.
     * <p>All entries are constructed and the complete state is validated before
     * the ledger is changed. Invalid saved data leaves the existing ledger intact.
     * Unknown extra properties are ignored.</p>
     * @param ledger non-null destination ledger
     * @param json non-null JSON text containing the saved state
     * @throws com.google.gson.JsonParseException if the text cannot be parsed
     * @throws IllegalArgumentException if required fields, session values, IDs,
     *         or the saved counter are invalid
     */

    public static void restoreFromJson(PracticeLedger ledger, String json) {
        JsonElement parsed = JsonParser.parseString(json);

        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Saved data must be a JSON object.");
        }

        JsonObject root = parsed.getAsJsonObject();

        int savedNextId = readInt(root, "nextId");

        JsonElement sessionsValue = root.get("sessions");

        if (sessionsValue == null || !sessionsValue.isJsonArray()) {
            throw new IllegalArgumentException(
                    "Saved data must contain a sessions array.");
        }

        List<Session> loaded = new ArrayList<>();

        for (JsonElement entry : sessionsValue.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Each session must be a JSON object.");
            }

            JsonObject item = entry.getAsJsonObject();

            int id = readInt(item, "id");
            String topic = readString(item, "topic");
            int minutes = readInt(item, "minutes");

            loaded.add(new Session(id, topic, minutes));

        }

        ledger.restoreSessions(loaded, savedNextId);

    }

    /**
     * Reads UTF-8 JSON from a file and restores the destination ledger.
     * <p>Read, parsing, and validation failures leave the existing ledger intact.</p>
     * @param ledger non-null destination ledger
     * @param path non-null file to read
     * @throws IOException if reading the file fails
     * @throws com.google.gson.JsonParseException if the text cannot be parsed
     * @throws IllegalArgumentException if the saved state is invalid
     */

    public static void load(PracticeLedger ledger, Path path)
            throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        restoreFromJson(ledger, json);
    }

    /**
     * Reads a required numeric property that has an exact {@code int} value.
     * <p>Numeric strings, fractional values, and out-of-range values are rejected.
     * An integer-valued number such as {@code 2.0} is accepted.</p>
     * @param object JSON object containing the property
     * @param name required property name
     * @return the exact integer value
     * @throws IllegalArgumentException if the property is missing, has the wrong
     *         type, or cannot be represented exactly as an int
     */

    private static int readInt(JsonObject object, String name) {
        JsonElement value = object.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be a number.");
        }

        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be a whole number within the int range.");
        }
    }

    /**
     * Reads a required JSON string property without trimming or validating its content.
     * @param object JSON object containing the property
     * @param name required property name
     * @return the string value
     * @throws IllegalArgumentException if the property is missing, null, or not a string
     */

    private static String readString(JsonObject object, String name) {
        JsonElement value = object.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }

        return value.getAsString();
    }
}