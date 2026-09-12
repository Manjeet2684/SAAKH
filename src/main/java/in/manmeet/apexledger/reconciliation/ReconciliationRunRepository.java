package in.manmeet.apexledger.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
