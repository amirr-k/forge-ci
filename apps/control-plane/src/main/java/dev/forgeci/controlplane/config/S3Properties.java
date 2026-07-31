package dev.forgeci.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 (or S3-compatible) settings for remote artifact storage. Leaving {@code endpointOverride}
 * blank targets real AWS S3 with the default credentials provider chain; setting it (Compose dev,
 * tests) switches to a path-style-addressed compatible service with static credentials — the
 * client code never branches on which one it is talking to.
 */
@ConfigurationProperties(prefix = "forge.artifacts.s3")
public class S3Properties {

    private String bucket = "forgeci-artifacts";
    private String region = "us-east-1";
    private String endpointOverride = "";
    private boolean pathStyleAccess = true;
    private String accessKey = "";
    private String secretKey = "";
    private String tempPrefix = "tmp/";
    private String objectPrefix = "artifacts/";
    private Duration tempTtl = Duration.ofHours(1);

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointOverride() {
        return endpointOverride;
    }

    public void setEndpointOverride(String endpointOverride) {
        this.endpointOverride = endpointOverride;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getTempPrefix() {
        return tempPrefix;
    }

    public void setTempPrefix(String tempPrefix) {
        this.tempPrefix = tempPrefix;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public Duration getTempTtl() {
        return tempTtl;
    }

    public void setTempTtl(Duration tempTtl) {
        this.tempTtl = tempTtl;
    }

    public boolean hasEndpointOverride() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }

    /** {@code artifacts/<first two digest chars>/<digest>} — the one bucket-key convention workers reuse in phase 5. */
    public String objectKey(String digest) {
        return objectPrefix + digest.substring(0, 2) + "/" + digest;
    }
}
