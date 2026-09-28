/**
 * Represents an immutable coding-practice session.
 * <p>A session has a positive ID, a nonblank topic, and a positive duration.
 * ID uniqueness is enforced by {@link PracticeLedger}, not by this class.</p>
 */

public final class Session {
    private final String topic;
    private final int minutes;
    private final int id;

    /**
     * Creates a validated session and trims the topic using {@link String#trim()}.
     * @param id positive session identifier
     * @param topic non-null, nonblank topic
     * @param minutes positive duration in minutes
     * @throws IllegalArgumentException if the topic is null or blank, or the ID or duration is not positive
     */

    public Session(int id, String topic, int minutes) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic must not be blank.");
        }
        if (minutes <= 0) {
            throw new IllegalArgumentException("Minutes must be positive.");
        }
        if (id <= 0) {
            throw new IllegalArgumentException("Id must be positive.");
        }
        this.id = id;
        this.topic = topic.trim();
        this.minutes = minutes;
    }

    /**
     * Returns the stored, trimmed topic.
     * @return the session topic
     */

    public String getTopic() {  
        return topic;
    }

    /**
     * Returns the duration in minutes.
     * @return the positive duration
     */

    public int getMinutes() {
        return minutes;
    }
    
    /**
     * Returns this session's identifier.
     * @return the positive session ID
     */

    public int getId(){
        return id;
    }

    /**
     * Returns a readable description containing the ID, topic, and minutes.
     * @return a description such as {@code #1 - Arrays - 20 minutes}
     */

    @Override
    public String toString() {
        return "#" + id + " - " + topic + " - " + minutes + " minutes";
    }
}