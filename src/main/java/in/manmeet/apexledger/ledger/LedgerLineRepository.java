package in.manmeet.apexledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerLineRepository extends JpaRepository<LedgerLine, UUID> {

    long countByTransferId(UUID transferId);

    List<LedgerLine> findByTransferId(UUID transferId);
}
