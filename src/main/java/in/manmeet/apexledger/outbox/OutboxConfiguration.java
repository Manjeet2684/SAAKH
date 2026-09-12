package in.manmeet.apexledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnProperty(prefix = "saakh.outbox", name = "enabled", havingValue = "true")
public class OutboxConfiguration {

    @Bean
    NewTopic saakhTransfersTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.getTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
