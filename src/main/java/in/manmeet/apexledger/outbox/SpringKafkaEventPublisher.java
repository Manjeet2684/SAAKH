package in.manmeet.apexledger.outbox;

import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class SpringKafkaEventPublisher implements KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    SpringKafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(
                            properties.getTopic(),
                            event.getAggregateId().toString(),
                            event.getPayload().toString()
                    )
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox event " + event.getEventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish outbox event " + event.getEventId(), e);
        }
    }
}
