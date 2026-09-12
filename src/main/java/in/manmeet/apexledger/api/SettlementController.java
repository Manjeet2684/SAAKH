package in.manmeet.apexledger.api;

import in.manmeet.apexledger.settlement.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/settlements")
public class SettlementController {

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @PostMapping
    public ResponseEntity<SettlementResponse> ingest(@Valid @RequestBody SettlementRequest request) {
        SettlementService.SettlementIngestResult result = settlements.ingest(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
}
