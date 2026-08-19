package com.fx.api.web;

import com.fx.api.service.UnknownPairException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** HTTP status codes + exception handling — 404 vs 400, never a stack trace to the client. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownPairException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> unknownPair(UnknownPairException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(MethodArgumentNotValidException ex) {
        var fe = ex.getBindingResult().getFieldErrors().get(0);
        return Map.of("error", fe.getField() + ": " + fe.getDefaultMessage());
    }
}
