package lk.boc.ride;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class LocationIngestionTests {

    private static final String GPS =
            "{\"lat\":6.9271,\"lon\":79.8612}";

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private AccountRoles roles;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private StringRedisTemplate applicationRedis;

    private StringRedisTemplate redis;
    private LocationIngestion ingestion;
    private String driver;

    @BeforeEach
    void setUp() {
        driver = "gps-test-" + UUID.randomUUID();

        String prefix =
                "{gps-test-" + UUID.randomUUID() + "}:";

        redis = new StringRedisTemplate(
                applicationRedis.getConnectionFactory()
        );

        // Isolate test keys from application GPS data.
        redis.setKeySerializer(new RedisSerializer<String>() {
            @Override
            public byte[] serialize(String value) {
                if (value == null) {
                    return null;
                }

                return (prefix + value)
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String deserialize(byte[] bytes) {
                if (bytes == null) {
                    return null;
                }

                String value =
                        new String(bytes, StandardCharsets.UTF_8);

                return value.startsWith(prefix)
                        ? value.substring(prefix.length())
                        : value;
            }
        });

        redis.afterPropertiesSet();

        // Manual instance: does not start another MQTT connection.
        ingestion = new LocationIngestion(
                redis,
                mapper,
                "tcp://unused:1883",
                roles
        );
    }

    private void grant(
            String role,
            boolean enabled,
            String city
    ) {
        db.update("""
                INSERT INTO account_roles (
                    firebase_uid,
                    role,
                    enabled,
                    driver_city
                )
                VALUES (?, ?, ?, ?)
                """,
                driver,
                role,
                enabled,
                city
        );
    }

    private void deliver(String body, boolean retained) {
        MqttMessage message = new MqttMessage(
                body.getBytes(StandardCharsets.UTF_8)
        );

        message.setRetained(retained);

        ReflectionTestUtils.invokeMethod(
                ingestion,
                "processLocation",
                "drivers/colombo/" + driver + "/location",
                message
        );
    }

    private void assertNotStored() {
        assertNull(
                redis.opsForZSet().score("geo:colombo", driver)
        );

        assertNull(
                redis.opsForZSet().score("seen:colombo", driver)
        );
    }

    @Test
    void authorizedDriverLocationReachesRedis() {
        grant("DRIVER", true, "colombo");

        long before = System.currentTimeMillis();

        deliver(GPS, false);

        var points = redis.opsForGeo().position(
                "geo:colombo",
                driver
        );

        assertNotNull(points);
        assertEquals(1, points.size());
        assertNotNull(points.get(0));

        assertEquals(
                79.8612,
                points.get(0).getX(),
                0.00001
        );

        assertEquals(
                6.9271,
                points.get(0).getY(),
                0.00001
        );

        Double seen = redis.opsForZSet().score(
                "seen:colombo",
                driver
        );

        assertNotNull(seen);
        assertTrue(seen >= before);
        assertTrue(seen <= System.currentTimeMillis());

        Long geoTtl = redis.getExpire("geo:colombo");
        Long seenTtl = redis.getExpire("seen:colombo");

        assertNotNull(geoTtl);
        assertNotNull(seenTtl);
        assertTrue(geoTtl > 0 && geoTtl <= 120);
        assertTrue(seenTtl > 0 && seenTtl <= 120);
    }

    @Test
    void riderLocationIsRejected() {
        grant("RIDER", true, null);

        deliver(GPS, false);

        assertNotStored();
    }

    @Test
    void disabledDriverLocationIsRejected() {
        grant("DRIVER", false, "colombo");

        deliver(GPS, false);

        assertNotStored();
    }

    @Test
    void wrongCityDriverLocationIsRejected() {
        grant("DRIVER", true, "kandy");

        deliver(GPS, false);

        assertNotStored();
    }

    @Test
    void unprovisionedAccountLocationIsRejected() {
        deliver(GPS, false);

        assertNotStored();
    }

    @Test
    void invalidCoordinatesAreRejected() {
        grant("DRIVER", true, "colombo");

        deliver("{\"lat\":90,\"lon\":79.8612}", false);
        assertNotStored();

        deliver("{\"lat\":6.9271,\"lon\":181}", false);
        assertNotStored();
    }

    @Test
    void retainedLocationIsRejected() {
        grant("DRIVER", true, "colombo");

        deliver(GPS, true);

        assertNotStored();
    }

    @Test
    void malformedOrMissingCoordinatesAreRejected() {
        grant("DRIVER", true, "colombo");

        for (String body : List.of(
                "{",
                "null",
                "[]",
                "{}",
                "{\"lat\":\"6.9271\",\"lon\":79.8612}"
        )) {
            deliver(body, false);
            assertNotStored();
        }
    }

    @AfterEach
    void cleanUp() {
        if (redis != null) {
            redis.delete(List.of(
                    "geo:colombo",
                    "seen:colombo"
            ));
        }

        // Database fixtures roll back after each test.
    }
}