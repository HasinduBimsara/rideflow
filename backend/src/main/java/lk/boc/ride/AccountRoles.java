package lk.boc.ride;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountRoles {
    private final JdbcTemplate db;

    public AccountRoles(JdbcTemplate db) {
        this.db = db;
    }

    public boolean isRider(String uid) {
        if (uid == null || uid.isBlank()) {
            return false;
        }

        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT COALESCE(
                    (SELECT enabled AND role = 'RIDER'
                     FROM account_roles WHERE firebase_uid = ?),
                    TRUE
                )
                """, Boolean.class, uid));
    }

    public boolean isDriver(String uid, String city) {
        if (uid == null || city == null) {
            return false;
        }

        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM account_roles
                    WHERE firebase_uid = ?
                      AND role = 'DRIVER'
                      AND enabled = TRUE
                      AND driver_city = ?
                )
                """, Boolean.class, uid, city));
    }
}
