package com.moveinsync.gps.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token authentication. The TENANT comes only from the token, never from the URL or body, so a client
 * cannot ask for another tenant's data. Tokens are compared as SHA-256 digests in constant time.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public record Principal(String tenant, AuthProperties.Role role, String subject) {}
    static final String ATTR = "principal";
    private final AuthProperties props;
    AuthFilter(AuthProperties props) { this.props = props; }

    @Override protected boolean shouldNotFilter(HttpServletRequest r) { return !r.getRequestURI().startsWith("/api/"); }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            byte[] given = sha(h.substring(7).trim());
            Principal found = null;
            for (AuthProperties.Entry e : props.tokens())
                if (MessageDigest.isEqual(given, sha(e.token()))) found = new Principal(e.tenant(), e.role(), e.subject());
            if (found != null) { req.setAttribute(ATTR, found); chain.doFilter(req, res); return; }
        }
        res.setStatus(401); res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Missing or invalid credentials\"}");
    }
    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
