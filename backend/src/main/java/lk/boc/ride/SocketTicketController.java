package lk.boc.ride;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rides")
public class SocketTicketController {

    private final JdbcTemplate db;
    private final StringRedisTemplate redis;

    public SocketTicketController(
            JdbcTemplate db,
            StringRedisTemplate redis
    ) {
        this.db = db;
        this.redis = redis;
    }

    @PostMapping("/{id}/socket-ticket")
    public ResponseEntity<Map<String, Object>> createTicket(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long count = db.queryForObject("""
                SELECT COUNT(*)
                FROM rides
                WHERE id = ? AND rider_id = ?
                """,
                Long.class,
                id,
                jwt.getSubject()
        );

        if (count == null || count != 1L) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Ride not found"
            );
        }

        String ticket = UUID.randomUUID().toString();

        String key = "ws-ticket:" + id + ":" + ticket;

        redis.opsForValue().set(
                key,
                jwt.getSubject(),
                Duration.ofSeconds(60)
        );

        Map<String, Object> result = Map.of(
                "ticket", ticket,
                "expiresInSeconds", 60
        );

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(result);
    }
}