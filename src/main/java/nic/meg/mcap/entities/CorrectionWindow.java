package nic.meg.mcap.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A correction window lets REGULAR-pool applicants (those who submitted on or
 * before the admission window's original end date) edit their already
 * finalized application after the original window has closed.
 *
 * One admission window has at most one correction window. Re-opening a
 * correction window updates the existing row rather than creating a new one,
 * so history of prior correction periods is not retained here.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
public class CorrectionWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long correctionWindowId;

    @OneToOne(optional = false)
    @JoinColumn(name = "admission_id", nullable = false, unique = true)
    private AdmissionWindow admissionWindow;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public boolean isOpenAt(LocalDateTime when) {
        return when != null && !when.isBefore(startDate) && !when.isAfter(endDate);
    }
}