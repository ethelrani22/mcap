package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_eligibility_window_programme",
                columnNames = {"admission_id", "programme_id"}
        )
)
public class EligibilityCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short eligibilityCriteriaId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admission_id", nullable = false)
    private AdmissionWindow admissionWindow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id", nullable = false)
    private Programme programme;

    /**
     * One or more accepted base qualifications for this programme, EACH with
     * its own minimum overall percentage requirement.
     * An applicant must hold AT LEAST ONE of these to be eligible, and must
     * meet the percentage tied to whichever one they hold.
     * Empty = no qualification gate (all pass this check).
     */
    @OneToMany(mappedBy = "eligibilityCriteria", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<EligibilityQualificationRequirement> qualificationRequirements = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "eligibility_criteria_id")
    private List<EligibilityCategoryRelaxation> categoryRelaxations = new ArrayList<>();

    @Column(name = "is_cuet_required", nullable = false)
    private boolean cuetRequired = false;

    // Unidirectional (Parent owns FK) - This is fine if EligibilityRuleSet doesn't have @ManyToOne
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "eligibility_criteria_id", nullable = false)
    private List<EligibilityRuleSet> ruleSets = new ArrayList<>();

    // ----------------------- THE FIX -----------------------
    // Changed to Bidirectional (Child owns FK)
    // removed @JoinColumn and added mappedBy="eligibilityCriteria"
    @OneToMany(mappedBy = "eligibilityCriteria", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderColumn(name = "merit_order_index")
    private List<MeritRuleSet> meritRuleSets = new ArrayList<>();
    // -------------------------------------------------------

    @Column(name = "tiebreaker_config", length = 4000)
    private String tiebreakerConfig;
}