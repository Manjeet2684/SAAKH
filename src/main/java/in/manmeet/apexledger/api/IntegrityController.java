package in.manmeet.apexledger.api;

import in.manmeet.apexledger.integrity.FinancialIntegrityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/integrity")
public class IntegrityController {

    private final FinancialIntegrityService integrity;

    public IntegrityController(FinancialIntegrityService integrity) {
        this.integrity = integrity;
    }

    @GetMapping
    public IntegrityResponse check() {
        return integrity.check();
    }
}
