package com.yugabyte.simulation.dao.api;

import java.util.List;

public class ApiErrorResponse {
    private int status;
    private String message;
    private java.util.Map<String, String> fieldErrors;

    public ApiErrorResponse() {
    }

    public ApiErrorResponse(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public ApiErrorResponse(int status, String message, java.util.Map<String, String> fieldErrors) {
        this.status = status;
        this.message = message;
        this.fieldErrors = fieldErrors;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public java.util.Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(java.util.Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
