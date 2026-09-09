package in.manmeet.apexledger.api;

import in.manmeet.apexledger.transfer.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> post(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferResponse body = transfers.post(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
