package lk.boc.ride;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MatchingWorker {

    private static final Logger log =
            LoggerFactory.getLogger(MatchingWorker.class);

    private static final long MAX_LOCATION_AGE_MS = 30_000;

    private static final DefaultRedisScript<Long> CLEAN_STALE =
            new DefaultRedisScript<>("""
                    local stale = redis.call(
                        'ZRANGEBYSCORE',
                        KEYS[2],
                        '-inf',
                        ARGV[1]
                    )

                    for _, driver in ipairs(stale) do
                        redis.call('ZREM', KEYS[1], driver)
                        redis.call('ZREM', KEYS[2], driver)
                    end

                    return #stale
                    """, Long.class);

    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    private final RideUpdates updates;
    private final MeterRegistry registry;

    // Used by existing tests that construct a worker directly.
    public MatchingWorker(
            JdbcTemplate db,
            StringRedisTemplate redis,
            TransactionTemplate transaction,
            RideUpdates updates
    ) {
        this(
                db,
                redis,
                transaction,
                updates,
                new SimpleMeterRegistry()
        );
    }

    // Spring injects the application's metrics registry here.
    @Autowired
    public MatchingWorker(
            JdbcTemplate db,
            StringRedisTemplate redis,
            TransactionTemplate transaction,
            RideUpdates updates,
            MeterRegistry registry
    ) {
        this.db = db;
        this.redis = redis;
        this.transaction = transaction;
        this.updates = updates;
        this.registry = registry;

        // Register zero-valued series before any rides arrive.
        for (String city : List.of("colombo", "kandy")) {
            staleCounter(city);

            for (String outcome : List.of(
                    "MATCHED", "NO_DRIVER", "SKIPPED", "ERROR"
            )) {
                processingTimer(city, outcome);
            }
        }
    }

    private Counter staleCounter(String city) {
        return Counter.builder("rideflow.locations.stale.removed")
                .description("Stale driver locations removed from Redis")
                .tag("city", city)
                .register(registry);
    }

    private Timer processingTimer(String city, String outcome) {
        return Timer.builder("rideflow.matching.duration")
                .description(
                        "Worker processing time, excluding queue waiting time"
                )
                .tag("city", city)
                .tag("outcome", outcome)
                .register(registry);
    }

    @RabbitListener(
            queues = "rides.colombo",
            concurrency = "2"
    )
    public void consumeColombo(Message message) {
        process(message, "colombo");
    }

    @RabbitListener(
            queues = "rides.kandy",
            concurrency = "2"
    )
    public void consumeKandy(Message message) {
        process(message, "kandy");
    }

    private void process(Message message, String expectedCity) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "ERROR";

        try {
            UUID rideId = UUID.fromString(
                    new String(
                            message.getBody(),
                            StandardCharsets.UTF_8
                    )
            );

            String result = transaction.execute(status -> {
                Map<String, Object> ride = db.queryForMap("""
                        SELECT *
                        FROM rides
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        rideId
                );

                String city = ride.get("city").toString();

                if (!expectedCity.equals(city)) {
                    throw new IllegalStateException(
                            "Ride received on incorrect city queue"
                    );
                }

                if (!"QUEUED".equals(ride.get("status"))) {
                    log.info(
                            "Skipping already processed ride {} status={}",
                            rideId,
                            ride.get("status")
                    );

                    return "SKIPPED";
                }

                long cutoff =
                        System.currentTimeMillis() - MAX_LOCATION_AGE_MS;

                Long removed = redis.execute(
                        CLEAN_STALE,
                        List.of("geo:" + city, "seen:" + city),
                        String.valueOf(cutoff)
                );

                if (removed != null && removed > 0) {
                    staleCounter(city).increment(removed.doubleValue());
                }

                double lat =
                        ((Number) ride.get("lat")).doubleValue();

                double lon =
                        ((Number) ride.get("lon")).doubleValue();

                var nearby = redis.opsForGeo().radius(
                        "geo:" + city,
                        new Circle(
                                new Point(lon, lat),
                                new Distance(5, Metrics.KILOMETERS)
                        ),
                        RedisGeoCommands.GeoRadiusCommandArgs
                                .newGeoRadiusArgs()
                                .sortAscending()
                                .limit(100)
                );

                String selectedDriver = null;

                if (nearby != null) {
                    for (var candidate : nearby) {
                        String driver =
                                candidate.getContent().getName();

                        Double lastSeen = redis.opsForZSet().score(
                                "seen:" + city,
                                driver
                        );

                        long currentCutoff =
                                System.currentTimeMillis()
                                        - MAX_LOCATION_AGE_MS;

                        if (lastSeen == null
                                || lastSeen <= currentCutoff) {
                            continue;
                        }

                        int reserved = db.update("""
                                INSERT INTO assignments (
                                    driver_id,
                                    ride_id
                                )
                                VALUES (?, ?)
                                ON CONFLICT DO NOTHING
                                """,
                                driver,
                                rideId
                        );

                        if (reserved == 1) {
                            selectedDriver = driver;
                            break;
                        }
                    }
                }

                if (selectedDriver == null) {
                    db.update("""
                            UPDATE rides
                            SET status = 'NO_DRIVER'
                            WHERE id = ?
                            """,
                            rideId
                    );

                    log.info("No available driver for ride {}", rideId);

                    return "NO_DRIVER";
                }

                db.update("""
                        UPDATE rides
                        SET status = 'MATCHED',
                            driver_id = ?
                        WHERE id = ?
                        """,
                        selectedDriver,
                        rideId
                );

                log.info(
                        "Ride {} assigned to {}",
                        rideId,
                        selectedDriver
                );

                return "MATCHED";
            });

            // TransactionTemplate returns after successful commit.
            outcome = result == null ? "ERROR" : result;

            updates.notifyRide(rideId);
        } finally {
            sample.stop(processingTimer(expectedCity, outcome));
        }
    }
}