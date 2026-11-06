package com.buildassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-only-not-used",
        "spring.security.oauth2.client.registration.google.client-id=test-only-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-only-secret",
        "app.encryption.aes-key=0123456789abcdef0123456789abcdef",
        "app.email-classification.enabled=false"
})
@ActiveProfiles("test")
class ClassificationMigrationIntegrationTest {

    @Test
    void v2UpgradesAnExistingV1SchemaAndPreservesManualCategories() {
        String schema = "classification_v1_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/mira_test?currentSchema=" + schema + ",public",
                System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv("TEST_DATABASE_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(migrationDataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure()
                    .dataSource(migrationDataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .initSql("SET search_path TO " + schema + ", public")
                    .target(MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();

            Long userId = jdbc.queryForObject("""
                    INSERT INTO %s.users (email, password_hash, is_active)
                    VALUES (?, 'test-only', TRUE)
                    RETURNING id
                    """.formatted(schema), Long.class, "migration-" + UUID.randomUUID() + "@test.local");
            Long connectionId = jdbc.queryForObject("""
                    INSERT INTO %s.gmail_connections (
                        user_id, gmail_address, access_token_encrypted, refresh_token_encrypted, token_expires_at
                    ) VALUES (?, ?, 'test-only', 'test-only', NOW() + INTERVAL '1 hour')
                    RETURNING id
                    """.formatted(schema), Long.class, userId, "migration-" + UUID.randomUUID() + "@test.local");
            jdbc.update("""
                    INSERT INTO %s.emails (
                        gmail_connection_id, gmail_message_id, email_received_at,
                        category, classification_status, classification_source
                    ) VALUES (?, 'pending-before-v2', NOW(), 'OTHER', 'PENDING', 'PENDING'),
                             (?, 'manual-before-v2', NOW(), 'QUOTE_REQUEST', 'CLASSIFIED', 'MANUAL')
                    """.formatted(schema), connectionId, connectionId);

            Flyway.configure()
                    .dataSource(migrationDataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .initSql("SET search_path TO " + schema + ", public")
                    .load()
                    .migrate();

            assertEquals("UNCLASSIFIED", jdbc.queryForObject("""
                    SELECT category FROM %s.emails WHERE gmail_message_id = 'pending-before-v2'
                    """.formatted(schema), String.class));
            assertEquals("HISTORICAL", jdbc.queryForObject("""
                    SELECT classification_queue_type FROM %s.emails
                    WHERE gmail_message_id = 'pending-before-v2'
                    """.formatted(schema), String.class));
            assertNotNull(jdbc.queryForObject("""
                    SELECT classification_queued_at FROM %s.emails
                    WHERE gmail_message_id = 'pending-before-v2'
                    """.formatted(schema), java.time.OffsetDateTime.class));
            assertEquals("QUOTE_REQUEST", jdbc.queryForObject("""
                    SELECT category FROM %s.emails WHERE gmail_message_id = 'manual-before-v2'
                    """.formatted(schema), String.class));
            assertEquals("MANUAL", jdbc.queryForObject("""
                    SELECT classification_source FROM %s.emails
                    WHERE gmail_message_id = 'manual-before-v2'
                    """.formatted(schema), String.class));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
