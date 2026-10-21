package lk.boc.ride;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class LocationIngestion {

    private static final Logger log =
            LoggerFactory.getLogger(LocationIngestion.class);

    private static final DefaultRedisScript<Long> STORE =
            new DefaultRedisScript<>("""
                    redis.call(
                        'GEOADD', KEYS[1],
                        ARGV[1], ARGV[2], ARGV[3]
                    )
                    redis.call(
                        'ZADD', KEYS[2],
                        ARGV[4], ARGV[3]
                    )
                    redis.call('EXPIRE', KEYS[1], 120)
                    redis.call('EXPIRE', KEYS[2], 120)
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String mqttUrl;

    private MqttClient client;

    public LocationIngestion(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            @Value("${rideflow.mqtt.url}") String mqttUrl
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.mqttUrl = mqttUrl;
    }

    @PostConstruct
    public void start() throws MqttException {
        client = new MqttClient(
                mqttUrl,
                "rideflow-ingestion-" + UUID.randomUUID(),
                new MemoryPersistence()
        );

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(
                    boolean reconnect,
                    String serverURI
            ) {
                try {
                    client.subscribe("drivers/+/+/location", 0);
                    log.info("MQTT location subscription ready");
                } catch (MqttException e) {
                    log.error("MQTT subscription failed", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT disconnected; reconnecting", cause);
            }

            @Override
            public void messageArrived(
                    String topic,
                    MqttMessage message
            ) {
                processLocation(topic, message);
            }

            @Override
            public void deliveryComplete(
                    IMqttDeliveryToken token
            ) {
            }
        });

        client.connect(options);
    }

    private void processLocation(
            String topic,
            MqttMessage message
    ) {
        try {
            String[] parts = topic.split("/");

            if (parts.length != 4) {
                return;
            }

            String city = parts[1];
            String driver = parts[2];

            if (!Set.of("colombo", "kandy").contains(city)
                    || !driver.matches("[A-Za-z0-9_-]{1,100}")) {
                return;
            }

            JsonNode data = mapper.readTree(message.getPayload());
            JsonNode latitude = data.get("lat");
            JsonNode longitude = data.get("lon");

            if (latitude == null || longitude == null
                    || !latitude.isNumber()
                    || !longitude.isNumber()) {
                return;
            }

            double lat = latitude.asDouble();
            double lon = longitude.asDouble();

            if (!Double.isFinite(lat)
                    || !Double.isFinite(lon)
                    || Math.abs(lat) > 85
                    || Math.abs(lon) > 180) {
                return;
            }

            redis.execute(
                    STORE,
                    List.of("geo:" + city, "seen:" + city),
                    String.valueOf(lon),
                    String.valueOf(lat),
                    driver,
                    String.valueOf(System.currentTimeMillis())
            );
        } catch (Exception e) {
            log.warn("Location update could not be processed", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (client == null) {
            return;
        }

        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            log.warn("MQTT shutdown failed", e);
        }
    }
}