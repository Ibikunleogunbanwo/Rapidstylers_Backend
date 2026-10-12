package com.macrotel.rapidstylers.payment.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/**
 * One payment reconciliation run and the report it produced (see
 * PaymentReconciliationService). Kept so the admin screen can show the latest
 * result, whether the schedule or an admin started it.
 */
@Data
@Entity
@Table(name = "payment_reconciliation_runs")
public class ReconciliationRunEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Server local time, like the report's runAt. */
    @Column(name = "ran_at", nullable = false)
    private LocalDateTime ranAt;
    /** SCHEDULED or MANUAL. */
    @Column(name = "trigger_source", nullable = false, length = 16)
    private String triggerSource;
    @Column(nullable = false)
    private boolean ok;
    @Column(name = "issue_count", nullable = false)
    private int issueCount;
    /** The report object the run returned, as JSON. */
    @Lob
    @Column(name = "report_json", nullable = false, columnDefinition = "LONGTEXT")
    private String reportJson;
}
