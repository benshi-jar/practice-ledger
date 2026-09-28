import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages practice sessions and the next available session ID in memory.
 * <p>Sessions retain insertion order. Queries return list copies containing
 * immutable {@link Session} references. Editing replaces a session while
 * preserving its ID and position; deletion does not reduce the ID counter.</p>
 * <p>This mutable class is not thread-safe. Callers must coordinate concurrent
 * access. ID allocation assumes the {@code int} counter has not been exhausted.</p>
 */

public class PracticeLedger {
    private final List<Session> sessions = new ArrayList<>();
    private int nextId = 1;

    /**
     * Appends a validated session using the next ID, then advances the counter.
     * <p>Repeated topics are allowed. Validation failure changes neither the list
     * nor the counter.</p>
     * @param topic non-null, nonblank session topic
     * @param minutes positive duration
     * @throws IllegalArgumentException if session construction rejects the values
     */

    public void addSession(String topic, int minutes) {
        Session session = new Session(nextId, topic, minutes);
        sessions.add(session);
        nextId++;
    }

    /**
     * Returns a mutable snapshot of the session list in ledger order.
     * <p>Changing the returned list does not change the ledger. Session objects
     * are shared safely because they are immutable.</p>
     * @return a new list, empty when the ledger has no sessions
     */

    public List<Session> listSessions() {
        List<Session> clone = new ArrayList<>();
        for (int i = 0; i<sessions.size(); i++){
            clone.add(sessions.get(i));
        }
        return clone;
    }

    /**
     * Sums the durations of all sessions without modifying the ledger.
     * @return total minutes, or zero for an empty ledger
     */

    public long totalMinutes() {
        long minutes = 0;
        for (int i = 0; i<sessions.size(); i++){
            minutes += sessions.get(i).getMinutes();
        }
        return minutes;
    }
    
    /**
     * Removes the session with the specified ID without changing the next ID.
     * @param id session identifier to find
     * @return true if a session was removed; false if no ID matched
     */

    public boolean deleteSession(int id) {

        for (int i = 0; i<sessions.size(); i++){
            if (sessions.get(i).getId() == id){
                sessions.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces a matching session while preserving its ID and list position.
     * <p>The replacement is validated before the existing entry is changed.
     * If the ID is absent, the method returns false without validating the new values.</p>
     * @param id identifier of the session to replace
     * @param topic new non-null, nonblank topic
     * @param minutes new positive duration
     * @return true if replaced; false if no ID matched
     * @throws IllegalArgumentException if a matching session's replacement values are invalid
     */

    public boolean replaceSession(int id, String topic, int minutes){
        for (int i = 0; i<sessions.size(); i++) {
            if (sessions.get(i).getId() == id) {
                sessions.set(i, new Session(id, topic, minutes));
                return true;
            }
        }
        return false;
    }

    /**
     * Finds exact topic matches, ignoring case and trimming the search input.
     * @param topic non-null, nonblank topic to search for
     * @return a new list of matching sessions in ledger order, possibly empty
     * @throws IllegalArgumentException if the search topic is null or blank
     */

    public List<Session> findSessionsByTopic(String topic){
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic must not be blank.");
        }

        String searchTopic = topic.trim();

        List<Session> filtered = new ArrayList<>(); 
        for (int i = 0; i<sessions.size(); i++) {
            if (sessions.get(i).getTopic().equalsIgnoreCase(searchTopic)) {
                filtered.add(sessions.get(i));
            }
        }
        return filtered;
    }

    /**
     * Sums durations for exact topic matches, ignoring case and surrounding input spaces.
     * @param topic non-null, nonblank topic to search for
     * @return matching minutes, or zero when no session matches
     * @throws IllegalArgumentException if the topic is null or blank
     */

    public long totalMinutesByTopic(String topic) {
        long minutes = 0;
        List<Session> filteredList = findSessionsByTopic(topic);
        for (int i = 0; i < filteredList.size(); i++) {
            minutes += filteredList.get(i).getMinutes();
        }
        return minutes;
    }

    /**
     * Finds the session with the largest duration in the current ledger.
     * <p>Ties are resolved by choosing the first session in ledger order.</p>
     * @return the longest session, or null when the ledger is empty
     */

    public Session findLongestSession() {
        Session longest = null;

        for (Session session : sessions) {
            if (longest == null) {
                longest = session;
            }
            else {
                if (longest.getMinutes() < session.getMinutes()) {
                    longest = session;
                }
            }
        }

        return longest;
    }

    /**
     * Returns a new list sorted by duration from largest to smallest.
     * <p>Equal durations retain ledger order. The internal list is not reordered.</p>
     * @return a sorted snapshot, possibly empty
     */

    public List<Session> listSessionsByMinutes() {
        // 1. Copy the list so sorting doesn't change the ledger's order.
        List<Session> sorted = new ArrayList<>(sessions);

        // 2. Describe the ordering: compare minutes, largest first.
        Comparator<Session> byMinutes =
                Comparator.comparingInt(Session::getMinutes).reversed();

        // 3. Sort the copy using that ordering.
        sorted.sort(byMinutes);

        return sorted;
    }

    /**
     * Removes every exact topic match, ignoring case and trimming the search input.
     * <p>Survivor order and the next ID are preserved. A survivor list avoids
     * repeated array shifts, using linear time and space apart from string comparisons.</p>
     * @param topic non-null, nonblank topic to delete
     * @return the number of removed sessions
     * @throws IllegalArgumentException if the topic is null or blank
     */

    public int deleteSessionsByTopic(String topic) {
        List<Session> survivors = new ArrayList<>();
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic must not be blank.");
        }

        String searchTopic = topic.trim();
        int deleted = 0;

        for (Session session : sessions) {
            if (session.getTopic().equalsIgnoreCase(searchTopic)) {
                deleted++;
            }
            else {
                survivors.add(session);
            }
        }

        sessions.clear();
        sessions.addAll(survivors);

        return deleted;
    }

    /**
     * Returns the ID counter without consuming an ID.
     * @return the counter to persist alongside the sessions
     */

    public int getNextId() {
        return nextId;
    }

    /**
     * Replaces the ledger with validated saved sessions and an ID counter.
     * <p>Validation finishes before either field is changed. The supplied order is
     * preserved and list membership is copied. An empty list with a positive counter
     * is valid. Validation failure leaves the current state unchanged.</p>
     * @param loadedSessions non-null list of non-null sessions with unique IDs
     * @param savedNextId positive counter greater than every loaded ID
     * @throws IllegalArgumentException if the list or an entry is null, an ID is
     *         duplicated, or the counter fails the stated requirements
     */

    public void restoreSessions(List<Session> loadedSessions, int savedNextId) throws IllegalArgumentException {
        Set<Integer> seenIds = new HashSet<>();

        if (loadedSessions == null) {
            throw new IllegalArgumentException("The list of loaded sessions is null.");
        }
        if (savedNextId < 1) {
            throw new IllegalArgumentException("The next ID is less than 1.");
        }
        for (Session session : loadedSessions) {
            if (session == null) {
                throw new IllegalArgumentException("There is a null Session.");
            }
            if (!seenIds.add(session.getId())) {
                throw new IllegalArgumentException("Duplicate session ID.");
            }
            if (session.getId() >= savedNextId) {
                throw new IllegalArgumentException(
                    "The next saved ID must be greater than every loaded session ID.");
            }
        }

        sessions.clear();
        sessions.addAll(loadedSessions);
        nextId = savedNextId;
    }
}