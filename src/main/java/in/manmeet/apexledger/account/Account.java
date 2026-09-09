package in.manmeet.apexledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "available_balance_minor", nullable = false)
    private long availableBalanceMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {}

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountKind getKind() {
        return kind;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAvailableBalanceMinor() {
        return availableBalanceMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
