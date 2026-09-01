package ma.cdg.claims.error;

/** The requested resource does not exist. Rendered as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException claim(Object identifier) {
        return new NotFoundException("No complaint found for " + identifier);
    }

    public static NotFoundException task(long taskKey) {
        return new NotFoundException("No open task found with key " + taskKey);
    }
}
