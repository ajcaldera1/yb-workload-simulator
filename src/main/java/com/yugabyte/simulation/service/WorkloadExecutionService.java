package com.yugabyte.simulation.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.yugabyte.simulation.dao.InvocationResult;
import com.yugabyte.simulation.dao.ParamValue;
import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.api.ExecutionResult;
import com.yugabyte.simulation.services.ServiceManager;

@Service
public class WorkloadExecutionService {
    @Autowired
    private WorkloadRegistryService workloadRegistryService;

    @Autowired
    private WorkloadParameterResolver workloadParameterResolver;

    @Autowired
    private ServiceManager serviceManager;

    public ExecutionResult execute(String workloadBean, String operationId, Map<String, Object> parameters) {
        String resolvedBean = workloadRegistryService.resolveBeanName(workloadBean);
        WorkloadSimulation workloadSimulation = workloadRegistryService.getWorkload(resolvedBean);
        WorkloadDesc operation = workloadRegistryService.getOperation(resolvedBean, operationId);
        ParamValue[] params = workloadParameterResolver.resolve(operation, parameters);

        try {
            if (operation.getInvoker() != null) {
                operation.invoke(serviceManager, params);
            } else {
                InvocationResult legacyResult = workloadSimulation.invokeWorkload(operationId, params);
                if (legacyResult != null && legacyResult.getResult() != 0) {
                    return new ExecutionResult(legacyResult.getResult(), legacyResult.getData(), resolvedBean, operationId);
                }
            }
            return new ExecutionResult(0, "Ok", resolvedBean, operationId);
        } catch (Exception e) {
            return new ExecutionResult(-1, e.getMessage(), resolvedBean, operationId);
        }
    }
}
