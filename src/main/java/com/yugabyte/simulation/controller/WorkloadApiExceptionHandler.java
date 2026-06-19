package com.yugabyte.simulation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.yugabyte.simulation.dao.api.ApiErrorResponse;
import com.yugabyte.simulation.exception.CertificateNotFoundException;
import com.yugabyte.simulation.exception.ConflictException;
import com.yugabyte.simulation.exception.OperationNotFoundException;
import com.yugabyte.simulation.exception.ParameterValidationException;
import com.yugabyte.simulation.exception.WorkloadNotFoundException;

@RestControllerAdvice(basePackageClasses = { WorkloadApiController.class, DatabaseApiController.class })
public class WorkloadApiExceptionHandler {

    @ExceptionHandler(WorkloadNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkloadNotFound(WorkloadNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler({ OperationNotFoundException.class, CertificateNotFoundException.class })
    public ResponseEntity<ApiErrorResponse> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(ParameterValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ParameterValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), ex.getFieldErrors()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.getMessage()));
    }
}
