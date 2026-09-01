package ma.cdg.claims.error;

/** The user may not act on this task. Rendered as HTTP 403. */
public class AccessDeniedForTaskException extends RuntimeException {

    public AccessDeniedForTaskException(String message) {
        super(message);
    }
}
