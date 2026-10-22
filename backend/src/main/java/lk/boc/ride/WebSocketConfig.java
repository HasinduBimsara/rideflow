package lk.boc.ride;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RideUpdates updates;
    private final RideHandshakeInterceptor interceptor;

    public WebSocketConfig(
            RideUpdates updates,
            RideHandshakeInterceptor interceptor
    ) {
        this.updates = updates;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                updates,
                "/ws/rides/*"
        ).addInterceptors(interceptor);
    }
}