package lk.boc.ride;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RideUpdates updates;

    public WebSocketConfig(RideUpdates updates) {
        this.updates = updates;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(updates, "/ws/rides/*");
    }
}