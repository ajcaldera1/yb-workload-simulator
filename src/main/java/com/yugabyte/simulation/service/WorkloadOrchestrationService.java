package com.yugabyte.simulation.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.yugabyte.simulation.dao.api.ExecutionResult;
import com.yugabyte.simulation.dao.api.PipelineExecutionResult;
import com.yugabyte.simulation.dao.api.PipelineStepRequest;
import com.yugabyte.simulation.dao.api.PipelineStepResult;
import com.yugabyte.simulation.exception.ParameterValidationException;
import com.yugabyte.simulation.workload.WorkloadManager;
import com.yugabyte.simulation.workload.WorkloadTypeInstance;

@Service
public class WorkloadOrchestrationService {
    private static final long POLL_INTERVAL_MS = 500L;

    @Autowired
    private WorkloadExecutionService workloadExecutionService;

    @Autowired
    private WorkloadManager workloadManager;

    public PipelineExecutionResult executePipeline(String workloadBean, List<PipelineStepRequest> steps,
            boolean waitForCompletion) throws InterruptedException {
        if (steps == null || steps.isEmpty()) {
            throw new ParameterValidationException("At least one pipeline step is required");
        }

        List<PipelineStepResult> stepResults = new ArrayList<PipelineStepResult>();
        String resolvedBean = workloadBean;

        for (PipelineStepRequest step : steps) {
            if (step.getOperationId() == null || step.getOperationId().trim().isEmpty()) {
                throw new ParameterValidationException("Each pipeline step must include operationId");
            }

            ExecutionResult result = workloadExecutionService.execute(resolvedBean, step.getOperationId(), step.getParameters());
            resolvedBean = result.getWorkloadBean();
            stepResults.add(new PipelineStepResult(step.getOperationId(), result.getResult(), result.getMessage()));

            if (result.getResult() != 0) {
                return buildPipelineResult(resolvedBean, stepResults, result.getResult(), "Pipeline failed at step " + step.getOperationId());
            }

            if (waitForCompletion) {
                waitForRunningWorkloadsToComplete();
            }
        }

        return buildPipelineResult(resolvedBean, stepResults, 0, "Ok");
    }

    private void waitForRunningWorkloadsToComplete() throws InterruptedException {
        while (true) {
            boolean running = false;
            for (WorkloadTypeInstance instance : workloadManager.getActiveWorkloads()) {
                if (instance.getType().canBeTerminated() && !instance.isComplete() && !instance.isTerminated()) {
                    running = true;
                    break;
                }
            }
            if (!running) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private static PipelineExecutionResult buildPipelineResult(String workloadBean, List<PipelineStepResult> steps,
            int overallResult, String message) {
        PipelineExecutionResult result = new PipelineExecutionResult();
        result.setWorkloadBean(workloadBean);
        result.setSteps(steps);
        result.setOverallResult(overallResult);
        result.setMessage(message);
        return result;
    }
}
