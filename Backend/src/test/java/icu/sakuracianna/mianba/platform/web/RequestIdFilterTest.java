package icu.sakuracianna.mianba.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @Test
    void preservesSafeIncomingRequestIdAndAddsItToResponse() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req_client-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (incoming, outgoing) -> assertThat(
                incoming.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("req_client-123");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req_client-123");
    }

    @Test
    void replacesOversizedIncomingRequestIdBeforeControllerAccess() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (incoming, outgoing) -> {
            String requestId = incoming.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
            assertThat(requestId).startsWith("req_").hasSize(36);
            assertThat(requestId).isEqualTo(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
        };

        filter.doFilter(request, response, chain);
    }
}
