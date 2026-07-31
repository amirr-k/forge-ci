package dev.forgeci.controlplane.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a correlation id (from the caller if it sent one, otherwise generated)
 * and, where the URL carries them, the project/build id — required structured log fields per
 * spec/reference/architecture.md#observability.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    private static final Pattern PROJECT_ID = Pattern.compile("/api/projects/(\\d+)");
    private static final Pattern BUILD_ID = Pattern.compile("/api/builds/(\\d+)");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader(HEADER, correlationId);

        String uri = request.getRequestURI();
        putIfMatches(PROJECT_ID, uri, "projectId");
        putIfMatches(BUILD_ID, uri, "buildId");

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static void putIfMatches(Pattern pattern, String uri, String mdcKey) {
        Matcher matcher = pattern.matcher(uri);
        if (matcher.find()) {
            MDC.put(mdcKey, matcher.group(1));
        }
    }
}
