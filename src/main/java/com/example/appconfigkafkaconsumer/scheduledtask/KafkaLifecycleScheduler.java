package com.example.appconfigkafkaconsumer.scheduledtask;

import com.azure.spring.cloud.appconfiguration.config.AppConfigurationRefresh;
import com.azure.spring.cloud.appconfiguration.config.AppConfigurationStoreHealth;
import com.azure.spring.cloud.feature.management.FeatureManager;
import com.example.appconfigkafkaconsumer.consumer.FeatureFlagKafkaConsumer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KafkaLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(KafkaLifecycleScheduler.class);

    private final KafkaListenerEndpointRegistry registry;
    private final FeatureManager featureManager;
    private final AppConfigurationRefresh appConfigurationRefresh;
    private final Meter meter;

    @Value("${app.feature-flag.name}")
    private String featureFlagName;

    @Value("${app.feature-flag.default-enabled}")
    private boolean defaultEnabled;

    public KafkaLifecycleScheduler(KafkaListenerEndpointRegistry registry, FeatureManager featureManager,
            AppConfigurationRefresh appConfigurationRefresh, Meter meter) {
        this.registry = registry;
        this.featureManager = featureManager;
        this.appConfigurationRefresh = appConfigurationRefresh;
        this.meter = meter;
    }

    @PostConstruct
    public void initializeMetrics() {
        // register metric for kafka consumer running status
        meter.gaugeBuilder("kafka_consumer_running")
                .setDescription("Reports 1 if the Kafka consumer is running, 0 otherwise")
                .setUnit("1")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    MessageListenerContainer container = registry
                            .getListenerContainer(FeatureFlagKafkaConsumer.LISTENER_ID);
                    long status = (container != null && container.isRunning()) ? 1L : 0L;
                    measurement.record(status, Attributes.of(
                            AttributeKey.stringKey("listener.id"), FeatureFlagKafkaConsumer.LISTENER_ID,
                            AttributeKey.stringKey("listener.group"), container.getGroupId()));
                    log.info("Reporting Kafka consumer status gauge: {}", status);
                });

        // register metric for feature flag status as seen by instance
        meter.gaugeBuilder("feature_flag_status")
                .setDescription("Reports 1 if kafka consumer enabled feature flag is true, 0 otherwise")
                .setUnit("1")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    long status = (featureManager != null && featureManager.isEnabledAsync(featureFlagName).block())
                            ? 1L
                            : 0L;
                    measurement.record(status);
                    log.info("Reporting feature flag status gauge: {}", status);
                });
    }

    // Runs every 10 seconds to check flag status
    @Scheduled(fixedDelay = 10000)
    public void evaluateFeatureFlag() {

        MessageListenerContainer container = registry.getListenerContainer(FeatureFlagKafkaConsumer.LISTENER_ID);
        if (container == null) {
            log.warn("Kafka listener container '{}' not found in registry", FeatureFlagKafkaConsumer.LISTENER_ID);
            return;
        }

        // Trigger a refresh check from Azure App Configuration
        // This method call will not tell us whether the App Configuration resource was
        // reachable or not.
        appConfigurationRefresh.refreshConfigurations().block();

        // check if at least one App Configuration store is reachable
        boolean useDefaultValue = appConfigurationRefresh.getAppConfigurationStoresHealth().values()
                .stream().noneMatch(health -> health == AppConfigurationStoreHealth.UP);

        boolean isEnabled;
        if (useDefaultValue) {
            log.warn("Using default value for feature flag '{}': {}", featureFlagName, defaultEnabled);
            isEnabled = defaultEnabled;
        } else {
            // Evaluates based on Azure App Configuration
            isEnabled = featureManager.isEnabledAsync(featureFlagName).block();
            log.info("Feature flag '{}' evaluated to: {}", featureFlagName, isEnabled);
        }

        boolean isRunning = container.isRunning();

        if (isEnabled && !isRunning) {
            log.info("Feature flag '{}' is ON, but Kafka listener is STOPPED. Starting listener...", featureFlagName);
            container.start();
        } else if (!isEnabled && isRunning) {
            log.info("Feature flag '{}' is OFF, but Kafka listener is RUNNING. Stopping listener...", featureFlagName);
            container.stop();
        } else {
            // Doing nothing, state matches flag
            log.info("Kafka listener state matches feature flag. (Flag: ON={}, Running={})", isEnabled, isRunning);
        }
    }
}
