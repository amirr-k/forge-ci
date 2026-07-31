package dev.forgeci.controlplane.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public guest endpoints (contracts.md#apis). Every request only ever operates on the fixed bundled
 * sample repo and an allowlisted scenario id — never a visitor-supplied path, command, or image
 * (product-and-demo.md#public-demo-safety).
 */
@RestController
public class DemoController {

    private final DemoScenarioService demoScenarioService;
    private final DemoGuestGuard guard;

    public DemoController(DemoScenarioService demoScenarioService, DemoGuestGuard guard) {
        this.demoScenarioService = demoScenarioService;
        this.guard = guard;
    }

    @PostMapping("/api/demo/builds")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoBuildResponse createBuild(
            @Valid @RequestBody DemoBuildRequest request, HttpServletRequest httpRequest) {
        if (!guard.tryConsumeRateLimit(clientKey(httpRequest))) {
            throw new DemoBusyException(
                    "you're demoing a bit fast — please wait a moment and try again");
        }
        DemoScenario scenario = DemoScenario.fromScriptId(request.scenario());
        return demoScenarioService.startBuild(scenario, request.workerCount());
    }

    @PostMapping("/api/demo/builds/{id}/crash-worker")
    public Map<String, Long> crashWorker(@PathVariable("id") Long buildId) {
        long workerId = demoScenarioService.crashWorker(buildId);
        return Map.of("workerId", workerId);
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
