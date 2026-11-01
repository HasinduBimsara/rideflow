package lk.boc.ride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHandler;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class SocketTicketTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper mapper;

    private MockMvc mvc;
    private RideHandshakeInterceptor interceptor;
    private String owner;
    private UUID rideId;

    private final List<String> ticketKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        interceptor = new RideHandshakeInterceptor(redis, db);

        owner = "socket-owner-" + UUID.randomUUID();
        rideId = UUID.randomUUID();

        db.update("""
                INSERT INTO rides (
                    id,
                    rider_id,
                    request_key,
                    city,
                    lat,
                    lon
                )
                VALUES (?, ?, ?, 'colombo', 6.928, 79.862)
                """,
                rideId,
                owner,
                UUID.randomUUID().toString()
        );

        // No outbox event: matching workers do not process this fixture.
    }

    private String endpoint() {
        return "/api/rides/" + rideId + "/socket-ticket";
    }

    private String key(UUID id, String ticket) {
        return "ws-ticket:" + id + ":" + ticket;
    }

    private String issueTicket() throws Exception {
        var result = mvc.perform(post(endpoint())
                        .with(jwt().jwt(token -> token.subject(owner))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.expiresInSeconds").value(60))
                .andExpect(jsonPath("$.ticket").isString())
                .andReturn();

        String ticket = mapper.readTree(
                result.getResponse().getContentAsString()
        ).get("ticket").asText();

        UUID.fromString(ticket);

        ticketKeys.add(key(rideId, ticket));
        return ticket;
    }

    private record HandshakeResult(
            boolean accepted,
            int status,
            Map<String, Object> attributes
    ) {
    }

    private HandshakeResult handshake(
            UUID id,
            String ticket
    ) {
        var request = new MockHttpServletRequest(
                "GET",
                "/ws/rides/" + id
        );

        if (ticket != null) {
            request.setQueryString("ticket=" + ticket);
        }

        var response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes
        );

        return new HandshakeResult(
                accepted,
                response.getStatus(),
                attributes
        );
    }

    @Test
    void anonymousUserCannotCreateTicket() throws Exception {
        mvc.perform(post(endpoint()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherRiderCannotCreateOwnersTicket() throws Exception {
        String otherUser = "socket-other-" + UUID.randomUUID();

        mvc.perform(post(endpoint())
                        .with(jwt().jwt(token -> token.subject(otherUser))))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerReceivesTicketWithRedisExpiry() throws Exception {
        String ticket = issueTicket();
        String redisKey = key(rideId, ticket);

        assertEquals(
                owner,
                redis.opsForValue().get(redisKey)
        );

        Long ttl = redis.getExpire(redisKey);

        assertNotNull(ttl);
        assertTrue(
                ttl > 0 && ttl <= 60,
                "Ticket must expire within 60 seconds"
        );
    }

    @Test
    void ticketCanBeUsedOnlyOnce() throws Exception {
        String ticket = issueTicket();

        HandshakeResult first = handshake(rideId, ticket);

        assertTrue(first.accepted());
        assertEquals(owner, first.attributes().get("riderId"));
        assertEquals(
                rideId.toString(),
                first.attributes().get("rideId")
        );

        assertFalse(
                Boolean.TRUE.equals(
                        redis.hasKey(key(rideId, ticket))
                )
        );

        HandshakeResult second = handshake(rideId, ticket);

        assertFalse(second.accepted());
        assertEquals(401, second.status());
        assertTrue(second.attributes().isEmpty());
    }

    @Test
    void ticketCannotBeUsedForAnotherRide() throws Exception {
        String ticket = issueTicket();

        HandshakeResult wrongRide = handshake(
                UUID.randomUUID(),
                ticket
        );

        assertFalse(wrongRide.accepted());
        assertEquals(401, wrongRide.status());
        assertTrue(wrongRide.attributes().isEmpty());

        // Wrong ride must not consume the original ride's ticket.
        assertTrue(handshake(rideId, ticket).accepted());
    }

    @Test
    void expiredTicketIsRejected() throws Exception {
        String ticket = issueTicket();
        String redisKey = key(rideId, ticket);

        // Expire the real Redis key immediately; no 60-second wait.
        assertEquals(
                Boolean.TRUE,
                redis.expireAt(redisKey, Instant.EPOCH)
        );

        assertFalse(Boolean.TRUE.equals(redis.hasKey(redisKey)));

        HandshakeResult result = handshake(rideId, ticket);

        assertFalse(result.accepted());
        assertEquals(401, result.status());
        assertTrue(result.attributes().isEmpty());
    }

    @Test
    void ownershipIsCheckedAgainDuringHandshake() throws Exception {
        String ticket = issueTicket();

        // Simulate ownership changing after ticket creation.
        db.update(
                "UPDATE rides SET rider_id = ? WHERE id = ?",
                "socket-new-owner-" + UUID.randomUUID(),
                rideId
        );

        HandshakeResult result = handshake(rideId, ticket);

        assertFalse(result.accepted());
        assertEquals(403, result.status());
        assertTrue(result.attributes().isEmpty());

        // A rejected ownership check still consumes the ticket.
        assertFalse(
                Boolean.TRUE.equals(
                        redis.hasKey(key(rideId, ticket))
                )
        );
    }

    @Test
    void missingAndMalformedTicketsAreRejected() {
        HandshakeResult missing = handshake(rideId, null);

        assertFalse(missing.accepted());
        assertEquals(401, missing.status());

        HandshakeResult malformed = handshake(
                rideId,
                "not-a-valid-uuid"
        );

        assertFalse(malformed.accepted());
        assertEquals(400, malformed.status());
    }

    @AfterEach
    void cleanUp() {
        if (!ticketKeys.isEmpty()) {
            redis.delete(ticketKeys);
        }

        // SQL fixtures roll back automatically.
    }
}