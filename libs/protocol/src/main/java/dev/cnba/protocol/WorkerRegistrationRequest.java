package dev.cnba.protocol;

import java.util.List;

public record WorkerRegistrationRequest(
        String externalId, List<String> capabilities, int maxConcurrency, String versionLabel) {}
