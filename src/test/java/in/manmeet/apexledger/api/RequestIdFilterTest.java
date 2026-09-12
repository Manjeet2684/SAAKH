package in.manmeet.apexledger.api;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void missingInvalidAndOversizedIdsAreReplacedAndValidIdIsPreserved() throws Exception {
        assertNotNull(RequestIdFilter.resolve(null));
        assertNotEquals("bad id", RequestIdFilter.resolve("bad id"));
        assertNotEquals("a".repeat(129), RequestIdFilter.resolve("a".repeat(129)));
        assertEquals("abc.DEF-12_3", RequestIdFilter.resolve("abc.DEF-12_3"));
    }

    @Test
    void filterSetsHeaderAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/accounts/x");
        request.addHeader(RequestIdFilter.HEADER, "client-req-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest req,
                    jakarta.servlet.ServletResponse res
            ) throws java.io.IOException, jakarta.servlet.ServletException {
                assertEquals("client-req-1", MDC.get(RequestIdFilter.MDC_KEY));
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, response, chain);

        assertEquals("client-req-1", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void generatedIdLooksLikeUuid() {
        String generated = RequestIdFilter.resolve(null);
        assertTrue(generated.matches("[0-9a-fA-F-]{36}"));
    }
}
