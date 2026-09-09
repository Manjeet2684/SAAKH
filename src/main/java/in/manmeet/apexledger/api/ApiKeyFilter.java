package in.manmeet.apexledger.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal API-key gate. This is not an authentication product.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final SaakhSecurityProperties properties;

    public ApiKeyFilter(SaakhSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/") && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        String expected = request.getRequestURI().startsWith("/internal/")
                ? properties.getInternalApiKey()
                : properties.getApiKey();

        if (provided == null || !provided.equals(expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("""
                    {"code":"UNAUTHORIZED","message":"Missing or invalid X-API-Key"}
                    """);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
