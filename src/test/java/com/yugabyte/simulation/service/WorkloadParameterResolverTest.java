package com.yugabyte.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.yugabyte.simulation.dao.ParamValue;
import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.exception.ParameterValidationException;

class WorkloadParameterResolverTest {
    private final WorkloadParameterResolver resolver = new WorkloadParameterResolver();

    @Test
    void appliesDefaultsAndNamedParameters() {
        WorkloadDesc operation = new WorkloadDesc(
                "SEED_DATA",
                "Seed Data",
                new WorkloadParamDesc("Number of accounts", 1, Integer.MAX_VALUE, 10000),
                new WorkloadParamDesc("Threads", 1, 500, 32)
        );

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("Number of accounts", 5000);

        ParamValue[] resolved = resolver.resolve(operation, params);
        assertEquals(5000, resolved[0].getIntValue());
        assertEquals(32, resolved[1].getIntValue());
    }

    @Test
    void rejectsOutOfRangeValues() {
        WorkloadDesc operation = new WorkloadDesc(
                "RUN_SIMULATION",
                "Run Simulation",
                new WorkloadParamDesc("Threads", 1, 500, 32)
        );

        Map<String, Object> params = Collections.singletonMap("Threads", (Object) 1000);
        assertThrows(ParameterValidationException.class, () -> resolver.resolve(operation, params));
    }
}
