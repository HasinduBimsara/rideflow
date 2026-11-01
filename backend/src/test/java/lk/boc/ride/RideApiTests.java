package lk.boc.ride;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.MediaType;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest

@Transactional

class RideApiTests {

    private static final String PICKUP = """

            {

              "city": "colombo",

              "lat": 6.928,

              "lon": 79.862

            }

            """;

    @Autowired

    private WebApplicationContext context;

    @Autowired

    private JdbcTemplate db;

    private MockMvc mvc;

    private String owner;

    private String otherUser;

    @BeforeEach

    void setUp() {

        mvc = MockMvcBuilders

                .webAppContextSetup(context)

                .apply(springSecurity())

                .build();

        owner = "test-owner-" + UUID.randomUUID();

        otherUser = "test-other-" + UUID.randomUUID();

    }

    private RequestPostProcessor asUser(String uid) {

        return jwt().jwt(builder -> builder.subject(uid));

    }

    private UUID createRide(String key) throws Exception {

        mvc.perform(post("/api/rides")

                        .with(asUser(owner))

                        .header("Idempotency-Key", key)

                        .contentType(MediaType.APPLICATION_JSON)

                        .content(PICKUP))

                .andExpect(status().isAccepted())

                .andExpect(jsonPath("$.rider_id").value(owner))

                .andExpect(jsonPath("$.status").value("QUEUED"))

                .andExpect(jsonPath("$.request_fingerprint").doesNotExist());

        return db.queryForObject("""

                SELECT id

                FROM rides

                WHERE rider_id = ? AND request_key = ?

                """,

                UUID.class,

                owner,

                key

        );

    }

    private UUID seedMatchedRide() {

        UUID id = UUID.randomUUID();

        String driver = "test-driver-" + UUID.randomUUID();

        db.update("""

                INSERT INTO rides (

                    id, rider_id, request_key, city,

                    lat, lon, status, driver_id,

                    request_fingerprint

                )

                VALUES (

                    ?, ?, ?, 'colombo',

                    6.928, 79.862, 'MATCHED', ?,

                    ride_request_fingerprint(

                        'colombo',

                        CAST(6.928 AS DOUBLE PRECISION),

                        CAST(79.862 AS DOUBLE PRECISION)

                    )

                )

                """,

                id,

                owner,

                UUID.randomUUID().toString(),

                driver

        );

        db.update("""

                INSERT INTO assignments (driver_id, ride_id)

                VALUES (?, ?)

                """,

                driver,

                id

        );

        return id;

    }

    @Test

    void anonymousRequestsAreRejected() throws Exception {

        mvc.perform(post("/api/rides")

                        .header(

                                "Idempotency-Key",

                                UUID.randomUUID().toString()

                        )

                        .contentType(MediaType.APPLICATION_JSON)

                        .content(PICKUP))

                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/rides/" + UUID.randomUUID()))

                .andExpect(status().isUnauthorized());

    }

    @Test

    void sameRequestKeyReturnsSameRideAndOneOutboxEvent()

            throws Exception {

        String key = UUID.randomUUID().toString();

        UUID id = createRide(key);

        mvc.perform(post("/api/rides")

                        .with(asUser(owner))

                        .header("Idempotency-Key", key)

                        .contentType(MediaType.APPLICATION_JSON)

                        .content(PICKUP))

                .andExpect(status().isAccepted())

                .andExpect(jsonPath("$.id").value(id.toString()));

        assertEquals(

                1L,

                db.queryForObject("""

                        SELECT COUNT(*)

                        FROM rides

                        WHERE rider_id = ? AND request_key = ?

                        """,

                        Long.class,

                        owner,

                        key

                )

        );

        assertEquals(

                1L,

                db.queryForObject(

                        "SELECT COUNT(*) FROM outbox WHERE ride_id = ?",

                        Long.class,

                        id

                )

        );

    }

    @Test

