package com.yugabyte.simulation.dao.api;

import java.util.Map;

public class ExecuteOperationRequest {
    private Map<String, Object> parameters;

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
}
