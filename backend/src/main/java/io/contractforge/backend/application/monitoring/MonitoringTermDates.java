package io.contractforge.backend.application.monitoring;

import java.time.LocalDate;

final class MonitoringTermDates {

    private MonitoringTermDates() {
    }

    static LocalDate endDate(ContractMonitoringService.Terms terms) {
        if (terms == null) {
            return null;
        }
        if (terms.endDate() != null) {
            return terms.endDate();
        }
        if (terms.startDate() == null || terms.contractDuration() == null || terms.contractDuration() <= 0) {
            return null;
        }
        return terms.startDate().plusMonths(terms.contractDuration());
    }
}
