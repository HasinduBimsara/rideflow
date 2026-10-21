package lk.boc.ride;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RideUpdates extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> sessions =
            new ConcurrentHashMap<>();

    private String rideId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void afterConnectionEstablished(
            WebSocketSession session
    ) throws Exception {
        String id = rideId(session);

        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        sessions.computeIfAbsent(
                id,
                key -> ConcurrentHashMap.newKeySet()
        ).add(session);

        synchronized (session) {
            session.sendMessage(
                    new TextMessage("{\"type\":\"CONNECTED\"}")
            );
        }
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status
    ) {
        Set<WebSocketSession> group =
                sessions.get(rideId(session));

        if (group != null) {
            group.remove(session);
        }
    }

    public void notifyRide(UUID id) {
        Set<WebSocketSession> group =
                sessions.get(id.toString());

        if (group == null) {
            return;
        }

        for (WebSocketSession session : group) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(
                                new TextMessage(
                                        "{\"type\":\"RIDE_UPDATED\"}"
                                )
                        );
                    }
                }
            } catch (Exception e) {
                group.remove(session);
            }
        }
    }
}