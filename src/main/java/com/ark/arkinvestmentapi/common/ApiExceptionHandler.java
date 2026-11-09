package com.ark.arkinvestmentapi.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String type, String title, int status, String detail, String instance) {
    }

    private ResponseEntity<ApiError> error(int status, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ApiError("about:blank", title, status, detail, request.getRequestURI()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> missing(ResourceNotFoundException e, HttpServletRequest r) {
        return error(404, "NotFound", e.getMessage(), r);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest r) {
        return error(409, "Conflict", r.getRequestURI().contains("/users")
                ? "Identity binding unavailable" : "Operation blocked by existing participation or transactions.", r);
    }

    @ExceptionHandler(StateConflictException.class)
    ResponseEntity<ApiError> stateConflict(StateConflictException e, HttpServletRequest r) {
        return error(409, "Conflict", e.getMessage(), r);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalid(HttpServletRequest r) {
        return error(400, "BadRequest", "Invalid JSON, field value, UUID, or pagination.", r);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpServletRequest r) {
        return error(415, "UnsupportedMediaType", "Request body must use application/json.", r);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(HttpServletRequest r, HttpServletResponse response) {
        response.setHeader("WWW-Authenticate", "Bearer");
        return error(401, "Unauthorized", "Application identity unavailable", r);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(HttpServletRequest r) {
        return error(403, "Forbidden", "Insufficient client permission", r);
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
            NoResourceFoundException.class})
    ResponseEntity<ApiError> framework(Exception e, HttpServletRequest r) {
        var frameworkError = (ErrorResponse) e;
        int status = frameworkError.getStatusCode().value();
        String title = switch (status) {
            case 405 -> "MethodNotAllowed";
            case 406 -> "NotAcceptable";
            default -> "NotFound";
        };
        String detail = switch (status) {
            case 405 -> "Request method is not supported for this resource.";
            case 406 -> "Requested response media type is not supported.";
            default -> "Resource not found.";
        };
        return ResponseEntity.status(frameworkError.getStatusCode()).headers(frameworkError.getHeaders())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ApiError("about:blank", title, status, detail, r.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest r) {
        LOG.error("Unexpected API failure", e);
        return error(500, "InternalError", "Unexpected server failure; internal implementation details are not exposed.", r);
    }
}
