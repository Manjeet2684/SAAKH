package in.manmeet.apexledger.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {}

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
