package in.manmeet.apexledger.api;

import in.manmeet.apexledger.reconciliation.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping
    public ResponseEntity<ReconciliationRunResponse> create(@Valid @RequestBody ReconciliationRunRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconciliation.create(request.from(), request.to()));
    }

    @GetMapping("/{runId}")
    public ReconciliationRunResponse get(@PathVariable UUID runId) {
        return reconciliation.get(runId);
    }
}
