package in.manmeet.apexledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Copies unpublished outbox rows to Kafka after the posting transaction has committed.
 * The DB transaction stays open across Kafka I/O on purpose (no lease columns in Phase 3).
 */
@Service
@ConditionalOnProperty(prefix = "saakh.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final OutboxProperties properties;
    private final ObjectProvider<OutboxPublisher> self;

    public OutboxPublisher(
            OutboxEventRepository outboxEvents,
            KafkaEventPublisher kafkaEventPublisher,
            OutboxProperties properties,
            ObjectProvider<OutboxPublisher> self
    ) {
        this.outboxEvents = outboxEvents;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.properties = properties;
        this.self = self;
    }

    @Scheduled(
            initialDelayString = "${saakh.outbox.poll-delay-ms:500}",
            fixedDelayString = "${saakh.outbox.poll-delay-ms:500}"
    )
    public void scheduledPoll() {
        self.getObject().publishBatch();
    }

    @Transactional
    public int publishBatch() {
        List<OutboxEvent> claimed = outboxEvents.claimUnpublished(properties.getBatchSize());
        Instant now = Instant.now();
        for (OutboxEvent event : claimed) {
            try {
                kafkaEventPublisher.publish(event);
                event.markPublished(now);
                outboxEvents.save(event);
            } catch (RuntimeException ex) {
                log.error("Failed to publish outbox event {}", event.getEventId(), ex);
                throw ex;
            }
        }
        return claimed.size();
    }
}
