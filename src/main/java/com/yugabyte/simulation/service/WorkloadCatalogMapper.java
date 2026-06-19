package com.yugabyte.simulation.service;

import java.util.ArrayList;
import java.util.List;

import com.yugabyte.simulation.dao.ParamType;
import com.yugabyte.simulation.dao.ParamValue;
import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.dao.api.ParameterSchemaResponse;
import com.yugabyte.simulation.dao.api.WorkloadCatalogResponse;
import com.yugabyte.simulation.dao.api.WorkloadOperationResponse;

public final class WorkloadCatalogMapper {
    private WorkloadCatalogMapper() {
    }

    public static WorkloadCatalogResponse toCatalog(String beanName, WorkloadSimulation workload) {
        WorkloadCatalogResponse catalog = new WorkloadCatalogResponse();
        catalog.setBeanName(beanName);
        catalog.setDisplayName(workload.getName());
        catalog.setOperations(toOperations(workload.getWorkloads()));
        return catalog;
    }

    public static WorkloadOperationResponse toOperation(WorkloadDesc operation) {
        WorkloadOperationResponse response = new WorkloadOperationResponse();
        response.setOperationId(operation.getWorkloadId());
        response.setName(operation.getName());
        response.setDescription(operation.getDescription());
        response.setParameters(toParameterSchemas(operation.getParams()));
        return response;
    }

    private static List<WorkloadOperationResponse> toOperations(List<WorkloadDesc> operations) {
        List<WorkloadOperationResponse> mapped = new ArrayList<WorkloadOperationResponse>();
        for (WorkloadDesc operation : operations) {
            mapped.add(toOperation(operation));
        }
        return mapped;
    }

    private static List<ParameterSchemaResponse> toParameterSchemas(List<WorkloadParamDesc> params) {
        List<ParameterSchemaResponse> mapped = new ArrayList<ParameterSchemaResponse>();
        for (WorkloadParamDesc param : params) {
            mapped.add(toParameterSchema(param));
        }
        return mapped;
    }

    private static ParameterSchemaResponse toParameterSchema(WorkloadParamDesc param) {
        ParameterSchemaResponse schema = new ParameterSchemaResponse();
        schema.setName(param.getName());
        schema.setType(param.getType().name());
        schema.setMinValue(param.getMinValue() == Integer.MIN_VALUE ? null : param.getMinValue());
        schema.setMaxValue(param.getMaxValue() == Integer.MAX_VALUE ? null : param.getMaxValue());
        schema.setChoices(param.getChoices());
        schema.setDefaultValue(toDefaultValue(param.getDefaultValue()));
        return schema;
    }

    private static Object toDefaultValue(ParamValue defaultValue) {
        if (defaultValue == null) {
            return null;
        }
        ParamType type = defaultValue.getType();
        switch (type) {
            case BOOLEAN:
                return defaultValue.getBoolValue();
            case NUMBER:
                return defaultValue.getIntValue();
            case STRING:
                return defaultValue.getStringValue();
            default:
                return null;
        }
    }
}
