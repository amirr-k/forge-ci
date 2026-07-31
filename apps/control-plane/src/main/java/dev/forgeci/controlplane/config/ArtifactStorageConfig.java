package dev.forgeci.controlplane.config;

import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Wires the S3 client. {@link S3Properties#hasEndpointOverride()} is the only branch between
 * production AWS S3 (default provider chain, virtual-hosted addressing) and an S3-compatible dev
 * service (static credentials, path-style addressing) — everything downstream of the client is
 * identical either way.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class ArtifactStorageConfig {

    @Bean
    public S3Client s3Client(S3Properties props) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(props.getRegion()));
        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.getEndpointOverride()))
                    .forcePathStyle(props.isPathStyleAccess());
            if (!props.getAccessKey().isBlank()) {
                builder.credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        props.getAccessKey(), props.getSecretKey())));
            }
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        S3Client client = builder.build();
        ensureBucket(client, props.getBucket());
        return client;
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Dev/test convenience only — production buckets are provisioned out of band, not created on
     * boot.
     */
    private void ensureBucket(S3Client client, String bucket) {
        try {
            client.headBucket(b -> b.bucket(bucket));
        } catch (RuntimeException notFound) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (BucketAlreadyOwnedByYouException alreadyExists) {
                // race with another instance starting concurrently — the bucket exists either way
            }
        }
    }
}
