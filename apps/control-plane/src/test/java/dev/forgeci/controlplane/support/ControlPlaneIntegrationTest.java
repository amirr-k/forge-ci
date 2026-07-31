package dev.forgeci.controlplane.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base for tests that need a full Spring context wired to the shared MySQL Testcontainer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
public abstract class ControlPlaneIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", MySqlTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", MySqlTestContainer.INSTANCE::getPassword);

        registry.add("forge.artifacts.s3.endpoint-override", MinioTestContainer.INSTANCE::getS3URL);
        registry.add("forge.artifacts.s3.access-key", MinioTestContainer.INSTANCE::getUserName);
        registry.add("forge.artifacts.s3.secret-key", MinioTestContainer.INSTANCE::getPassword);
        registry.add("forge.artifacts.s3.bucket", () -> "forgeci-artifacts-test");
    }
}
