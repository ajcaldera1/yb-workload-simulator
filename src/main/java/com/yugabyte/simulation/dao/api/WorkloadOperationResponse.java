package com.yugabyte.simulation.dao.api;

import java.util.List;

public class WorkloadOperationResponse {
    private String operationId;
    private String name;
    private String description;
    private List<ParameterSchemaResponse> parameters;

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ParameterSchemaResponse> getParameters() {
        return parameters;
    }

    public void setParameters(List<ParameterSchemaResponse> parameters) {
        this.parameters = parameters;
    }
}
