import java.util.Scanner;
import java.util.List;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides the interactive terminal interface for Practice Ledger.
 * <p>This version explicitly saves and loads {@code ledger-test.json} in the
 * working directory. Exiting does not automatically save or prompt for unsaved
 * changes. The graphical alternative is {@link LedgerGui}.</p>
 */

public class Main {

    private static final Scanner scanner = new Scanner(System.in);

    /**
     * Runs the numbered menu until the user selects Exit.
     * <p>Expected file and validation errors are reported without ending the menu.
     * The input scanner, including its standard-input stream, is closed on normal exit.</p>
     * @param args command-line arguments; unused
     */

    public static void main(String[] args) {
        
        PracticeLedger ledger = new PracticeLedger();
        
        boolean running = true;

        Path SAVE_PATH = Path.of("ledger-test.json");
        
        while (running) {
            //*
            // 1. Add session
            // 2. List sessions
            // 3. Show total minutes
            // 4. Delete session by ID
            // 5. Edit session
            // 6. Find sessions by topic
            // 7. Show total minutes for a topic
            // 8. Show longest session
            // 9. List sessions by duration
            // 10. Delete sessions by topic
            // 11. Save
            // 12. Load
            // 13. Exit 
            // */
            System.out.println("\n1. Add session");
            System.out.println("2. List sessions");
            System.out.println("3. Show total minutes");
            System.out.println("4. Delete session");
            System.out.println("5. Replace session");
            System.out.println("6. Find sessions by topic");
            System.out.println("7. Show total minutes for a topic");
            System.out.println("8. Show longest session");
            System.out.println("9. List sessions by duration");
            System.out.println("10. Delete sessions by topic");
            System.out.println("11. Save");
            System.out.println("12. Load");
            System.out.println("13. Exit");

            String choice = getString("Choose an option: ");

            if (choice.equals("1")) {
                String topic = getString("Enter the session topic: ");
                int minutes = getInt("Enter the session minutes: ");
                try {
                    ledger.addSession(topic, minutes);
                    System.out.println("Session successfully added!");
                } catch (IllegalArgumentException exception) {
                System.out.println("No session added: " + exception.getMessage());
                }
            }
            else if (choice.equals("2")) {
                List<Session> sessions = ledger.listSessions();

                if (sessions.isEmpty()) {
                    System.out.println("No sessions yet.");
                } else {
                    for (Session session : sessions) {
                        System.out.println(session);
                    }
                }
            }
            else if (choice.equals("3")) {
                System.out.println("Total minutes: " + ledger.totalMinutes());
            }
            else if (choice.equals("4")) {
                int id = getInt("Enter session id: ");
                if(ledger.deleteSession(id)){
                    System.out.println("Session deleted.");
                }
                else {
                    System.out.println("Session not found.");
                }
            }
            else if (choice.equals("5")) {
                int id = getInt("Enter session id: ");
                String topic = getString("Enter the session topic: ");
                int minutes = getInt("Enter the session minutes: ");
                try {
                    if (ledger.replaceSession(id, topic, minutes)) {
                        System.out.println("Sessions successfully replaced!");
                    }
                    else {
                        System.out.println("Session not found.");
                    }
                } catch (IllegalArgumentException exception) {
                    System.out.println(exception.getMessage());
                }
            }
            else if (choice.equals("6")) {
                String topic = getString("Enter the session topic: ");
                try {
                    List<Session> topicSessions = ledger.findSessionsByTopic(topic);
                    if (topicSessions.isEmpty()) {
                        System.out.println("There are no " + topic + " sessions");
                    }
                    else {
                        System.out.println(topicSessions);
                    }
                } catch (IllegalArgumentException exception) {
                    System.out.println(exception.getMessage());
                }
                
            }
            else if (choice.equals("7")) {
                String topic = getString("Enter the session topic: ");
                try{ 
                    long minutes = ledger.totalMinutesByTopic(topic);
                    System.out.println("Total minutes for the " + topic +" topic: " + minutes);
                } catch (IllegalArgumentException exception) {
                    System.out.println(exception.getMessage());
                }
            }
            else if (choice.equals("8")) {
                Session longestSession = ledger.findLongestSession();
                if (longestSession == null){
                    System.out.println("There are no sessions");
                }
                else {
                    System.out.println("The longest session is " + longestSession);
                }
            }
            else if (choice.equals("9")) {
                List<Session> sorted = ledger.listSessionsByMinutes();
                if (sorted.isEmpty()) {
                    System.out.println("There are no sessions");
                }
                else {
                    System.out.println(sorted);
                }
            }
            else if (choice.equals("10")) {
                String topic = getString("Enter the session topic: ");
                String check = getString(
                    "Are you sure you want to delete all sessions with the topic: " + topic +"?" + " Enter 'yes' or 'no': ");
                boolean yes = check.equalsIgnoreCase("yes");
                if (yes){
                    try {
                        int sessionsDeleted = ledger.deleteSessionsByTopic(topic);
                        if (sessionsDeleted == 0) {
                            System.out.println("There are no " + topic + " sessions.");
                        }
                        else if (sessionsDeleted == 1) {
                            System.out.println(sessionsDeleted + " session was deleted.");
                        }
                        else {
                            System.out.println(sessionsDeleted + " sessions were deleted.");
                        }                    
                    } catch (IllegalArgumentException exception) {
                        System.out.println(exception.getMessage());
                    }
                }
                else {
                    System.out.println("Deletion cancelled.");
                }
            }
            else if (choice.equals("11")) {
                try {
                    LedgerFileStore.save(ledger, SAVE_PATH);
                    System.out.println("Saved to: " + SAVE_PATH.toAbsolutePath());
                } catch (IOException exception) {
                    System.out.println(
                            "Could not save the file: " + exception.getMessage());
                }
            }
            else if (choice.equals("12")) {
                String answer = getString(
                        "Loading replaces current sessions. Continue? yes/no: ");

                if (answer.trim().equalsIgnoreCase("yes")) {
                    try {
                        LedgerFileStore.load(ledger, SAVE_PATH);

                        System.out.println("Sessions loaded successfully.");
                    } catch (IOException exception) {
                        System.out.println(
                                "Could not read the file: " + exception.getMessage());
                    } catch (JsonParseException exception) {
                        System.out.println(
                                "The file could not be parsed as JSON.");
                    } catch (IllegalArgumentException exception) {
                        System.out.println(
                                "Invalid saved data: " + exception.getMessage());
                    }
                } else {
                    System.out.println("Loading cancelled.");
                }
            }
            else if (choice.equals("13")) {
                System.out.println("Exiting program...");
                running = false;
            }
            else {
                System.out.println("Try again.");
            }
        }
        scanner.close();
        
    }

    /**
     * Displays a prompt and reads one line from standard input without trimming it.
     * @param output prompt to display
     * @return the entered line
     * @throws java.util.NoSuchElementException if no input line is available
     * @throws IllegalStateException if the scanner has been closed
     */

    public static String getString(String output) {
        System.out.print(output);
        String input = scanner.nextLine();
        return input;
    }

    /**
     * Prompts repeatedly until the user supplies a positive int.
     * <p>Surrounding input spaces are trimmed. Invalid numbers and nonpositive
     * values cause an explanatory message and another attempt.</p>
     * @param output prompt to display on each attempt
     * @return a positive integer entered by the user
     * @throws java.util.NoSuchElementException if no input line is available
     * @throws IllegalStateException if the scanner has been closed
     */

    public static int getInt(String output) {
        while (true) {
            System.out.print(output);

            try {
                int input = Integer.parseInt(scanner.nextLine().trim());
                if (input > 0) {
                    return input;
                }
                else {
                    System.out.println("Entry must be greater than zero.");
                }
            } catch (NumberFormatException exception) {
                System.out.println("Please enter a whole number within the int range.");
            }
        }
    }
}