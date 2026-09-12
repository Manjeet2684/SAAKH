package in.manmeet.apexledger.integrity;

import in.manmeet.apexledger.account.Account;
import in.manmeet.apexledger.account.AccountKind;
import in.manmeet.apexledger.account.AccountRepository;
import in.manmeet.apexledger.api.IntegrityCheckStatus;
import in.manmeet.apexledger.api.IntegrityChecksResponse;
import in.manmeet.apexledger.api.IntegrityResponse;
import in.manmeet.apexledger.ledger.LedgerDirection;
import in.manmeet.apexledger.ledger.LedgerLine;
import in.manmeet.apexledger.ledger.LedgerLineRepository;
import in.manmeet.apexledger.transfer.Transfer;
import in.manmeet.apexledger.transfer.TransferRepository;
import in.manmeet.apexledger.transfer.TransferStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FinancialIntegrityService {

    private final TransferRepository transfers;
    private final LedgerLineRepository ledgerLines;
    private final AccountRepository accounts;

    public FinancialIntegrityService(
            TransferRepository transfers,
            LedgerLineRepository ledgerLines,
            AccountRepository accounts
    ) {
        this.transfers = transfers;
        this.ledgerLines = ledgerLines;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public IntegrityResponse check() {
        List<Transfer> completed = transfers.findByStatus(TransferStatus.COMPLETED);
        List<LedgerLine> lines = ledgerLines.findAll();
        Map<UUID, List<LedgerLine>> linesByTransfer = lines.stream().collect(Collectors.groupingBy(LedgerLine::getTransferId));
        Map<UUID, Account> accountsById = accounts.findAll().stream().collect(Collectors.toMap(Account::getId, account -> account));

        IntegrityCheckStatus doubleEntry = IntegrityCheckStatus.PASS;
        IntegrityCheckStatus amount = IntegrityCheckStatus.PASS;
        IntegrityCheckStatus currency = IntegrityCheckStatus.PASS;
        for (Transfer transfer : completed) {
            List<LedgerLine> pair = linesByTransfer.getOrDefault(transfer.getId(), List.of());
            LedgerLine debit = line(pair, LedgerDirection.DEBIT);
            LedgerLine credit = line(pair, LedgerDirection.CREDIT);
            if (debit == null || credit == null || pair.size() != 2) {
                doubleEntry = IntegrityCheckStatus.FAIL;
            }
            if (debit == null || credit == null
                    || debit.getAmountMinor() != transfer.getAmountMinor()
                    || credit.getAmountMinor() != transfer.getAmountMinor()) {
                amount = IntegrityCheckStatus.FAIL;
            }
            Account source = accountsById.get(transfer.getSourceAccountId());
            Account destination = accountsById.get(transfer.getDestinationAccountId());
            if (source == null || destination == null
                    || !transfer.getCurrency().equals(source.getCurrency())
                    || !transfer.getCurrency().equals(destination.getCurrency())) {
                currency = IntegrityCheckStatus.FAIL;
            }
        }

        long debitSum = lines.stream().filter(line -> line.getDirection() == LedgerDirection.DEBIT).mapToLong(LedgerLine::getAmountMinor).sum();
        long creditSum = lines.stream().filter(line -> line.getDirection() == LedgerDirection.CREDIT).mapToLong(LedgerLine::getAmountMinor).sum();
        IntegrityCheckStatus global = debitSum == creditSum ? IntegrityCheckStatus.PASS : IntegrityCheckStatus.FAIL;

        Map<UUID, Long> derived = lines.stream().collect(Collectors.groupingBy(
                LedgerLine::getAccountId,
                Collectors.summingLong(line -> line.getDirection() == LedgerDirection.CREDIT ? line.getAmountMinor() : -line.getAmountMinor())
        ));
        IntegrityCheckStatus customerBalance = IntegrityCheckStatus.PASS;
        boolean hasSystem = false;
        for (Account account : accountsById.values()) {
            if (account.getKind() == AccountKind.SYSTEM) {
                hasSystem = true;
                continue;
            }
            long expected = derived.getOrDefault(account.getId(), 0L);
            if (account.getAvailableBalanceMinor() != expected) {
                customerBalance = IntegrityCheckStatus.FAIL;
            }
        }
        IntegrityCheckStatus systemBalance = hasSystem ? IntegrityCheckStatus.INCONCLUSIVE : IntegrityCheckStatus.PASS;

        IntegrityCheckStatus overall = firstFail(doubleEntry, amount, currency, global, customerBalance);
        return new IntegrityResponse(
                overall,
                new IntegrityChecksResponse(doubleEntry, amount, currency, global, customerBalance, systemBalance)
        );
    }

    private static IntegrityCheckStatus firstFail(IntegrityCheckStatus... checks) {
        for (IntegrityCheckStatus check : checks) {
            if (check == IntegrityCheckStatus.FAIL) {
                return IntegrityCheckStatus.FAIL;
            }
        }
        return IntegrityCheckStatus.PASS;
    }

    private static LedgerLine line(List<LedgerLine> pair, LedgerDirection direction) {
        return pair.stream().filter(line -> line.getDirection() == direction).findFirst().orElse(null);
    }
}
