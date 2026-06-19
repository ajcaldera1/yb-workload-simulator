package com.yugabyte.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.exception.OperationNotFoundException;
import com.yugabyte.simulation.exception.WorkloadNotFoundException;

@Service
public class WorkloadRegistryService {
    private final ApplicationContext applicationContext;

    public WorkloadRegistryService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<String> getWorkloadBeanNames() {
        List<String> names = new ArrayList<String>(getWorkloadBeans().keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public WorkloadSimulation getWorkload(String beanName) {
        WorkloadSimulation workload = findWorkloadBean(beanName);
        if (workload == null) {
            throw new WorkloadNotFoundException(beanName);
        }
        return workload;
    }

    public String resolveBeanName(String beanName) {
        String resolved = findBeanName(beanName);
        if (resolved == null) {
            throw new WorkloadNotFoundException(beanName);
        }
        return resolved;
    }

    public List<WorkloadDesc> getOperations(String beanName) {
        return getWorkload(beanName).getWorkloads();
    }

    public WorkloadDesc getOperation(String beanName, String operationId) {
        for (WorkloadDesc operation : getOperations(beanName)) {
            if (operation.getWorkloadId().equals(operationId)) {
                return operation;
            }
        }
        throw new OperationNotFoundException(beanName, operationId);
    }

    private Map<String, WorkloadSimulation> getWorkloadBeans() {
        return applicationContext.getBeansOfType(WorkloadSimulation.class);
    }

    private WorkloadSimulation findWorkloadBean(String beanName) {
        Map<String, WorkloadSimulation> beans = getWorkloadBeans();
        if (beans.containsKey(beanName)) {
            return beans.get(beanName);
        }
        for (Map.Entry<String, WorkloadSimulation> entry : beans.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(beanName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String findBeanName(String beanName) {
        Map<String, WorkloadSimulation> beans = getWorkloadBeans();
        if (beans.containsKey(beanName)) {
            return beanName;
        }
        for (String name : beans.keySet()) {
            if (name.equalsIgnoreCase(beanName)) {
                return name;
            }
        }
        return null;
    }
}
