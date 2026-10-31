package com.moveinsync.gps.web;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Non-leaking errors: fixed messages only; we never echo input, and never log exception messages (Jackson quotes input). */
@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    record ErrorBody(String code, String message, String errorId) {}

    static final class ApiException extends RuntimeException {
        final HttpStatus status; final String code;
        ApiException(HttpStatus s, String code, String msg) { super(msg, null, false, false); this.status = s; this.code = code; }
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus s, String code, String msg, String id) {
        return ResponseEntity.status(s).body(new ErrorBody(code, msg, id));
    }
    @ExceptionHandler(ApiException.class) ResponseEntity<ErrorBody> api(ApiException e) { return body(e.status, e.code, e.getMessage(), null); }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST_BODY", "Request body is malformed", null);
    }
    @ExceptionHandler(NoSuchElementException.class) ResponseEntity<ErrorBody> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Trip not found", null);
    }
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<ErrorBody> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found", null);
    }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<ErrorBody> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "Operation not allowed in the trip's current state", null);
    }
    @ExceptionHandler(Exception.class) ResponseEntity<ErrorBody> other(Exception e) {
        String id = UUID.randomUUID().toString();
        log.error("event=unhandled_error errorId={} type={}", id, e.getClass().getSimpleName());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Unexpected error. Quote errorId to support.", id);
    }
}
