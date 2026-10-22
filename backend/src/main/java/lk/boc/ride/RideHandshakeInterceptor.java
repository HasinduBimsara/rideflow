package lk.boc.ride;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

@Component
public class RideHandshakeInterceptor
        implements HandshakeInterceptor {

    private final StringRedisTemplate redis;
    private final JdbcTemplate db;

    public RideHandshakeInterceptor(
            StringRedisTemplate redis,
            JdbcTemplate db
    ) {
        this.redis = redis;
        this.db = db;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes
    ) {
        String path = request.getURI().getPath();
        String prefix = "/ws/rides/";

        if (!path.startsWith(prefix)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        UUID rideId;
        UUID ticketId;

        try {
            rideId = UUID.fromString(
                    path.substring(prefix.length())
            );

            String ticket = UriComponentsBuilder
                    .fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("ticket");

            if (ticket == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            ticketId = UUID.fromString(ticket);
        } catch (IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        String key =
                "ws-ticket:" + rideId + ":" + ticketId;

        String rider = redis.opsForValue().getAndDelete(key);

        if (rider == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long count = db.queryForObject("""
                SELECT COUNT(*)
                FROM rides
                WHERE id = ? AND rider_id = ?
                """,
                Long.class,
                rideId,
                rider
        );

        if (count == null || count != 1L) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put("riderId", rider);
        attributes.put("rideId", rideId.toString());

        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Exception exception
    ) {
    }
}