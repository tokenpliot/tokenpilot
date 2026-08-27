package io.tokenpilot.sample;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Demo result containing all core control/accounting scenarios run together. */
public record DemoRunReport(
        String runId,
        String profile,
        String provider,
        String note,
        List<DemoScenarioResult> scenarios,
        Map<String, Double> metrics
) {

    public DemoRunReport {
        scenarios = List.copyOf(scenarios);
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}