    void changedPayloadWithSameKeyIsRejected() throws Exception {

        String key = UUID.randomUUID().toString();

        createRide(key);

        mvc.perform(post("/api/rides")

                        .with(asUser(owner))

                        .header("Idempotency-Key", key)

                        .contentType(MediaType.APPLICATION_JSON)

                        .content("""

                                {

                                  "city": "colombo",

                                  "lat": 6.95,

                                  "lon": 79.88

                                }

                                """))

                .andExpect(status().isConflict());

    }

    @Test

    void anotherUserCannotAccessOrModifyOwnedRide()

            throws Exception {

        UUID id = seedMatchedRide();

        mvc.perform(get("/api/rides/" + id)

                        .with(asUser(owner)))

                .andExpect(status().isOk());

        mvc.perform(get("/api/rides/" + id)

                        .with(asUser(otherUser)))

                .andExpect(status().isNotFound());

        mvc.perform(post("/api/rides/" + id + "/complete")

                        .with(asUser(otherUser)))

                .andExpect(status().isNotFound());

        mvc.perform(post("/api/rides/" + id + "/pay")

                        .with(asUser(otherUser)))

                .andExpect(status().isNotFound());

        assertEquals(

                "MATCHED",

                db.queryForObject(

                        "SELECT status FROM rides WHERE id = ?",

                        String.class,

                        id

                )

        );

    }

    @Test

    void completionClearsCoordinatesAndReleasesDriver()

            throws Exception {

        UUID id = seedMatchedRide();

        mvc.perform(post("/api/rides/" + id + "/complete")

                        .with(asUser(owner)))

                .andExpect(status().isOk())

                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var ride = db.queryForMap(

                "SELECT * FROM rides WHERE id = ?",

                id

        );

        assertNull(ride.get("lat"));

        assertNull(ride.get("lon"));

        assertNotNull(ride.get("request_fingerprint"));

        assertEquals(

                0L,

                db.queryForObject(

                        "SELECT COUNT(*) FROM assignments WHERE ride_id = ?",

                        Long.class,

                        id

                )

        );

        assertEquals(

                Boolean.TRUE,

                db.queryForObject("""

                        SELECT

                            ST_SRID(pickup_area) = 4326

                            AND ST_Covers(

                                pickup_area,

                                ST_SetSRID(

                                    ST_MakePoint(79.862, 6.928),

                                    4326

                                )

                            )

                        FROM trip_history

                        WHERE ride_id = ?

                        """,

                        Boolean.class,

                        id

                )

        );

        // Completing the same ride again must remain safe.

        mvc.perform(post("/api/rides/" + id + "/complete")

                        .with(asUser(owner)))

                .andExpect(status().isOk())

                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(

                1L,

                db.queryForObject(

                        "SELECT COUNT(*) FROM trip_history WHERE ride_id = ?",

                        Long.class,

                        id

                )

        );

    }

    @Test

    void paymentRetryReturnsSameReceipt() throws Exception {

        UUID id = seedMatchedRide();

        mvc.perform(post("/api/rides/" + id + "/complete")

                        .with(asUser(owner)))

                .andExpect(status().isOk());

        mvc.perform(post("/api/rides/" + id + "/pay")

                        .with(asUser(owner)))

                .andExpect(status().isOk())

                .andExpect(jsonPath("$.status").value("MOCK_SUCCEEDED"))

                .andExpect(jsonPath("$.amount_minor").value(15000))

                .andExpect(jsonPath("$.currency").value("lkr"));

        UUID receipt = db.queryForObject(

                "SELECT receipt FROM payments WHERE ride_id = ?",

                UUID.class,

                id

        );

        mvc.perform(post("/api/rides/" + id + "/pay")

                        .with(asUser(owner)))

                .andExpect(status().isOk())

                .andExpect(jsonPath("$.receipt").value(receipt.toString()));

        assertEquals(

                1L,

                db.queryForObject(

                        "SELECT COUNT(*) FROM payments WHERE ride_id = ?",

                        Long.class,

                        id

                )

        );

        // Ownership protection also applies after completion.

        mvc.perform(post("/api/rides/" + id + "/pay")

                        .with(asUser(otherUser)))

                .andExpect(status().isNotFound());

    }

    @Test

