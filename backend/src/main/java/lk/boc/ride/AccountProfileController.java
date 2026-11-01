package lk.boc.ride;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountProfileController {
    private final JdbcTemplate db;

    public AccountProfileController(JdbcTemplate db) {
        this.db = db;
    }

    public record AccountProfile(String uid, String role, boolean enabled, String driverCity) { }

    @GetMapping("/api/auth/account-profile")
    public AccountProfile get(@AuthenticationPrincipal Jwt jwt) {
        var profiles = db.query("""
                SELECT role, enabled, driver_city
                FROM account_roles WHERE firebase_uid = ?
                """, (rs, row) -> new AccountProfile(jwt.getSubject(),
                        rs.getString("role"), rs.getBoolean("enabled"),
                        rs.getString("driver_city")), jwt.getSubject());
        return profiles.isEmpty()
                ? new AccountProfile(jwt.getSubject(), "RIDER", true, null)
                : profiles.get(0);
    }
}
