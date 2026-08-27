package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.notification.AtomicNotificationStateStore;
import io.tokenpilot.notification.BudgetNotificationErrorHook;
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

/** Auto-configuration isolating the optional notification module bean graph. */
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
    @ConditionalOnMissingBean(NotificationStateStore.class)
    public NotificationStateStore notificationStateStore() {
        return new InMemoryNotificationStateStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({BudgetNotificationHandler.class, BudgetStateStore.class})
    public BudgetNotificationService budgetNotificationService(
            ObjectProvider<BudgetNotificationHandler> handlers,
            NotificationStateStore notificationStateStore,
            BudgetStateStore budgetStateStore,
            TokenPilotProperties properties,
            ObjectProvider<BudgetNotificationErrorHook> errorHook,
            ObjectProvider<BudgetNotificationLifecycleListener> lifecycleListeners
    ) {
        if (!(notificationStateStore instanceof AtomicNotificationStateStore atomicStore)) {
            throw new IllegalStateException(
                    "notification state store must implement AtomicNotificationStateStore"
            );
        }
        var policy = TokenPilotBudgetPolicyFactory.from(properties);
        return new BudgetNotificationService(
                handlers.orderedStream().toList(),
                atomicStore,
                key -> budgetStateStore.snapshot(key, policy.monthlyLimit()),
                errorHook.getIfAvailable(BudgetNotificationErrorHook::noOp),
                lifecycleListeners.orderedStream().toList()
        );
    }
}
