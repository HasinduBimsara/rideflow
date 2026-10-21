package lk.boc.ride;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final JdbcTemplate db;
private final RideUpdates updates;

public RideController(
        JdbcTemplate db,
        RideUpdates updates
) {
    this.db = db;
    this.updates = updates;
}

    public record RideRequest(
            String city,
            Double lat,
            Double lon
    ) {}

    private void validateIdentifier(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identifier must contain 1–100 letters, numbers, underscores or hyphens"
            );
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Object> create(
            @RequestHeader("X-Demo-User") String rider,
            @RequestHeader("Idempotency-Key") String requestKey,
            @RequestBody RideRequest request
    ) {
        validateIdentifier(rider);
        validateIdentifier(requestKey);

        if (request == null
                || request.city() == null
                || !Set.of("colombo", "kandy").contains(request.city())
                || request.lat() == null
                || request.lon() == null
                || !Double.isFinite(request.lat())
                || !Double.isFinite(request.lon())
                || Math.abs(request.lat()) > 85
                || Math.abs(request.lon()) > 180) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Valid city, latitude and longitude are required"
            );
        }

        UUID id = UUID.randomUUID();

        int inserted = db.update("""
                INSERT INTO rides (
                    id, rider_id, request_key, city, lat, lon
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (rider_id, request_key) DO NOTHING
                """,
                id,
                rider,
                requestKey,
                request.city(),
                request.lat(),
                request.lon()
        );

        Map<String, Object> ride = db.queryForMap("""
                SELECT * FROM rides
                WHERE rider_id = ? AND request_key = ?
                """,
                rider,
                requestKey
        );

        if (inserted == 0) {
            boolean sameRequest =
                    request.city().equals(ride.get("city"))
                    && ride.get("lat") instanceof Number
                    && ride.get("lon") instanceof Number
                    && Double.compare(
                            ((Number) ride.get("lat")).doubleValue(),
                            request.lat()
                    ) == 0
                    && Double.compare(
                            ((Number) ride.get("lon")).doubleValue(),
                            request.lon()
                    ) == 0;

            if (!sameRequest) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This key belongs to a different request"
                );
            }
        } else {
            db.update("""
                    INSERT INTO outbox (ride_id, city)
                    VALUES (?, ?)
                    """,
                    id,
                    request.city()
            );
        }

        return ride;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Demo-User") String rider
    ) {
        validateIdentifier(rider);

        List<Map<String, Object>> rows = db.queryForList("""
                SELECT * FROM rides
                WHERE id = ? AND rider_id = ?
                """,
                id,
                rider
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Ride not found"
            );
        }

        return rows.get(0);
    }
    @PostMapping("/{id}/complete")
@Transactional
public Map<String, Object> complete(
        @PathVariable("id") UUID id,
        @RequestHeader("X-Demo-User") String rider
) {
    validateIdentifier(rider);

    List<Map<String, Object>> rows = db.queryForList("""
            SELECT *
            FROM rides
            WHERE id = ? AND rider_id = ?
            FOR UPDATE
            """,
            id,
            rider
    );

    if (rows.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ride not found"
        );
    }

    Map<String, Object> ride = rows.get(0);

    if ("COMPLETED".equals(ride.get("status"))) {
        return ride;
    }

    if (!"MATCHED".equals(ride.get("status"))) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only a matched ride can be completed"
        );
    }

    db.update("""
            INSERT INTO trip_history (
                ride_id, city, pickup_area
            )
            SELECT id, city,
                ST_MakeEnvelope(
                    FLOOR(lon / 0.02) * 0.02,
                    FLOOR(lat / 0.02) * 0.02,
                    FLOOR(lon / 0.02) * 0.02 + 0.02,
                    FLOOR(lat / 0.02) * 0.02 + 0.02,
                    4326
                )
            FROM rides
            WHERE id = ?
            ON CONFLICT (ride_id) DO NOTHING
            """,
            id
    );

    db.update(
            "DELETE FROM assignments WHERE ride_id = ?",
            id
    );

    db.update("""
            UPDATE rides
            SET status = 'COMPLETED'
            WHERE id = ?
            """,
            id
    );

    TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    updates.notifyRide(id);
                }
            }
    );

    return db.queryForMap(
            "SELECT * FROM rides WHERE id = ?",
            id
    );
}
@PostMapping("/{id}/pay")
@Transactional
public Map<String, Object> pay(
        @PathVariable("id") UUID id,
        @RequestHeader("X-Demo-User") String rider
) {
    validateIdentifier(rider);

    List<Map<String, Object>> rows = db.queryForList("""
            SELECT *
            FROM rides
            WHERE id = ? AND rider_id = ?
            FOR UPDATE
            """,
            id,
            rider
    );

    if (rows.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ride not found"
        );
    }

    Map<String, Object> ride = rows.get(0);

    if (!"COMPLETED".equals(ride.get("status"))) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Complete the ride before paying"
        );
    }

    String paymentKey = "ride-payment-" + id;

    db.update("""
            INSERT INTO payments (
                ride_id,
                payment_key,
                receipt,
                amount_minor,
                currency,
                status
            )
            VALUES (?, ?, ?, ?, ?, 'MOCK_SUCCEEDED')
            ON CONFLICT (ride_id) DO NOTHING
            """,
            id,
            paymentKey,
            UUID.randomUUID(),
            ride.get("fare_minor"),
            ride.get("currency")
    );

    return db.queryForMap(
            "SELECT * FROM payments WHERE ride_id = ?",
            id
    );
}
}