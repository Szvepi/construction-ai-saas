package com.buildassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.buildassist.config.AppProperties;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-only-not-used",
        "spring.security.oauth2.client.registration.google.client-id=test-only-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-only-secret"
})
@ActiveProfiles("test")
class DatabaseBootstrapIntegrationTest {

    private static final String TEST_ENCRYPTION_KEY = java.util.UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        registry.add("app.encryption.aes-key", () -> TEST_ENCRYPTION_KEY);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @Autowired
    private AppProperties appProperties;

    @Test
    void normalGoogleLoginAuthorizationRequestUsesOnlyIdentityScopesWithoutConsentPrompts() {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrations, "/oauth2/authorization");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setRequestURI("/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");

        var authorizationRequest = resolver.resolve(request);

        assertEquals(Set.of("openid", "email", "profile"), authorizationRequest.getScopes());
        var query = org.springframework.web.util.UriComponentsBuilder
                .fromUriString(authorizationRequest.getAuthorizationRequestUri()).build(true).getQueryParams();
        assertFalse(query.containsKey("access_type"));
        assertFalse(query.containsKey("prompt"));
        assertFalse(java.net.URLDecoder.decode(
                query.getFirst("scope"), java.nio.charset.StandardCharsets.UTF_8).contains("gmail."));
    }

    @Test
    void flywayMigrationsCreateTheCurrentSchemaAndHibernateValidatesIt() {
        assertEquals(List.of("1", "2"), jdbcTemplate.query(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank",
                (resultSet, rowNum) -> resultSet.getString(1)));
        assertTrue(migrationExists("1"));
        assertTrue(migrationExists("2"));

        List<String> expectedTables = List.of(
                "users", "gmail_connections", "emails", "email_drafts", "company_profiles", "catalog_items",
                "email_classification_daily_budget", "email_classification_user_daily_budget",
                "email_classification_ai_attempt");
        for (String table : expectedTables) {
            assertTrue(tableExists(table), "Expected migrated table: " + table);
        }

        assertTrue(columnExists("email_drafts", "quote_snapshot"));
        assertTrue(columnExists("email_drafts", "draft_version"));
        assertTrue(columnExists("catalog_items", "calculation_config"));
        assertTrue(columnExists("gmail_connections", "gmail_sync_retry_at"));
        assertTrue(columnExists("gmail_connections", "gmail_sync_auth_error_category"));
        assertTrue(columnExists("emails", "classification_status"));
        assertTrue(columnExists("emails", "classification_source"));
        assertTrue(columnExists("emails", "classification_queue_type"));
        assertTrue(columnExists("emails", "classification_origin"));
        assertTrue(columnExists("emails", "classification_rule_id"));
        assertTrue(columnExists("emails", "classification_active_attempt_id"));
        assertFalse(columnExists("email_drafts", "line_items"));
        assertFalse(columnExists("email_drafts", "unmapped_requests"));
        assertFalse(columnExists("email_drafts", "requested_work_items"));
        assertFalse(columnExists("catalog_items", "calculation_strategy"));
    }

    @Test
    void classificationLimitsAreConfiguredOutsideJavaDefaults() {
        assertEquals(1_000, appProperties.getEmailClassification().getMaxPerDay());
        assertEquals(150, appProperties.getEmailClassification().getMaxPerUserPerDay());
        assertEquals(5, appProperties.getEmailClassification().getMaxPerRun());
        assertEquals("10.00", appProperties.getEmailClassification().getMaxCostUsdPerDay().toPlainString());
        assertEquals("UTC", appProperties.getEmailClassification().getDailyBudgetTimeZone());
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?)",
                Boolean.class, table));
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?)",
                Boolean.class, table, column));
    }

    private boolean migrationExists(String version) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = TRUE AND version = ?)",
                Boolean.class, version));
    }
}
