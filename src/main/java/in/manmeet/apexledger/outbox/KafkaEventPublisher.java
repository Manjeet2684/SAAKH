package in.manmeet.apexledger.outbox;

/**
 * Thin port so tests can fail Kafka deterministically. Not a messaging framework.
 */
public interface KafkaEventPublisher {

    void publish(OutboxEvent event);
}
