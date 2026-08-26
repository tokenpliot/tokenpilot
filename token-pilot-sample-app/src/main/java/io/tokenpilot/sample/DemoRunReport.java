package io.tokenpilot.sample;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** 모든 핵심 control/accounting 시나리오를 한 번에 실행한 demo 결과입니다. */
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
