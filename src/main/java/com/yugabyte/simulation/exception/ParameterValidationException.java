package com.yugabyte.simulation.exception;

import java.util.Collections;
import java.util.Map;

public class ParameterValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ParameterValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyMap();
    }

    public ParameterValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors == null ? Collections.emptyMap() : fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
