package io.tokenpilot.sample;

import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ObserveLedgerListener implements LedgerListener {

    private static final Logger log = LoggerFactory.getLogger(ObserveLedgerListener.class);
    private final HttpClient httpClient;

    public ObserveLedgerListener() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // 외부에서 직접 호출할 수 있는 수동 전송 메서드 추가
    public void sendUsageLogManually(String modelId, long inputTokens, long outputTokens, double totalCost) {
        String jsonPayload = String.format("""
                {
                    "projectId": "demo-project-id",
                    "organizationId": "demo-org-id",
                    "environment": "demo",
                    "provider": "openai",
                    "model": "%s",
                    "promptTokens": %d,
                    "completionTokens": %d,
                    "cachedPromptTokens": 0,
                    "reasoningTokens": 0,
                    "totalTokens": %d,
                    "promptCostUsd": 0.0018,
                    "completionCostUsd": 0.0017,
                    "cachedPromptCostUsd": 0,
                    "reasoningCostUsd": 0,
                    "totalCostUsd": %.4f,
                    "requestId": "req-%d",
                    "sourceType": "DEMO",
                    "pricingVersion": "openai-2026-08-14",
                    "occurredAt": "2026-10-02T18:35:00"
                }
                """, modelId, inputTokens, outputTokens, (inputTokens + outputTokens), totalCost, System.currentTimeMillis());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/internal/usage-logs"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        log.error("[Observe 통신 실패 - 비차단 확인] {}", throwable.getMessage());
                    } else {
                        log.info("[Observe 전송 성공] Status: {}", response.statusCode());
                    }
                });
    }

    @Override
    public void onRecord(CostRecordedEvent event) {
        // 기존 라이브러리 연동부
        sendUsageLogManually("gpt-4o-mini", 1200, 800, 0.0035);
    }
}