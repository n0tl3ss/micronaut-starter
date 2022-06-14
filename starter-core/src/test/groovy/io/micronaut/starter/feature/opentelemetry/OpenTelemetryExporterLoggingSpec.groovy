package io.micronaut.starter.feature.opentelemetry

import io.micronaut.starter.ApplicationContextSpec
import io.micronaut.starter.application.ApplicationType
import io.micronaut.starter.feature.Category
import io.micronaut.starter.fixture.CommandOutputFixture
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class OpenTelemetryExporterLoggingSpec extends ApplicationContextSpec implements CommandOutputFixture {

    @Subject
    OpenTelemetryExporterLogging feature = beanContext.getBean(OpenTelemetryExporterLogging)

    void 'tracing-opentelemetry-exporter-logging feature is in the tracing category'() {
        expect:
        feature.category == Category.TRACING
    }

    @Unroll
    void 'feature tracing-opentelemetry-exporter-logging does not support type: #applicationType'(ApplicationType applicationType) {
        expect:
        !feature.supports(applicationType)

        where:
        applicationType << [ApplicationType.CLI]
    }

    @Unroll
    void 'feature tracing-opentelemetry-exporter-logging supports #applicationType'(ApplicationType applicationType) {
        expect:
        feature.supports(applicationType)

        where:
        applicationType << (ApplicationType.values().toList() - ApplicationType.CLI)
    }
}
