package nic.meg.mcap.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class WebhookBodyCachingFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH = "/webhook/razorpay";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(WEBHOOK_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Read body once, cache it, allow unlimited re-reads downstream
        byte[] body = request.getInputStream().readAllBytes();
        filterChain.doFilter(new CachedBodyRequestWrapper(request, body), response);
    }

    // ── Inner wrapper — serves cached bytes on every getInputStream() call ──
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.cachedBody = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override public int read() throws IOException { return bais.read(); }
                @Override public boolean isFinished()          { return bais.available() == 0; }
                @Override public boolean isReady()             { return true; }
                @Override public void setReadListener(ReadListener rl) {}
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}