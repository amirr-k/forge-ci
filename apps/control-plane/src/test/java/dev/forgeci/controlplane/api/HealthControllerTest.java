package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    @Test
    void readyReportsUpWhenTheDatabaseConnectionIsValid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        ResponseEntity<?> response = new HealthController(dataSource, "unknown").ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readyReportsDownWhenTheDatabaseIsUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        ResponseEntity<?> response = new HealthController(dataSource, "unknown").ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void healthAlwaysReportsUpRegardlessOfDependencies() {
        DataSource dataSource = mock(DataSource.class);

        assertThat(new HealthController(dataSource, "unknown").health())
                .containsEntry("status", "UP");
    }

    @Test
    void versionReportsTheCommitItWasConfiguredWith() {
        DataSource dataSource = mock(DataSource.class);

        Map<String, String> response = new HealthController(dataSource, "abc1234").version();

        assertThat(response).containsEntry("commit", "abc1234");
    }

    @Test
    void versionDefaultsToUnknownRatherThanFabricatingACommit() {
        DataSource dataSource = mock(DataSource.class);

        assertThat(new HealthController(dataSource, "unknown").version())
                .containsEntry("commit", "unknown");
    }
}
