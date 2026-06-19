package com.yugabyte.simulation.dao.api;

public class PipelineStepResult {
    private String operationId;
    private int result;
    private String message;

    public PipelineStepResult() {
    }

    public PipelineStepResult(String operationId, int result, String message) {
        this.operationId = operationId;
        this.result = result;
        this.message = message;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
