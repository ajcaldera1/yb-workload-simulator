package com.yugabyte.simulation.dao.api;

import java.util.List;

public class WorkloadCatalogResponse {
    private String beanName;
    private String displayName;
    private List<WorkloadOperationResponse> operations;

    public String getBeanName() {
        return beanName;
    }

    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<WorkloadOperationResponse> getOperations() {
        return operations;
    }

    public void setOperations(List<WorkloadOperationResponse> operations) {
        this.operations = operations;
    }
}
