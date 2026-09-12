package in.manmeet.apexledger.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Registers the Kafka publisher after {@link KafkaAutoConfiguration} so {@code KafkaTemplate}
 * exists. Scanned {@code @ConditionalOnBean} would miss that bean and skip the publisher.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "saakh.outbox", name = "enabled", havingValue = "true")
@ConditionalOnBean(KafkaTemplate.class)
public class OutboxKafkaAutoConfiguration {

    @Bean
    KafkaEventPublisher kafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxProperties properties
    ) {
        return new SpringKafkaEventPublisher(kafkaTemplate, properties);
    }
}
