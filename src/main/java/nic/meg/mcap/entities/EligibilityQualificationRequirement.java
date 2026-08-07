package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (eligibility criteria, accepted qualification), each carrying
 * its own minimum overall percentage requirement.
 *
 * Replaces the old model of a single ManyToMany "acceptedQualifications" list
 * plus one shared EligibilityCriteria.minOverallPercentage.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "eligibility_qualification_requirement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_criteria_qualification",
                columnNames = {"eligibility_criteria_id", "qualification_id"}
        )
)
public class EligibilityQualificationRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eligibility_criteria_id", nullable = false)
    private EligibilityCriteria eligibilityCriteria;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qualification_id", nullable = false)
    private Qualification qualification;

    /**
     * Minimum overall percentage required for THIS specific qualification.
     * Null = no percentage gate for this qualification (only presence of the
     * qualification itself is required).
     */
    @Column(name = "min_percentage")
    private Double minPercentage;
}