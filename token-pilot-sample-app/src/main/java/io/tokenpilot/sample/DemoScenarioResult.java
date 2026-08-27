package io.tokenpilot.sample;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** 화면에서 바로 읽을 수 있는 단일 demo 시나리오 결과입니다. */
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
