package in.manmeet.apexledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, UUID> {

    Optional<SettlementRecord> findByExternalReference(String externalReference);

    List<SettlementRecord> findBySettledAtGreaterThanEqualAndSettledAtLessThan(Instant from, Instant to);
}
