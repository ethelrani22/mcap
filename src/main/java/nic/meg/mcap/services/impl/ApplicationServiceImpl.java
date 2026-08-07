package nic.meg.mcap.services.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.response.ApplicationStatusResponseDTO;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.CorrectionWindow;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.CorrectionWindowRepository;
import nic.meg.mcap.services.ApplicationService;

@Service
@Transactional
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final CorrectionWindowRepository correctionWindowRepository;

    private Application findAndVerifyApplication(Long applicationId, String applicantNo) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new EntityNotFoundException("Application not found with ID: " + applicationId));
        if (!application.getApplicant().getApplicantNo().equals(applicantNo)) {
            throw new SecurityException("Unauthorized access to application " + applicationId);
        }
        return application;
    }

    /**
     * Returns true if a correction window is currently open for this application
     * AND the applicant is in the REGULAR pool (i.e. eligible to use it).
     */
    private boolean isCorrectionWindowOpenFor(Application app) {
        if (!app.isRegularRound()) return false;
        CorrectionWindow cw = correctionWindowRepository
                .findByAdmissionWindow(app.getAdmissionWindow()).orElse(null);
        return cw != null && cw.isOpenAt(LocalDateTime.now());
    }

    @Override
    public ApplicationStatusResponseDTO updatePersonalDetailsStatus(Long applicationId, String applicantNo) {
        Application app = findAndVerifyApplication(applicationId, applicantNo);
        app.setPersonalDetailsComplete(true);
        Application savedApp = applicationRepository.save(app);
        return ApplicationStatusResponseDTO.fromEntity(savedApp, isCorrectionWindowOpenFor(savedApp));
    }

    @Override
    public ApplicationStatusResponseDTO updateAcademicDetailsStatus(Long applicationId, String applicantNo) {
        Application app = findAndVerifyApplication(applicationId, applicantNo);
        app.setAcademicDetailsComplete(true);
        Application savedApp = applicationRepository.save(app);
        return ApplicationStatusResponseDTO.fromEntity(savedApp, isCorrectionWindowOpenFor(savedApp));
    }

    @Override
    public ApplicationStatusResponseDTO updateProgrammeSelectionStatus(Long applicationId, String applicantNo) {
        Application app = findAndVerifyApplication(applicationId, applicantNo);
        app.setProgrammeSelectionComplete(true);
        Application savedApp = applicationRepository.save(app);
        return ApplicationStatusResponseDTO.fromEntity(savedApp, isCorrectionWindowOpenFor(savedApp));
    }

    @Override
    public ApplicationStatusResponseDTO updateDocumentsUploadStatus(Long applicationId, String applicantNo) {
        Application app = findAndVerifyApplication(applicationId, applicantNo);
        app.setDocumentsFinalized(true);
        Application savedApp = applicationRepository.save(app);
        return ApplicationStatusResponseDTO.fromEntity(savedApp, isCorrectionWindowOpenFor(savedApp));
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationStatusResponseDTO getApplicationStatus(Long applicationId, String applicantNo) {
        Application app = findAndVerifyApplication(applicationId, applicantNo);
        return ApplicationStatusResponseDTO.fromEntity(app, isCorrectionWindowOpenFor(app));
    }

    @Override
    public ApplicationStatusResponseDTO confirmPayment(Long applicationId, String applicantNo, BigDecimal amountPaid) {
        Application application = findAndVerifyApplication(applicationId, applicantNo);

        application.setPaymentComplete(true);
        application.setApplicationStatus("SUBMITTED");
        application.setPaymentTimestamp(java.time.LocalDateTime.now());
        application.markSubmitted(application.getPaymentTimestamp());
        if (amountPaid != null) {
            application.setAmountPaid(amountPaid);
        }

        Application savedApplication = applicationRepository.save(application);
        // Correction window is never open immediately after payment (window opens later)
        return ApplicationStatusResponseDTO.fromEntity(savedApplication, false);
    }
}