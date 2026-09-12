package in.manmeet.apexledger.api;

import in.manmeet.apexledger.observability.SaakhMetrics;
import in.manmeet.apexledger.support.ApiKeyEquals;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Two-audience API-key gate. This is not an authentication product.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final SaakhSecurityProperties properties;
    private final SaakhMetrics metrics;

    public ApiKeyFilter(SaakhSecurityProperties properties, SaakhMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/")
                && !path.startsWith("/api/")
                && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean internal = request.getRequestURI().startsWith("/internal/");
        String audience = internal ? "internal" : "client";
        String expected = internal ? properties.getInternalApiKey() : properties.getApiKey();
        String provided = request.getHeader(HEADER);

        if (provided == null || provided.isBlank()) {
            reject(response, audience, "missing", pathGroup(request.getRequestURI()));
            return;
        }
        if (!ApiKeyEquals.matches(provided, expected)) {
            reject(response, audience, "mismatch", pathGroup(request.getRequestURI()));
            return;
        }

        log.info("Authentication succeeded audience={}", audience);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String audience, String reason, String pathGroup)
            throws IOException {
        metrics.authFailure(pathGroup);
        log.info("Authentication failed audience={} outcome={}", audience, reason);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = requestId == null
                ? """
                {"code":"UNAUTHORIZED","message":"Missing or invalid X-API-Key"}
                """
                : """
                {"code":"UNAUTHORIZED","message":"Missing or invalid X-API-Key","requestId":"%s"}
                """.formatted(requestId);
        response.getWriter().write(body);
    }

    private static String pathGroup(String uri) {
        if (uri.startsWith("/internal/")) {
            return "/internal";
        }
        if (uri.startsWith("/api/")) {
            return "/api";
        }
        if (uri.startsWith("/v1/")) {
            return "/v1";
        }
        return "other";
    }
}
