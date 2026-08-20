package ma.cdg.claims.camunda;

/** Raised when the workflow engine cannot serve a request. */
public class CamundaGatewayException extends RuntimeException {

    public CamundaGatewayException(String message) {
        super(message);
    }

    public CamundaGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
