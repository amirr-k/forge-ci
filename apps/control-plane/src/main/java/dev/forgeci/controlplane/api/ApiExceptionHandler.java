package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.service.ArtifactIntegrityException;
import dev.forgeci.controlplane.service.InvalidTransitionException;
import dev.forgeci.controlplane.service.LeaseRejectedException;
import dev.forgeci.controlplane.service.NotFoundException;
import dev.forgeci.controlplane.service.StaleTransitionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(InvalidTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "invalid_transition", "message", e.getMessage()));
    }

    @ExceptionHandler(StaleTransitionException.class)
    public ResponseEntity<Map<String, String>> staleTransition(StaleTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stale_transition", "message", e.getMessage()));
    }

    @ExceptionHandler(LeaseRejectedException.class)
    public ResponseEntity<Map<String, String>> leaseRejected(LeaseRejectedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "lease_rejected", "message", e.getMessage()));
    }

    @ExceptionHandler(ArtifactIntegrityException.class)
    public ResponseEntity<Map<String, String>> artifactIntegrity(ArtifactIntegrityException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "artifact_corrupt", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                        .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "validation_failed", "message", message));
    }
}
