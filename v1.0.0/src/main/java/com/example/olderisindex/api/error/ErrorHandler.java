package com.example.olderisindex.api.error;

import com.example.olderisindex.api.ResearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(StudyNotFoundException.class)
    public ResponseEntity<ResearchResponse> onNotFound(StudyNotFoundException e) {
        if (log.isDebugEnabled()) log.debug("404 NOT_FOUND: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResearchResponse.notFound(e.getAn(), e.getSuid()));
    }
    @ExceptionHandler(ResearchTimeoutException.class)
    public ResponseEntity<ResearchResponse> onTimeout(ResearchTimeoutException e) {
        log.warn("504 TIMEOUT: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ResearchResponse.error(e.getAn(), e.getSuid(), "Database query timed out"));
    }
    @ExceptionHandler(ResearchDatabaseException.class)
    public ResponseEntity<ResearchResponse> onDb(ResearchDatabaseException e) {
        log.error("502 DB_ERROR: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ResearchResponse.error(e.getAn(), e.getSuid(), "Database error"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResearchResponse> onValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().isEmpty()
                ? "validation error"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        if (log.isDebugEnabled()) log.debug("400 VALIDATION: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResearchResponse.error(null, null, msg));
    }
}
