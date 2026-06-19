package com.yugabyte.simulation.dao.api;

public class ExecutionResult {
    private int result;
    private String message;
    private String workloadBean;
    private String operationId;

    public ExecutionResult() {
    }

    public ExecutionResult(int result, String message, String workloadBean, String operationId) {
        this.result = result;
        this.message = message;
        this.workloadBean = workloadBean;
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

    public String getWorkloadBean() {
        return workloadBean;
    }

    public void setWorkloadBean(String workloadBean) {
        this.workloadBean = workloadBean;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }
}
