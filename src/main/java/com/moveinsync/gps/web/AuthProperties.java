package com.moveinsync.gps.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(List<Entry> tokens) {
    public enum Role { EMPLOYEE, OPERATOR, ADMIN }
    public record Entry(String token, String tenant, Role role, String subject) {}
    public AuthProperties { tokens = tokens == null ? List.of() : List.copyOf(tokens); }
}
