package com.yugabyte.simulation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yugabyte.simulation.dao.api.ExecuteOperationRequest;
import com.yugabyte.simulation.dao.api.ExecutePipelineRequest;
import com.yugabyte.simulation.dao.api.ExecutionResult;
import com.yugabyte.simulation.dao.api.PipelineExecutionResult;
import com.yugabyte.simulation.dao.api.WorkloadCatalogResponse;
import com.yugabyte.simulation.dao.api.WorkloadOperationResponse;
import com.yugabyte.simulation.service.WorkloadCatalogMapper;
import com.yugabyte.simulation.service.WorkloadExecutionService;
import com.yugabyte.simulation.service.WorkloadOrchestrationService;
import com.yugabyte.simulation.service.WorkloadRegistryService;
import com.yugabyte.simulation.service.WorkloadSimulation;

@RestController
@RequestMapping("/api/v1/workloads")
public class WorkloadApiController {
    @Autowired
    private WorkloadRegistryService workloadRegistryService;

    @Autowired
    private WorkloadExecutionService workloadExecutionService;

    @Autowired
    private WorkloadOrchestrationService workloadOrchestrationService;

    @GetMapping
    public List<WorkloadCatalogResponse> listWorkloads() {
        List<WorkloadCatalogResponse> catalogs = new ArrayList<WorkloadCatalogResponse>();
        for (String beanName : workloadRegistryService.getWorkloadBeanNames()) {
            WorkloadSimulation workload = workloadRegistryService.getWorkload(beanName);
            catalogs.add(WorkloadCatalogMapper.toCatalog(beanName, workload));
        }
        return catalogs;
    }

    @GetMapping("/{beanName}")
    public WorkloadCatalogResponse getWorkload(@PathVariable String beanName) {
        String resolvedBean = workloadRegistryService.resolveBeanName(beanName);
        WorkloadSimulation workload = workloadRegistryService.getWorkload(resolvedBean);
        return WorkloadCatalogMapper.toCatalog(resolvedBean, workload);
    }

    @GetMapping("/{beanName}/operations/{operationId}")
    public WorkloadOperationResponse getOperation(@PathVariable String beanName, @PathVariable String operationId) {
        String resolvedBean = workloadRegistryService.resolveBeanName(beanName);
        return WorkloadCatalogMapper.toOperation(workloadRegistryService.getOperation(resolvedBean, operationId));
    }

    @PostMapping("/{beanName}/operations/{operationId}/execute")
    public ExecutionResult executeOperation(@PathVariable String beanName, @PathVariable String operationId,
            @RequestBody(required = false) ExecuteOperationRequest request) {
        Map<String, Object> parameters = request == null ? null : request.getParameters();
        return workloadExecutionService.execute(beanName, operationId, parameters);
    }

    @PostMapping("/{beanName}/pipelines/execute")
    public PipelineExecutionResult executePipeline(@PathVariable String beanName,
            @RequestBody ExecutePipelineRequest request) throws InterruptedException {
        return workloadOrchestrationService.executePipeline(beanName, request.getSteps(), request.isWaitForCompletion());
    }
}
