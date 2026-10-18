package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseCredentialRotationIntegrationTests extends PostgresIntegrationTest {

    private static final String DATABASE_USER = "contractforge_rotation_test";
    private static final String INITIAL_PASSWORD = "initial-test-password";
    private static final String ROTATED_PASSWORD = "rotated-test-password";
    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:eu-west-1:000000000000:secret:database-rotation-test";

    @Test
    void jdbcConnectionsRefreshManagedCredentialsAfterPasswordRotation() throws Exception {
        var credential = new AtomicReference<>(INITIAL_PASSWORD);
        var secretReads = new AtomicInteger();
        ExecutorService httpExecutor = Executors.newSingleThreadExecutor();
        HttpServer secretsManager = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secretsManager.setExecutor(httpExecutor);
        secretsManager.createContext("/", exchange -> sendSecret(exchange, credential, secretReads));
        secretsManager.start();

        String oldAccessKey = System.getProperty("aws.accessKeyId");
        String oldSecretKey = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");

        try {
            createDatabaseRole(INITIAL_PASSWORD);
            var properties = secretsManagerProperties(secretsManager);
            var hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(POSTGRES.getJdbcUrl().replace("jdbc:postgresql:", "jdbc:aws-wrapper:postgresql:"));
            hikariConfig.setDriverClassName("software.amazon.jdbc.Driver");
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setMinimumIdle(0);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setDataSourceProperties(properties);

            try (var pool = new HikariDataSource(hikariConfig)) {
                assertThat(currentDatabaseUser(pool)).isEqualTo(DATABASE_USER);
                int readsBeforeRotation = secretReads.get();

                changeDatabasePassword(ROTATED_PASSWORD);
                credential.set(ROTATED_PASSWORD);
                pool.getHikariPoolMXBean().softEvictConnections();

                assertThat(currentDatabaseUser(pool)).isEqualTo(DATABASE_USER);
                assertThat(secretReads.get()).isGreaterThan(readsBeforeRotation);
            }
        } finally {
            restoreSystemProperty("aws.accessKeyId", oldAccessKey);
            restoreSystemProperty("aws.secretAccessKey", oldSecretKey);
            secretsManager.stop(0);
            httpExecutor.shutdownNow();
            dropDatabaseRole();
        }
    }

    private static Properties secretsManagerProperties(HttpServer secretsManager) throws IOException {
        PropertySource<?> awsProperties = new YamlPropertySourceLoader()
                .load("aws", new ClassPathResource("application-aws.yml"))
                .getFirst();
        assertThat(awsProperties.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("software.amazon.jdbc.Driver");
        assertThat(awsProperties.getProperty(
                        "spring.datasource.hikari.data-source-properties.wrapperPlugins"))
                .isEqualTo("awsSecretsManager");

        var properties = new Properties();
        properties.setProperty("wrapperPlugins", awsProperties.getProperty(
                "spring.datasource.hikari.data-source-properties.wrapperPlugins").toString());
        properties.setProperty("secretsManagerSecretId", SECRET_ARN);
        properties.setProperty("secretsManagerRegion", "eu-west-1");
        properties.setProperty(
                "secretsManagerConnectRetryTimeoutMs",
                awsProperties.getProperty(
                        "spring.datasource.hikari.data-source-properties.secretsManagerConnectRetryTimeoutMs")
                        .toString());
        properties.setProperty(
                "secretsManagerConnectRetryIntervalMs",
                awsProperties.getProperty(
                        "spring.datasource.hikari.data-source-properties.secretsManagerConnectRetryIntervalMs")
                        .toString());
        properties.setProperty("secretsManagerExpirationTimeSec", "3600");
        properties.setProperty(
                "secretsManagerEndpoint",
                "http://127.0.0.1:" + secretsManager.getAddress().getPort());
        return properties;
    }

    private static void sendSecret(
            HttpExchange exchange,
            AtomicReference<String> credential,
            AtomicInteger secretReads) throws IOException {
        secretReads.incrementAndGet();
        String secretJson = "{\"username\":\"" + DATABASE_USER + "\",\"password\":\""
                + credential.get() + "\"}";
        String response = "{\"ARN\":\"" + SECRET_ARN + "\",\"Name\":\"database-rotation-test\","
                + "\"VersionId\":\"" + UUID.randomUUID().toString().replace("-", "") + "\","
                + "\"SecretString\":\"" + secretJson.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"VersionStages\":[\"AWSCURRENT\"]}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.1");
        exchange.getResponseHeaders().set("x-amzn-RequestId", UUID.randomUUID().toString());
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void createDatabaseRole(String password) throws SQLException {
        try (Connection connection = administrativeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + DATABASE_USER);
            statement.execute("CREATE ROLE " + DATABASE_USER + " LOGIN PASSWORD '" + password + "'");
        }
    }

    private static void changeDatabasePassword(String password) throws SQLException {
        try (Connection connection = administrativeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE " + DATABASE_USER + " PASSWORD '" + password + "'");
        }
    }

    private static void dropDatabaseRole() throws SQLException {
        try (Connection connection = administrativeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + DATABASE_USER);
        }
    }

    private static Connection administrativeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String currentDatabaseUser(HikariDataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT current_user")) {
            result.next();
            return result.getString(1);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
