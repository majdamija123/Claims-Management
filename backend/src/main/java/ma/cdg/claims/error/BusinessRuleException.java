package ma.cdg.claims.error;

/** The request is well formed but not allowed by the business rules. Rendered as HTTP 409. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
