package com.vistay.property.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Portfolio gauges used by the business overview dashboard. */
@Component
public class PropertyBusinessMetrics {
    private final JdbcClient jdbc;
    private final AtomicLong cities = new AtomicLong();
    private final AtomicLong areas = new AtomicLong();
    private final AtomicLong buildings = new AtomicLong();
    private final Map<String, AtomicLong> units = new LinkedHashMap<>();
    private final Counter unitCreated;

    public PropertyBusinessMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("vistay_property_cities", cities, AtomicLong::get).description("Active property cities").register(registry);
        Gauge.builder("vistay_property_areas", areas, AtomicLong::get).description("Active property areas").register(registry);
        Gauge.builder("vistay_property_buildings", buildings, AtomicLong::get).description("Active buildings").register(registry);
        for (var status : new String[] {"ONBOARDING", "ACTIVE", "MAINTENANCE", "SUSPENDED", "OFFBOARDING", "INACTIVE"}) {
            var count = new AtomicLong();
            units.put(status, count);
            Gauge.builder("vistay_property_units", count, AtomicLong::get)
                .description("Current units by operational status").tag("status", status).register(registry);
        }
        unitCreated = Counter.builder("vistay_property_unit_creations")
            .description("Successful property unit creations").register(registry);
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${vistay.observability.business-metrics-refresh:60s}")
    public void refresh() {
        try {
            cities.set(count("SELECT count(*) FROM cities WHERE active"));
            areas.set(count("SELECT count(*) FROM areas WHERE active"));
            buildings.set(count("SELECT count(*) FROM buildings WHERE active"));
            for (var entry : units.entrySet()) {
                entry.getValue().set(countByStatus(entry.getKey()));
            }
        } catch (RuntimeException ignored) {
            // Keep workload availability independent from metrics refresh.
        }
    }

    public void unitCreated() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            unitCreated.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                unitCreated.increment();
            }
        });
    }

    private long count(String query) {
        return jdbc.sql(query).query((rs, rowNum) -> rs.getLong(1)).single();
    }

    private long countByStatus(String status) {
        return jdbc.sql("SELECT count(*) FROM properties WHERE operational_status = :status")
            .param("status", status).query((rs, rowNum) -> rs.getLong(1)).single();
    }
}
