package ma.cdg.claims.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ma.cdg.claims.service.AssistantService;
import ma.cdg.claims.camunda.CamundaGatewayException;
import ma.cdg.claims.error.AccessDeniedForTaskException;
import ma.cdg.claims.error.BusinessRuleException;
import ma.cdg.claims.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders every failure as an RFC 9457 problem document. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail onBusinessRule(BusinessRuleException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Action not allowed", e.getMessage(), request);
    }

    @ExceptionHandler({AccessDeniedForTaskException.class, AccessDeniedException.class})
    ProblemDetail onForbidden(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail onBadCredentials(BadCredentialsException e, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed",
                "Incorrect username or password", request);
    }

    @ExceptionHandler(AssistantService.AssistantUnavailableException.class)
    ProblemDetail onAssistantUnavailable(AssistantService.AssistantUnavailableException e,
                                         HttpServletRequest request) {
        // The message is written for the agent reading it in the panel.
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Assistant unavailable", e.getMessage(), request);
    }

    @ExceptionHandler(CamundaGatewayException.class)
    ProblemDetail onEngineFailure(CamundaGatewayException e, HttpServletRequest request) {
        log.error("Workflow engine call failed", e);
        return problem(HttpStatus.BAD_GATEWAY, "Workflow engine unavailable", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Some fields are missing or malformed", request);
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "The request could not be completed. Please contact the administrator.", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}
