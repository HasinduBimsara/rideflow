package lk.boc.ride;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;

    public OutboxPublisher(
            JdbcTemplate db,
            RabbitTemplate rabbit,
            TransactionTemplate transaction
    ) {
        this.db = db;
        this.rabbit = rabbit;
        this.transaction = transaction;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 2000)
    public void publishPending() {
        try {
            transaction.executeWithoutResult(status -> {
                List<Map<String, Object>> pending =
                        db.queryForList("""
                                SELECT id, ride_id, city
                                FROM outbox
                                WHERE published = FALSE
                                ORDER BY id
                                LIMIT 20
                                FOR UPDATE SKIP LOCKED
                                """);

                for (Map<String, Object> row : pending) {
                    String rideId = row.get("ride_id").toString();
                    String queue = "rides." + row.get("city");

                    Message message = MessageBuilder
                            .withBody(
                                    rideId.getBytes(StandardCharsets.UTF_8)
                            )
                            .setContentType("text/plain")
                            .setMessageId(rideId)
                            .setDeliveryMode(
                                    MessageDeliveryMode.PERSISTENT
                            )
                            .build();

                    CorrelationData correlation =
                            new CorrelationData(
                                    UUID.randomUUID().toString()
                            );

                    rabbit.send("", queue, message, correlation);

                    try {
                        CorrelationData.Confirm confirmation =
                                correlation.getFuture()
                                        .get(5, TimeUnit.SECONDS);

                        if (!confirmation.ack()
                                || correlation.getReturned() != null) {
                            throw new IllegalStateException(
                                    "Message was not accepted by queue"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Publishing interrupted",
                                e
                        );
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Publishing failed",
                                e
                        );
                    }

                    db.update("""
                            UPDATE outbox
                            SET published = TRUE
                            WHERE id = ?
                            """,
                            row.get("id")
                    );
                }
            });
        } catch (Exception e) {
            log.warn(
                    "Outbox publishing failed; pending records will retry",
                    e
            );
        }
    }
}