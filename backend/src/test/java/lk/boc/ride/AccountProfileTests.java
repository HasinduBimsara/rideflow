package lk.boc.ride;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class AccountProfileTests {

    private static final String ENDPOINT = "/api/auth/account-profile";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate db;

    private MockMvc mvc;
    private String uid;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        uid = "profile-test-" + UUID.randomUUID();
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountWithoutExplicitRoleDefaultsToRider() throws Exception {
        mvc.perform(get(ENDPOINT)
                        .with(jwt().jwt(token -> token.subject(uid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.role").value("RIDER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.driverCity").value(nullValue()));
    }

    @Test
    void driverReceivesOwnRoleAndCity() throws Exception {
        insertDriver(uid, "colombo", true);

        // Another account's profile must not be returned.
        insertDriver(
                "profile-other-" + UUID.randomUUID(),
                "kandy",
                true
        );

        mvc.perform(get(ENDPOINT)
                        .with(jwt().jwt(token -> token.subject(uid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.driverCity").value("colombo"));
    }

    @Test
    void disabledAccountReceivesDisabledProfile() throws Exception {
        insertDriver(uid, "kandy", false);

        mvc.perform(get(ENDPOINT)
                        .with(jwt().jwt(token -> token.subject(uid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.driverCity").value("kandy"));
    }

    private void insertDriver(
            String accountUid,
            String city,
            boolean enabled
    ) {
        db.update("""
                INSERT INTO account_roles (
                    firebase_uid,
                    role,
                    enabled,
                    driver_city
                )
                VALUES (?, 'DRIVER', ?, ?)
                """,
                accountUid,
                enabled,
                city
        );
    }
}