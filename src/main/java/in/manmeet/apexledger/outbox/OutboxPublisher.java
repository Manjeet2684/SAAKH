package in.manmeet.apexledger.outbox;

import in.manmeet.apexledger.observability.SaakhMetrics;
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
    private final SaakhMetrics metrics;

    public OutboxPublisher(
            OutboxEventRepository outboxEvents,
            KafkaEventPublisher kafkaEventPublisher,
            OutboxProperties properties,
            ObjectProvider<OutboxPublisher> self,
            SaakhMetrics metrics
    ) {
        this.outboxEvents = outboxEvents;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.properties = properties;
        this.self = self;
        this.metrics = metrics;
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
        if (claimed.isEmpty()) {
            log.debug("Outbox batch attempted claimed=0");
        } else {
            log.info("Outbox batch attempted claimed={}", claimed.size());
        }
        Instant now = Instant.now();
        int published = 0;
        for (OutboxEvent event : claimed) {
            try {
                kafkaEventPublisher.publish(event);
                event.markPublished(now);
                outboxEvents.save(event);
                published++;
            } catch (RuntimeException ex) {
                metrics.outboxPublishFailure();
                log.error("Outbox publish failure eventId={} aggregateId={}",
                        event.getEventId(), event.getAggregateId(), ex);
                throw ex;
            }
        }
        if (published > 0) {
            metrics.outboxPublished(published);
            log.info("Outbox published count={}", published);
        }
        metrics.outboxPending(outboxEvents.countByPublishedAtIsNull());
        return claimed.size();
    }
}
