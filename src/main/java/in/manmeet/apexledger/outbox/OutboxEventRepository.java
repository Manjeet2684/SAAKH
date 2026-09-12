package in.manmeet.apexledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByAggregateIdAndEventType(UUID aggregateId, String eventType);

    List<OutboxEvent> findByAggregateIdAndEventType(UUID aggregateId, String eventType);

    long countByPublishedAtIsNull();

    @Query(value = """
            SELECT event_id, event_type, aggregate_id, payload, created_at, published_at
            FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);
}
