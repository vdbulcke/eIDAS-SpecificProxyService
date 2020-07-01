package ee.ria.eidas.proxy.specific.monitoring;

import ee.ria.eidas.proxy.specific.monitoring.health.TruststoreHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.Double.valueOf;
import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static java.time.Instant.ofEpochMilli;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

@Component
@Endpoint(id = "heartbeat", enableByDefault = false)
public class ApplicationHealthEndpoint {

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Autowired
    private GitProperties gitProperties;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TruststoreHealthIndicator truststoreHealthIndicator;

    @ReadOperation(produces = "application/json")
    public ResponseEntity<Map<String, Object>> health() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.ok().headers(headers).body(getHealthDetails());
    }

    private Map<String, Object> getHealthDetails() {
        Map<String, Status> healthIndicatorStatuses = getHealthIndicatorStatuses();
        Map<String, Object> details = new HashMap<>();
        details.put("status", getAggregatedStatus(healthIndicatorStatuses).getCode());
        details.put("name", buildProperties.getName());
        details.put("version", buildProperties.getVersion());
        details.put("buildTime", buildProperties.getTime());
        details.put("commitId", gitProperties.getCommitId());
        details.put("commitBranch", gitProperties.getBranch());
        details.put("currentTime", now());
        details.computeIfAbsent("startTime", v -> getServiceStartTime());
        details.computeIfAbsent("upTime", v -> getServiceUpTime());
        details.computeIfAbsent("warnings", v -> getTrustStoreWarnings());
        details.put("dependencies", getFormatedStatuses(healthIndicatorStatuses));
        return details;
    }

    private List<String> getTrustStoreWarnings() {
        List<String> certificateExpirationWarnings = truststoreHealthIndicator.getCertificateExpirationWarnings();
        return certificateExpirationWarnings.isEmpty() ? null : certificateExpirationWarnings;
    }

    private String getServiceStartTime() {
        TimeGauge startTime = meterRegistry.find("process.start.time").timeGauge();
        return startTime != null ? ofEpochMilli(valueOf(startTime.value(MILLISECONDS)).longValue()).toString() : null;
    }

    private String getServiceUpTime() {
        TimeGauge upTime = meterRegistry.find("process.uptime").timeGauge();
        return upTime != null ? ofSeconds(valueOf(upTime.value(SECONDS)).longValue()).toString() : null;
    }

    private Map<String, Status> getHealthIndicatorStatuses() {
        return healthContributorRegistry.stream()
                .filter(hc -> hc.getContributor() instanceof HealthIndicator)
                .collect(Collectors.toMap(NamedContributor::getName,
                        healthContributorNamedContributor -> ((HealthIndicator) healthContributorNamedContributor
                                .getContributor()).health().getStatus()));
    }

    private Status getAggregatedStatus(Map<String, Status> healthIndicatorStatuses) {
        Optional<Status> anyNotUp = healthIndicatorStatuses.values().stream()
                .filter(status -> !Status.UP.equals(status))
                .findAny();
        return anyNotUp.isPresent() ? Status.DOWN : Status.UP;
    }

    private List<HashMap<String, String>> getFormatedStatuses(Map<String, Status> healthIndicatorStatuses) {
        return healthIndicatorStatuses.entrySet().stream()
                .map(healthIndicator -> new HashMap<String, String>() {{
                    put("name", healthIndicator.getKey());
                    put("status", healthIndicator.getValue().getCode());
                }}).collect(toList());
    }
}