    void paymentBeforeCompletionIsRejected() throws Exception {

        UUID id = seedMatchedRide();

        mvc.perform(post("/api/rides/" + id + "/pay")

                        .with(asUser(owner)))

                .andExpect(status().isConflict());

        assertEquals(

                0L,

                db.queryForObject(

                        "SELECT COUNT(*) FROM payments WHERE ride_id = ?",

                        Long.class,

                        id

                )

        );

    }


    @Test
    void driverCannotCreateRideEvenWithRiderClaim() throws Exception {
        insertAccountRole("DRIVER", true, "colombo");
        String key = UUID.randomUUID().toString();

        // Database role is authoritative, not a token role claim.
        mvc.perform(post("/api/rides")
                        .with(jwt().jwt(token -> token.subject(owner).claim("role", "RIDER")))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PICKUP))
                .andExpect(status().isForbidden());

        assertEquals(0L, db.queryForObject(
                "SELECT COUNT(*) FROM rides WHERE rider_id = ?", Long.class, owner));
        assertEquals(0L, db.queryForObject("""
                SELECT COUNT(*) FROM outbox o JOIN rides r ON r.id = o.ride_id
                WHERE r.rider_id = ?
                """, Long.class, owner));
    }

    @Test
    void driverCannotUseRideEndpointsEvenForPreviouslyOwnedRide() throws Exception {
        UUID id = seedMatchedRide();
        insertAccountRole("DRIVER", true, "colombo");
        assertOwnedRideEndpointsForbidden(id);
        assertMatchedRideUnchanged(id);
    }

    @Test
    void disabledRiderCannotCreateOrAccessOwnedRide() throws Exception {
        UUID id = seedMatchedRide();
        insertAccountRole("RIDER", false, null);
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/rides")
                        .with(asUser(owner))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PICKUP))
                .andExpect(status().isForbidden());

        assertEquals(0L, db.queryForObject("""
                SELECT COUNT(*) FROM rides WHERE rider_id = ? AND request_key = ?
                """, Long.class, owner, key));
        assertOwnedRideEndpointsForbidden(id);
        assertMatchedRideUnchanged(id);
    }

    @Test
    void databasePermissionChangeAppliesToExistingAuthentication() throws Exception {
        UUID id = seedMatchedRide();
        insertAccountRole("RIDER", true, null);
        RequestPostProcessor authentication = asUser(owner);

        mvc.perform(get("/api/rides/" + id).with(authentication))
                .andExpect(status().isOk());

        db.update("UPDATE account_roles SET enabled = FALSE WHERE firebase_uid = ?", owner);

        mvc.perform(get("/api/rides/" + id).with(authentication))
                .andExpect(status().isForbidden());

        db.update("UPDATE account_roles SET enabled = TRUE WHERE firebase_uid = ?", owner);

        mvc.perform(get("/api/rides/" + id).with(authentication))
                .andExpect(status().isOk());
    }

    private void insertAccountRole(String role, boolean enabled, String city) {
        db.update("""
                INSERT INTO account_roles (firebase_uid, role, enabled, driver_city)
                VALUES (?, ?, ?, ?)
                """, owner, role, enabled, city);
    }

    private void assertOwnedRideEndpointsForbidden(UUID id) throws Exception {
        mvc.perform(get("/api/rides/" + id).with(asUser(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rides/" + id + "/complete").with(asUser(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rides/" + id + "/pay").with(asUser(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rides/" + id + "/socket-ticket").with(asUser(owner)))
                .andExpect(status().isForbidden());
    }

    private void assertMatchedRideUnchanged(UUID id) {
        var ride = db.queryForMap("SELECT status, lat, lon FROM rides WHERE id = ?", id);
        assertEquals("MATCHED", ride.get("status"));
        assertNotNull(ride.get("lat"));
        assertNotNull(ride.get("lon"));
        assertEquals(1L, db.queryForObject(
                "SELECT COUNT(*) FROM assignments WHERE ride_id = ?", Long.class, id));
        assertEquals(0L, db.queryForObject(
                "SELECT COUNT(*) FROM trip_history WHERE ride_id = ?", Long.class, id));
        assertEquals(0L, db.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE ride_id = ?", Long.class, id));
    }
}