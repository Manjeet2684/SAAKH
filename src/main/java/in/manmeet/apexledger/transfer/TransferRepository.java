package in.manmeet.apexledger.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    long countByIdempotencyKey(String idempotencyKey);

    List<Transfer> findByStatus(TransferStatus status);

    List<Transfer> findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            TransferStatus status,
            Instant from,
            Instant to
    );
}
