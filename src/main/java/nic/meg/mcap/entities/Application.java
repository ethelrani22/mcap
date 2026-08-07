package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nic.meg.mcap.enums.ApplicantType;
import nic.meg.mcap.enums.ApplicationPool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long applicationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id", nullable = false)
    @JsonIgnore
    private Applicant applicant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admission_id", nullable = false)
    private AdmissionWindow admissionWindow;

    @Column(unique = true, nullable = false, length = 50)
    private String applicationNo;

    @Column(nullable = false)
    private LocalDateTime applicationDate;

    /**
     * The moment this application was actually finalized/submitted
     * (payment complete / status flips to SUBMITTED) - as opposed to
     * applicationDate, which is when the row was first created (registration
     * start). REGULAR vs LATE is decided against this timestamp, not
     * applicationDate, since an applicant may start before the original end
     * date but only finish after it.
     */
    private LocalDateTime submittedAt;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean isDocumentsFinalized = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean personalDetailsComplete = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean academicDetailsComplete = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean programmeSelectionComplete = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean paymentComplete = false;

    @Column(precision = 10, scale = 2)
    private BigDecimal amountPaid;

    @Column(length = 100)
    private String transactionId;

    private LocalDateTime paymentTimestamp;

    @Column(length = 20)
    private String applicationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicant_type", nullable = false)
    private ApplicantType applicantType;

    /**
     * Classifies whether this application belongs to the REGULAR pool
     * (submitted on or before the admission window's original end date) or
     * the LATE pool (submitted after the original end date but within the
     * extended end date). Only meaningful once the application has actually
     * been submitted (see {@link #markSubmitted(LocalDateTime)}); before
     * that it holds a placeholder value since the column is NOT NULL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "applicant_pool", nullable = false)
    private ApplicationPool applicantPool = ApplicationPool.LATE;

    @OneToMany(mappedBy = "application", fetch = FetchType.LAZY)
    private List<ApplicantProgrammePreference> applicantProgrammePreferences;

    // ---------------------------------------------------------------
    // Lifecycle hooks
    // ---------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        this.applicationDate = LocalDateTime.now();
        this.applicationStatus = "INCOMPLETE";
        // Placeholder only - not meaningful until markSubmitted() runs at
        // actual final submission. Kept non-null to satisfy the DB constraint.
    }

    /**
     * Recompute pool on every load so that existing rows without the column
     * (null) are resolved correctly until a migration back-fills the column.
     */
    @PostLoad
    protected void onLoad() {
        if (this.applicantPool == null) {
            LocalDateTime effectiveMoment = this.submittedAt != null ? this.submittedAt : this.applicationDate;
            this.applicantPool = resolvePool(effectiveMoment, this.admissionWindow);
        }
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    /**
     * Call this exactly when the application is actually finalized/submitted
     * (payment complete / status -> SUBMITTED). Records the submission
     * timestamp and locks in REGULAR vs LATE against it.
     */
    public void markSubmitted(LocalDateTime submissionTime) {
        this.submittedAt = submissionTime;
        this.applicantPool = resolvePool(submissionTime, this.admissionWindow);
    }

    /**
     * Returns true if this application is eligible for regular rounds of
     * seat allotment (i.e., submitted on or before its admission window's
     * original end date).
     */
    public boolean isRegularRound() {
        return ApplicationPool.REGULAR.equals(this.applicantPool);
    }

    /**
     * REGULAR: submitted on or before the window's original end date
     * (or the current end date, if the window was never extended).
     * LATE: submitted after the original end date but on/before the
     * (extended) end date.
     */
    private static ApplicationPool resolvePool(LocalDateTime dateTime, AdmissionWindow window) {
        if (dateTime == null || window == null) {
            return ApplicationPool.LATE;
        }
        LocalDateTime regularCutoff = window.isExtended() && window.getOriginalEndDate() != null
                ? window.getOriginalEndDate()
                : window.getEndDate();

        return dateTime.isAfter(regularCutoff) ? ApplicationPool.LATE : ApplicationPool.REGULAR;
    }
}