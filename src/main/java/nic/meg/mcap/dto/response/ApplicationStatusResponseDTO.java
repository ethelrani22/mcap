package nic.meg.mcap.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import nic.meg.mcap.entities.Application;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationStatusResponseDTO {

    private boolean personalDetailsComplete;
    private boolean academicDetailsComplete;
    private boolean programmeSelectionComplete;
    private boolean documentsUploadComplete;
    private boolean paymentComplete;
    private boolean isFormLocked;

    public static ApplicationStatusResponseDTO fromEntity(Application app) {
        return fromEntity(app, false);
    }

    /**
     * @param correctionWindowOpen pass {@code true} when a correction window is
     *                             currently open for this application's admission
     *                             window AND the applicant is in the REGULAR pool.
     *                             When true, a paid application is unlocked so
     *                             the applicant can make corrections.
     */
    public static ApplicationStatusResponseDTO fromEntity(Application app, boolean correctionWindowOpen) {
        if (app == null) {
            return ApplicationStatusResponseDTO.builder().build();
        }
        // Locked if paid, UNLESS a correction window is currently open for this applicant
        boolean locked = app.isPaymentComplete() && !correctionWindowOpen;
        return ApplicationStatusResponseDTO.builder()
                .personalDetailsComplete(app.isPersonalDetailsComplete())
                .academicDetailsComplete(app.isAcademicDetailsComplete())
                .programmeSelectionComplete(app.isProgrammeSelectionComplete())
                .documentsUploadComplete(app.isDocumentsFinalized())
                .paymentComplete(app.isPaymentComplete())
                .isFormLocked(locked)
                .build();
    }
}