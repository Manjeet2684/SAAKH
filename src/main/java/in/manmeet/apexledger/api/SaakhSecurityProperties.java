package in.manmeet.apexledger.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saakh.security")
public class SaakhSecurityProperties {

    /**
     * Required value of header X-API-Key for /v1/**.
     */
    private String apiKey = "local-dev-key";

    /**
     * Required value of header X-API-Key for /internal/**.
     */
    private String internalApiKey = "local-internal-key";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }
}
