package lk.boc.ride;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

    public MatchingWorker(
            JdbcTemplate db,
            StringRedisTemplate redis,
            TransactionTemplate transaction,
            RideUpdates updates
    ) {
        this.db = db;
        this.redis = redis;
        this.transaction = transaction;
        this.updates = updates;
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
        UUID rideId = UUID.fromString(
                new String(
                        message.getBody(),
                        StandardCharsets.UTF_8
                )
        );

        transaction.executeWithoutResult(status -> {
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
                return;
            }

            long cutoff =
                    System.currentTimeMillis() - MAX_LOCATION_AGE_MS;

            Long removed = redis.execute(
                    CLEAN_STALE,
                    List.of("geo:" + city, "seen:" + city),
                    String.valueOf(cutoff)
            );

            log.info(
                    "CLEANUP ride={} city={} removed={}",
                    rideId,
                    city,
                    removed
            );

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

            log.info(
                    "MATCH ride={} city={} lat={} lon={} candidates={}",
                    rideId,
                    city,
                    lat,
                    lon,
                    nearby == null
                            ? 0
                            : nearby.getContent().size()
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

                    long now = System.currentTimeMillis();
                    long currentCutoff = now - MAX_LOCATION_AGE_MS;

                    log.info(
                            "CANDIDATE ride={} driver={} ageMs={}",
                            rideId,
                            driver,
                            lastSeen == null
                                    ? "missing"
                                    : Long.toString(
                                            now - lastSeen.longValue()
                                    )
                    );

                    if (lastSeen == null
                            || lastSeen <= currentCutoff) {
                        log.info(
                                "SKIP stale location ride={} driver={}",
                                rideId,
                                driver
                        );
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

                    log.info(
                            "RESERVATION ride={} driver={} inserted={}",
                            rideId,
                            driver,
                            reserved
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

                log.info(
                        "No available driver for ride {}",
                        rideId
                );
            } else {
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
            }
        });

        updates.notifyRide(rideId);
    }
}