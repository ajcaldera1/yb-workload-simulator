package com.yugabyte.simulation.dao.api;

import java.util.List;

public class PipelineExecutionResult {
    private int overallResult;
    private String message;
    private String workloadBean;
    private List<PipelineStepResult> steps;

    public int getOverallResult() {
        return overallResult;
    }

    public void setOverallResult(int overallResult) {
        this.overallResult = overallResult;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getWorkloadBean() {
        return workloadBean;
    }

    public void setWorkloadBean(String workloadBean) {
        this.workloadBean = workloadBean;
    }

    public List<PipelineStepResult> getSteps() {
        return steps;
    }

    public void setSteps(List<PipelineStepResult> steps) {
        this.steps = steps;
    }
}
