package in.manmeet.apexledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByAggregateIdAndEventType(UUID aggregateId, String eventType);

    List<OutboxEvent> findByAggregateIdAndEventType(UUID aggregateId, String eventType);
}
