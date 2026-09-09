package in.manmeet.apexledger.api;

import in.manmeet.apexledger.account.Account;
import in.manmeet.apexledger.account.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only account lookup so Phase 1 seed data can be verified.
 * Transfer posting is Phase 2 (`POST /api/v1/transfers`).
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<?> get(@PathVariable UUID accountId) {
        return accounts.findById(accountId)
                .<ResponseEntity<?>>map(this::toResponse)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("ACCOUNT_NOT_FOUND", "No account with id " + accountId)));
    }

    private ResponseEntity<AccountResponse> toResponse(Account account) {
        return ResponseEntity.ok(new AccountResponse(
                account.getId(),
                account.getName(),
                account.getKind().name(),
                account.getStatus().name(),
                account.getCurrency(),
                account.getAvailableBalanceMinor()
        ));
    }
}
