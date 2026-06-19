package com.yugabyte.simulation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.yugabyte.simulation.dao.ParamType;
import com.yugabyte.simulation.dao.ParamValue;
import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.exception.ParameterValidationException;

@Service
public class WorkloadParameterResolver {

    public ParamValue[] resolve(WorkloadDesc operation, Map<String, Object> parameters) {
        List<WorkloadParamDesc> paramDescs = operation.getParams();
        Map<String, Object> provided = parameters == null ? new HashMap<String, Object>() : parameters;
        Map<String, String> fieldErrors = new LinkedHashMap<String, String>();
        ParamValue[] values = new ParamValue[paramDescs.size()];

        for (int i = 0; i < paramDescs.size(); i++) {
            WorkloadParamDesc paramDesc = paramDescs.get(i);
            Object rawValue = provided.get(paramDesc.getName());
            try {
                values[i] = toParamValue(paramDesc, rawValue);
            } catch (ParameterValidationException e) {
                fieldErrors.put(paramDesc.getName(), e.getMessage());
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new ParameterValidationException("Invalid workload parameters", fieldErrors);
        }
        return values;
    }

    public Map<String, Object> toParameterMap(WorkloadDesc operation, ParamValue[] params) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        List<WorkloadParamDesc> paramDescs = operation.getParams();
        for (int i = 0; i < paramDescs.size(); i++) {
            map.put(paramDescs.get(i).getName(), fromParamValue(params[i]));
        }
        return map;
    }

    private ParamValue toParamValue(WorkloadParamDesc paramDesc, Object rawValue) {
        if (rawValue == null || (rawValue instanceof String && ((String) rawValue).isEmpty())) {
            if (paramDesc.getDefaultValue() != null) {
                return paramDesc.getDefaultValue();
            }
            throw new ParameterValidationException("Missing required parameter");
        }

        switch (paramDesc.getType()) {
            case BOOLEAN:
                return new ParamValue(toBoolean(rawValue));
            case NUMBER:
                int intValue = toInt(rawValue);
                validateNumberRange(paramDesc, intValue);
                return new ParamValue(intValue);
            case STRING:
                String stringValue = rawValue.toString();
                validateChoice(paramDesc, stringValue);
                return new ParamValue(stringValue);
            default:
                throw new ParameterValidationException("Unsupported parameter type");
        }
    }

    private static void validateNumberRange(WorkloadParamDesc paramDesc, int value) {
        if (paramDesc.getMinValue() != Integer.MIN_VALUE && value < paramDesc.getMinValue()) {
            throw new ParameterValidationException("Value must be >= " + paramDesc.getMinValue());
        }
        if (paramDesc.getMaxValue() != Integer.MAX_VALUE && value > paramDesc.getMaxValue()) {
            throw new ParameterValidationException("Value must be <= " + paramDesc.getMaxValue());
        }
    }

    private static void validateChoice(WorkloadParamDesc paramDesc, String value) {
        String[] choices = paramDesc.getChoices();
        if (choices == null) {
            return;
        }
        for (String choice : choices) {
            if (choice.equals(value)) {
                return;
            }
        }
        throw new ParameterValidationException("Value must be one of the allowed choices");
    }

    private static boolean toBoolean(Object rawValue) {
        if (rawValue instanceof Boolean) {
            return ((Boolean) rawValue).booleanValue();
        }
        return Boolean.parseBoolean(rawValue.toString());
    }

    private static int toInt(Object rawValue) {
        if (rawValue instanceof Number) {
            return ((Number) rawValue).intValue();
        }
        try {
            return Integer.parseInt(rawValue.toString());
        } catch (NumberFormatException e) {
            throw new ParameterValidationException("Value must be a number");
        }
    }

    private static Object fromParamValue(ParamValue value) {
        if (value == null) {
            return null;
        }
        ParamType type = value.getType();
        switch (type) {
            case BOOLEAN:
                return value.getBoolValue();
            case NUMBER:
                return value.getIntValue();
            case STRING:
                return value.getStringValue();
            default:
                return null;
        }
    }
}
