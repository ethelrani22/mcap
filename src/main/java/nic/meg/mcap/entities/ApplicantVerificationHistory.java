package nic.meg.mcap.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import nic.meg.mcap.enums.VerificationActionType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for institute verification decisions and
 * institute-initiated edits to applicant details.
 *
 * IMPORTANT: rows in this table are NEVER updated or deleted. Every
 * verification action (accept/reject) or applicant-detail edit by an
 * institute creates a brand new row, so the full history survives across
 * every counseling round/phase and remains visible to other institutes
 * the applicant interacts with in later rounds.
 */
@Getter
@Setter
@Entity
@Table(
        name = "applicant_verification_history",
        indexes = {
                @Index(name = "idx_avh_applicant", columnList = "applicant_id"),
                @Index(name = "idx_avh_seat_allotment", columnList = "seat_allotment_id")
        }
)
public class ApplicantVerificationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "applicant_id", nullable = false)
    private Applicant applicant;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    // Nullable: a DETAILS_EDITED event may not be tied to a specific allotment
    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_allotment_id", nullable = true)
    private SeatAllotment seatAllotment;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "institute_id", nullable = false)
    private Institute institute;

    @Column(name = "admission_window_id", nullable = false)
    private Short admissionWindowId;

    @Column(name = "round_type", length = 20)
    private String roundType;

    @Column(name = "phase_no")
    private Integer phaseNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 20, nullable = false)
    private VerificationActionType actionType;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /**
     * For DETAILS_EDITED rows: a JSON snapshot of the fields that changed,
     * e.g. {"phoneNumber":{"old":"...","new":"..."}}. Null for VERIFIED/REJECTED rows.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", columnDefinition = "jsonb")
    private String changedFields;

    @Column(name = "performed_by_user_id", nullable = false)
    private Integer performedByUserId;

    @CreationTimestamp
    @Column(name = "performed_at", nullable = false, updatable = false)
    private LocalDateTime performedAt;
}