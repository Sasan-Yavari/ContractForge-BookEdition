package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class AwsDatabaseProfileConfigurationTests {

    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    @Test
    void awsProfileUsesTheRotatingSecretsManagerDriverWithoutStaticDatabaseCredentials() throws IOException {
        PropertySource<?> aws = load("application-aws.yml");

        assertThat(aws.getProperty("spring.datasource.url")).isEqualTo("${CONTRACTFORGE_DB_URL}");
        assertThat(aws.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("software.amazon.jdbc.Driver");
        assertThat(aws.getProperty("spring.datasource.hikari.data-source-properties.wrapperPlugins"))
                .isEqualTo("awsSecretsManager");
        assertThat(aws.getProperty(
                        "spring.datasource.hikari.data-source-properties.secretsManagerSecretId"))
                .isEqualTo("${CONTRACTFORGE_DB_SECRET_ARN}");
        int connectionTimeout = ((Number) aws.getProperty("spring.datasource.hikari.connection-timeout")).intValue();
        int retryTimeout = ((Number) aws.getProperty(
                "spring.datasource.hikari.data-source-properties.secretsManagerConnectRetryTimeoutMs")).intValue();
        assertThat(retryTimeout).isEqualTo(120000);
        assertThat(connectionTimeout).isGreaterThan(retryTimeout);
        assertThat(aws.getProperty("spring.datasource.username")).isNull();
        assertThat(aws.getProperty("spring.datasource.password")).isNull();
    }

    @Test
    void jacksonThreeCanInspectModelsWithTheJsonApplyViewAnnotation() throws Exception {
        var json = JsonMapper.builder().build().writeValueAsString(new SampleValue("credential"));

        assertThat(json).isEqualTo("{\"value\":\"credential\"}");
    }

    private PropertySource<?> load(String resourceName) throws IOException {
        return yamlLoader.load(resourceName, new ClassPathResource(resourceName)).getFirst();
    }

    private record SampleValue(String value) {}
}
