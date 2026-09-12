package in.manmeet.apexledger.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, UUID> {

    List<ReconciliationResult> findByRunIdOrderByCreatedAtAsc(UUID runId);

    long countByRunId(UUID runId);
}
