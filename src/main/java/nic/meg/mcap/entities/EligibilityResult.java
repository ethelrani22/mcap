package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "eligibility_result")
public class EligibilityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer eligibilityResultId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "programme_id", nullable = false)
    private Programme programme;


    // Combined/display flag = isEligibleCuet OR isEligibleNonCuet. Kept for backward
    // compatibility with existing displays/reports that just want a single yes/no.
    // Do NOT use this for round-specific matching queries — use the two flags below.
    @Column(nullable = false)
    private Boolean isEligible;

    // True only if the applicant passes at least one CUET-only rule set (no NON-CUET
    // fallback). This is the ONLY flag that should gate CUET-round merit list inclusion
    // for WITH_ENTRANCE applicants.
    @Column(nullable = false)
    private Boolean isEligibleCuet;

    // True if the applicant passes at least one NON-CUET (or mixed) rule set. Used for
    // NON-CUET round merit list inclusion (WITHOUT_ENTRANCE applicants) and for the
    // CUET-to-NON-CUET carryover of WITH_ENTRANCE applicants who never accepted a CUET
    // seat — per admission policy, NON-CUET round doesn't check CUET rules at all.
    @Column(nullable = false)
    private Boolean isEligibleNonCuet;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    private LocalDateTime calculatedAt;

    @PrePersist
    protected void onCreate() {
        this.calculatedAt = LocalDateTime.now();
    }
}