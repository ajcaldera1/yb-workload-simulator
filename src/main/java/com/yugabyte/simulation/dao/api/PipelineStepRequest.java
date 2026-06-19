package com.yugabyte.simulation.dao.api;

import java.util.Map;

public class PipelineStepRequest {
    private String operationId;
    private Map<String, Object> parameters;

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
}
