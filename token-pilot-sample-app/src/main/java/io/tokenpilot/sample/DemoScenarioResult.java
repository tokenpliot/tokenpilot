package io.tokenpilot.sample;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Single demo scenario result that can be read directly on screen. */
public record DemoScenarioResult(
        String scenario,
        String status,
        String summary,
        Map<String, Object> evidence
) {

    public DemoScenarioResult {
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
