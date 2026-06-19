package com.yugabyte.simulation.exception;

public class WorkloadNotFoundException extends RuntimeException {
    private final String workloadBean;

    public WorkloadNotFoundException(String workloadBean) {
        super("Workload not found: " + workloadBean);
        this.workloadBean = workloadBean;
    }

    public String getWorkloadBean() {
        return workloadBean;
    }
}
