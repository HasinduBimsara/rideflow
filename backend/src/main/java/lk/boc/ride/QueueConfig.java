package lk.boc.ride;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class QueueConfig {

    @Bean
    public Declarables rideQueues() {
        return new Declarables(
                QueueBuilder.durable("rides.colombo")
                        .deadLetterExchange("")
                        .deadLetterRoutingKey("rides.dead")
                        .build(),

                QueueBuilder.durable("rides.kandy")
                        .deadLetterExchange("")
                        .deadLetterRoutingKey("rides.dead")
                        .build(),

                QueueBuilder.durable("rides.dead")
                        .build()
        );
    }
}