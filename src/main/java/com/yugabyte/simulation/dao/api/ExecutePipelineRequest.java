package com.yugabyte.simulation.dao.api;

import java.util.List;

public class ExecutePipelineRequest {
    private boolean waitForCompletion;
    private List<PipelineStepRequest> steps;

    public boolean isWaitForCompletion() {
        return waitForCompletion;
    }

    public void setWaitForCompletion(boolean waitForCompletion) {
        this.waitForCompletion = waitForCompletion;
    }

    public List<PipelineStepRequest> getSteps() {
        return steps;
    }

    public void setSteps(List<PipelineStepRequest> steps) {
        this.steps = steps;
    }
}
