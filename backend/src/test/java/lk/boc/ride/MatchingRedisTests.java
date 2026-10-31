package lk.boc.ride;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class MatchingRedisTests {
    @Autowired private JdbcTemplate db;
    @Autowired private TransactionTemplate transaction;
    @Autowired private StringRedisTemplate applicationRedis;
    private StringRedisTemplate redis;
    private MatchingWorker worker;
    private RideUpdates updates;
    private String driver;

    @BeforeEach
    void setUp() {
        String prefix = "{matching-test-" + UUID.randomUUID() + "}:";
        redis = new StringRedisTemplate(applicationRedis.getConnectionFactory());
        // Prefix only Redis keys; leave members and Lua arguments unchanged.
        redis.setKeySerializer(new RedisSerializer<String>() {
            @Override
            public byte[] serialize(String value) {
                return value == null ? null
                        : (prefix + value).getBytes(StandardCharsets.UTF_8);
            }
            @Override
            public String deserialize(byte[] bytes) {
                if (bytes == null) return null;
                String value = new String(bytes, StandardCharsets.UTF_8);
                return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
            }
        });
        redis.afterPropertiesSet();
        updates = mock(RideUpdates.class);
        worker = new MatchingWorker(db, redis, transaction, updates);
        driver = "redis-test-driver-" + UUID.randomUUID();
    }

    private UUID insertRide(String city) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO rides (
                    id, rider_id, request_key, city, lat, lon, request_fingerprint
                ) VALUES (?, ?, ?, ?, 6.928, 79.862,
                    ride_request_fingerprint(CAST(? AS TEXT),
                        CAST(6.928 AS DOUBLE PRECISION),
                        CAST(79.862 AS DOUBLE PRECISION)))
                """, id, "redis-test-rider-" + UUID.randomUUID(),
                UUID.randomUUID().toString(), city, city);
        return id;
    }

    private void grantDriver(String city) {
        db.update("""
                INSERT INTO account_roles (firebase_uid, role, driver_city)
                VALUES (?, 'DRIVER', ?)
                """, driver, city);
    }

    private void storeDriver(String city, double longitude, double latitude, long lastSeen) {
        redis.opsForGeo().add("geo:" + city, new Point(longitude, latitude), driver);
        redis.opsForZSet().add("seen:" + city, driver, lastSeen);
        redis.expire("geo:" + city, Duration.ofMinutes(5));
        redis.expire("seen:" + city, Duration.ofMinutes(5));
    }

    private void storeFreshNearbyDriver() {
        storeDriver("colombo", 79.8612, 6.9271, System.currentTimeMillis());
    }

    private void processColombo(UUID id) {
        worker.consumeColombo(new Message(id.toString().getBytes(StandardCharsets.UTF_8),
                new MessageProperties()));
    }

    private void assertNoDriver(UUID id) {
        var ride = db.queryForMap("SELECT status, driver_id FROM rides WHERE id = ?", id);
        assertEquals("NO_DRIVER", ride.get("status"));
        assertNull(ride.get("driver_id"));
        assertEquals(0L, db.queryForObject(
                "SELECT COUNT(*) FROM assignments WHERE ride_id = ?", Long.class, id));
    }

    @Test
    void freshNearbyDriverIsMatched() {
        grantDriver("colombo");
        UUID id = insertRide("colombo");
        storeFreshNearbyDriver();
        processColombo(id);
        var ride = db.queryForMap("SELECT status, driver_id FROM rides WHERE id = ?", id);
        assertEquals("MATCHED", ride.get("status"));
        assertEquals(driver, ride.get("driver_id"));
        assertEquals(id, db.queryForObject(
                "SELECT ride_id FROM assignments WHERE driver_id = ?", UUID.class, driver));
        verify(updates).notifyRide(id);
    }

    @Test
    void staleDriverIsRejectedAndRemovedFromRedis() {
        grantDriver("colombo");
        UUID id = insertRide("colombo");
        storeDriver("colombo", 79.8612, 6.9271, System.currentTimeMillis() - 60_000);
        processColombo(id);
        assertNoDriver(id);
        assertNull(redis.opsForZSet().score("geo:colombo", driver));
        assertNull(redis.opsForZSet().score("seen:colombo", driver));
    }

    @Test
    void freshDriverOutsideSearchRadiusIsNotMatched() {
        grantDriver("colombo");
        UUID id = insertRide("colombo");
        storeDriver("colombo", 80.6386, 7.2906, System.currentTimeMillis());
        processColombo(id);
        assertNoDriver(id);
        assertNotNull(redis.opsForZSet().score("seen:colombo", driver));
    }

    @Test
    void driverInAnotherCityIsNotMatchedEvenAtSameCoordinates() {
        grantDriver("kandy");
        UUID id = insertRide("colombo");
        storeDriver("kandy", 79.8612, 6.9271, System.currentTimeMillis());
        processColombo(id);
        assertNoDriver(id);
        assertNotNull(redis.opsForZSet().score("geo:kandy", driver));
    }

    @Test
    void unprovisionedDriverInRedisIsNotMatched() {
        UUID id = insertRide("colombo");
        storeFreshNearbyDriver();
        processColombo(id);
        assertNoDriver(id);
    }

    @Test
    void riderInRedisIsNotMatched() {
        db.update("INSERT INTO account_roles (firebase_uid, role) VALUES (?, 'RIDER')", driver);
        UUID id = insertRide("colombo");
        storeFreshNearbyDriver();
        processColombo(id);
        assertNoDriver(id);
    }

    @Test
    void disabledDriverWithCachedLocationIsNotMatched() {
        grantDriver("colombo");
        UUID id = insertRide("colombo");
        storeFreshNearbyDriver();
        db.update("UPDATE account_roles SET enabled = FALSE WHERE firebase_uid = ?", driver);
        processColombo(id);
        assertNoDriver(id);
    }

    @Test
    void driverAuthorizedForKandyCannotMatchColomboFromCachedLocation() {
        grantDriver("kandy");
        UUID id = insertRide("colombo");
        // Deliberately simulate a leftover location in the wrong city key.
        storeFreshNearbyDriver();
        processColombo(id);
        assertNoDriver(id);
    }

    @AfterEach
    void cleanUpRedis() {
        if (redis != null) redis.delete(List.of(
                "geo:colombo", "seen:colombo", "geo:kandy", "seen:kandy"));
        // The test transaction rolls back its SQL fixtures, including account_roles.
    }
}
