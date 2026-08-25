package io.tokenpilot.autoconfigure;

import io.tokenpilot.notification.BudgetNotificationHandler;
import io.tokenpilot.notification.BudgetNotificationLifecycleListener;
import io.tokenpilot.notification.BudgetNotificationService;
import io.tokenpilot.notification.InMemoryNotificationStateStore;
import io.tokenpilot.notification.NotificationStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Optional notification module의 bean graph를 격리하는 자동 설정입니다. */
@AutoConfiguration(after = {
        TokenPilotAutoConfiguration.class,
        TokenPilotNotificationMetricsAutoConfiguration.class
})
@ConditionalOnClass(NotificationStateStore.class)
@ConditionalOnProperty(
        prefix = "token-pilot",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "token-pilot.notification",
        name = "enabled",
        havingValue = "true"
)
public class TokenPilotNotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationStateStore notificationStateStore() {
        return new InMemoryNotificationStateStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(BudgetNotificationHandler.class)
    public BudgetNotificationService budgetNotificationService(
            BudgetNotificationHandler handler,
            NotificationStateStore notificationStateStore,
            ObjectProvider<BudgetNotificationLifecycleListener> lifecycleListeners
    ) {
        return new BudgetNotificationService(
                handler,
                notificationStateStore,
                lifecycleListeners.orderedStream().toList()
        );
    }
}
