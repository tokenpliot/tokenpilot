package io.tokenpilot.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Spring AI configuration for running the sample app in the demo profile without an API key. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {

    @Bean
    DemoChatModel demoChatModel() {
        return new DemoChatModel();
    }

    @Bean
    ChatClient.Builder demoChatClientBuilder(
            ChatModel chatModel,
            ObjectProvider<ChatClientBuilderCustomizer> customizers
    ) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        customizers.orderedStream()
                .forEach(customizer -> customizer.customize(builder));
        return builder;
    }
}
