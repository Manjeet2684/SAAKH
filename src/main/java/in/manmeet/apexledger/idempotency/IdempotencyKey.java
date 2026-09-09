package in.manmeet.apexledger.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "http_status")
    private Integer httpStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private JsonNode responseBody;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {}

    public static IdempotencyKey completed(
            String key,
            String requestHash,
            int httpStatus,
            JsonNode responseBody,
            UUID transferId,
            Instant at
    ) {
        IdempotencyKey row = new IdempotencyKey();
        row.idempotencyKey = key;
        row.requestHash = requestHash;
        row.httpStatus = httpStatus;
        row.responseBody = responseBody;
        row.transferId = transferId;
        row.createdAt = at;
        return row;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public JsonNode getResponseBody() {
        return responseBody;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
