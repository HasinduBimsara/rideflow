package lk.boc.ride;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class MatchingConcurrencyTests {

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private TransactionTemplate transaction;

    private final List<UUID> testRideIds = new ArrayList<>();

    private ExecutorService executor;

    private UUID insertQueuedRide() {
        UUID id = UUID.randomUUID();
        testRideIds.add(id);

        db.update("""
                INSERT INTO rides (
                    id,
                    rider_id,
                    request_key,
                    city,
                    lat,
                    lon,
                    request_fingerprint
                )
                VALUES (
                    ?, ?, ?, 'colombo', 6.928, 79.862,
                    ride_request_fingerprint(
                        'colombo',
                        CAST(6.928 AS DOUBLE PRECISION),
                        CAST(79.862 AS DOUBLE PRECISION)
                    )
                )
                """,
                id,
                "concurrency-rider-" + UUID.randomUUID(),
                UUID.randomUUID().toString()
        );

        // No outbox event: only this test invokes the workers.
        return id;
    }

    private Message messageFor(UUID rideId) {
        return new Message(
                rideId.toString().getBytes(StandardCharsets.UTF_8),
                new MessageProperties()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void simultaneousRequestsCannotReserveTheSameDriverTwice()
            throws Exception {

        String driver = "concurrency-driver-" + UUID.randomUUID();

        UUID firstRide = insertQueuedRide();
        UUID secondRide = insertQueuedRide();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        GeoOperations<String, String> geo =
                mock(GeoOperations.class);

        org.springframework.data.redis.core.ZSetOperations<String, String>
                seen = mock(
                        org.springframework.data.redis.core
                                .ZSetOperations.class
                );

        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForZSet()).thenReturn(seen);

        var location = new RedisGeoCommands.GeoLocation<>(
                driver,
                new Point(79.8612, 6.9271)
        );

        var candidate = new GeoResult<>(
                location,
                new Distance(0.1336, Metrics.KILOMETERS)
        );

        var nearby = new GeoResults<>(List.of(candidate));

        CountDownLatch bothSearching = new CountDownLatch(2);

        when(geo.radius(
                eq("geo:colombo"),
                any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)
        )).thenAnswer(invocation -> {
            bothSearching.countDown();

            if (!bothSearching.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Both matching workers did not reach candidate search"
                );
            }

            return nearby;
        });

        when(seen.score("seen:colombo", driver))
                .thenAnswer(invocation ->
                        (double) System.currentTimeMillis()
                );

        RideUpdates updates = mock(RideUpdates.class);

        MatchingWorker worker = new MatchingWorker(
                db,
                redis,
                transaction,
                updates
        );

        executor = Executors.newFixedThreadPool(2);

        Future<?> first = executor.submit(() ->
                worker.consumeColombo(messageFor(firstRide))
        );

        Future<?> second = executor.submit(() ->
                worker.consumeColombo(messageFor(secondRide))
        );

        // get() also propagates worker exceptions to the test.
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        List<String> statuses = db.queryForList("""
                SELECT status
                FROM rides
                WHERE id IN (?, ?)
                """,
                String.class,
                firstRide,
                secondRide
        );

        assertEquals(
                1L,
                statuses.stream()
                        .filter("MATCHED"::equals)
                        .count(),
                "Exactly one ride must be matched"
        );

        assertEquals(
                1L,
                statuses.stream()
                        .filter("NO_DRIVER"::equals)
                        .count(),
                "The other ride must not reserve the busy driver"
        );

        assertEquals(
                1L,
                db.queryForObject("""
                        SELECT COUNT(*)
                        FROM assignments
                        WHERE driver_id = ?
                        """,
                        Long.class,
                        driver
                ),
                "The driver must have exactly one assignment"
        );

        UUID matchedRide = db.queryForObject("""
                SELECT id
                FROM rides
                WHERE id IN (?, ?)
                  AND status = 'MATCHED'
                  AND driver_id = ?
                """,
                UUID.class,
                firstRide,
                secondRide,
                driver
        );

        UUID assignedRide = db.queryForObject("""
                SELECT ride_id
                FROM assignments
                WHERE driver_id = ?
                """,
                UUID.class,
                driver
        );

        assertEquals(
                matchedRide,
                assignedRide,
                "The reservation must belong to the matched ride"
        );

        assertEquals(
                0L,
                db.queryForObject("""
                        SELECT COUNT(*)
                        FROM rides
                        WHERE id IN (?, ?)
                          AND status = 'NO_DRIVER'
                          AND driver_id IS NOT NULL
                        """,
                        Long.class,
                        firstRide,
                        secondRide
                )
        );

        verify(updates).notifyRide(firstRide);
        verify(updates).notifyRide(secondRide);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Matching threads are still running; cleanup stopped"
                );
            }
        }

        // Remove only rows created by this test.
        for (UUID id : testRideIds) {
            transaction.executeWithoutResult(status -> {
                db.update(
                        "DELETE FROM assignments WHERE ride_id = ?",
                        id
                );

                db.update(
                        "DELETE FROM rides WHERE id = ?",
                        id
                );
            });
        }
    }
}