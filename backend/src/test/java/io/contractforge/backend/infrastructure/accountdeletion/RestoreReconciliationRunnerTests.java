package io.contractforge.backend.infrastructure.accountdeletion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.UserStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;

class RestoreReconciliationRunnerTests {
    @Test
    void onlyAcceptsExactDatabaseEndpointHostFromTheWrapperJdbcUrl() {
        assertThat(RestoreReconciliationRunner.usesEndpoint(
                "jdbc:aws-wrapper:postgresql://restore.example:5432/contractforge?sslmode=require",
                "restore.example")).isTrue();
        assertThat(RestoreReconciliationRunner.usesEndpoint(
                "jdbc:postgresql://attacker.example/contractforge?targetServer=restore.example",
                "restore.example")).isFalse();
        assertThat(RestoreReconciliationRunner.usesEndpoint(
                "jdbc:aws-wrapper:postgresql://restore.example.attacker.invalid/contractforge",
                "restore.example")).isFalse();
    }

    @Test
    void refusesToTouchTheRestoredDatabaseWithoutExplicitIsolationConfirmation() {
        RdsClient rds = mock(RdsClient.class);
        var runner = new RestoreReconciliationRunner(mock(AccountDeletionTombstonePort.class),
                mock(AccountDeletionRepository.class), mock(AccountDeletionWorker.class),
                mock(RestoreReconciliationBootstrap.class), mock(UserRepository.class), rds,
                Clock.systemUTC(), mock(Environment.class));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
                "--restore-reconcile=apply", "--confirm-isolated=false",
                "--source-db-resource-id=source", "--source-db-instance-identifier=source-id",
                "--target-db-instance-identifier=restored-id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled runtime traffic must be confirmed");
        verify(rds, never()).describeDBInstances(any(software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest.class));
    }

    @Test
    void refusesToTreatTheSourceDatabaseAsItsOwnRestoreTarget() {
        RdsClient rds = mock(RdsClient.class);
        var runner = new RestoreReconciliationRunner(mock(AccountDeletionTombstonePort.class),
                mock(AccountDeletionRepository.class), mock(AccountDeletionWorker.class),
                mock(RestoreReconciliationBootstrap.class), mock(UserRepository.class), rds,
                Clock.systemUTC(), mock(Environment.class));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
                "--restore-reconcile=apply", "--confirm-isolated=true",
                "--source-db-resource-id=source-resource", "--source-db-instance-identifier=source-id",
                "--target-db-instance-identifier=source-id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new isolated database instance");
        verify(rds, never()).describeDBInstances(any(software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest.class));
    }

    @Test
    void refusesAResourceIdThatDoesNotBelongToTheNamedSourceDatabase() {
        RdsClient rds = mock(RdsClient.class);
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenReturn(DescribeDbInstancesResponse.builder().dbInstances(DBInstance.builder()
                        .dbInstanceIdentifier("source-id").dbiResourceId("different-resource").build()).build());
        var runner = new RestoreReconciliationRunner(mock(AccountDeletionTombstonePort.class),
                mock(AccountDeletionRepository.class), mock(AccountDeletionWorker.class),
                mock(RestoreReconciliationBootstrap.class), mock(UserRepository.class), rds,
                Clock.systemUTC(), mock(Environment.class));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
                "--restore-reconcile=apply", "--confirm-isolated=true",
                "--source-db-resource-id=approved-resource", "--source-db-instance-identifier=source-id",
                "--target-db-instance-identifier=restored-id")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the approved resource ID");
        verify(rds).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    void appliesTheTombstoneAgainstRestoredStateAndCompletesOnlyAfterPurgedDataIsVerified() {
        UUID operationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(Clock.systemUTC());
        String sourceResourceId = "approved-source-resource";
        var tombstone = new AccountDeletionTombstone(operationId, userId, sourceResourceId,
                List.of("v1_fingerprint"), AccountDeletionTombstone.State.IN_PROGRESS,
                now.minusMinutes(5), null, null, null, 0);
        ExternalIdentity identity = new ExternalIdentity("cognito", "subject-that-is-never-logged");
        User activeUser = new User(userId, UserStatus.ACTIVE, now.minusDays(10), now.minusDays(10));
        AccountDeletionJob deletionJob = new AccountDeletionJob(operationId, userId,
                AccountDeletionPhase.DELETE_OBJECTS, now, now, now, null);
        RdsClient rds = mock(RdsClient.class);
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenAnswer(invocation -> {
            DescribeDbInstancesRequest request = invocation.getArgument(0);
            if ("source-id".equals(request.dbInstanceIdentifier())) {
                return DescribeDbInstancesResponse.builder().dbInstances(DBInstance.builder()
                        .dbInstanceIdentifier("source-id").dbiResourceId(sourceResourceId).build()).build();
            }
            return DescribeDbInstancesResponse.builder().dbInstances(DBInstance.builder()
                    .dbInstanceIdentifier("restore-id").dbiResourceId("restored-resource")
                    .dbInstanceStatus("available")
                    .endpoint(software.amazon.awssdk.services.rds.model.Endpoint.builder()
                            .address("restore.example").build())
                    .build()).build();
        });
        var tombstones = mock(AccountDeletionTombstonePort.class);
        when(tombstones.findAll()).thenReturn(List.of(tombstone));
        when(tombstones.fingerprint("cognito", identity.providerSubject())).thenReturn("v1_fingerprint");
        when(tombstones.blocksProvisioning("cognito", identity.providerSubject())).thenReturn(true);
        var deletions = mock(AccountDeletionRepository.class);
        when(deletions.find(operationId)).thenReturn(Optional.empty(), Optional.of(deletionJob),
                Optional.of(deletionJob), Optional.empty());
        when(deletions.findIdentities(userId)).thenReturn(List.of(identity), List.of(), List.of());
        var users = mock(UserRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(activeUser), Optional.empty(), Optional.empty());
        var worker = mock(AccountDeletionWorker.class);
        var bootstrap = mock(RestoreReconciliationBootstrap.class);
        when(bootstrap.ensureJob(tombstone)).thenReturn(Optional.of(operationId));
        Environment environment = mock(Environment.class);
        when(environment.getProperty("CONTRACTFORGE_DB_URL", ""))
                .thenReturn("jdbc:aws-wrapper:postgresql://restore.example:5432/contractforge?sslmode=require");
        RestoreReconciliationRunner runner = new RestoreReconciliationRunner(tombstones, deletions, worker,
                bootstrap, users, rds, Clock.systemUTC(), environment);

        runner.run(new DefaultApplicationArguments("--restore-reconcile=apply", "--confirm-isolated=true",
                "--source-db-resource-id=" + sourceResourceId, "--source-db-instance-identifier=source-id",
                "--target-db-instance-identifier=restore-id"));

        verify(worker).process(operationId);
        verify(tombstones).blocksProvisioning("cognito", identity.providerSubject());
        verify(tombstones).complete(eq(operationId), eq(userId), any());
    }
}
