package com.yugabyte.simulation.exception;

public class OperationNotFoundException extends RuntimeException {
    private final String workloadBean;
    private final String operationId;

    public OperationNotFoundException(String workloadBean, String operationId) {
        super("Operation not found: " + operationId + " on workload " + workloadBean);
        this.workloadBean = workloadBean;
        this.operationId = operationId;
    }

    public String getWorkloadBean() {
        return workloadBean;
    }

    public String getOperationId() {
        return operationId;
    }
}
