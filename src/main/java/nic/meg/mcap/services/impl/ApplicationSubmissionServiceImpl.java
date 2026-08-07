package nic.meg.mcap.services.impl;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.audit.AuditingEntityListener;
import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.enums.ApplicantType;
import nic.meg.mcap.enums.ApplicationPool;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.services.ApplicationSubmissionService;
import nic.meg.mcap.services.ApplicationSubmittedEvent;
import nic.meg.mcap.services.EligibilityCalculationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationSubmissionServiceImpl implements ApplicationSubmissionService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationEventPublisher eventPublisher;

    // Inject the eligibility service right here
    private final EligibilityCalculationService eligibilityCalculationService;
    private static final Logger logger = LoggerFactory.getLogger(ApplicationSubmissionServiceImpl.class);


    @Override
    @Transactional
    public void finalizeApplicationSubmission(Application app) {

        // 1. Determine Applicant Type
        //
        // An applicant qualifies as WITH_ENTRANCE (CUET route) only when they
        // have entered at least one actual subject score. This guards against:
        //   (a) Toggling hasCuet = true but never filling in scores at all
        //   (b) Saving a CuetScore header row (applicationNumber/rollNumber)
        //       but leaving the subject scores table empty
        //
        // Both edge cases fall through to WITHOUT_ENTRANCE (NON-CUET route).
        Applicant applicant = app.getApplicant();

        boolean hasValidCuet = applicant.getCuetScore() != null
                && applicant.getCuetScore().getApplicationNumber() != null
                && !applicant.getCuetScore().getApplicationNumber().isBlank()
                && applicant.getCuetScore().getSubjectScores() != null
                && !applicant.getCuetScore().getSubjectScores().isEmpty();

        boolean hasValidJee = applicant.getJeeScore() != null
                && applicant.getJeeScore().getApplicationNumber() != null
                && !applicant.getJeeScore().getApplicationNumber().isBlank();

        boolean hasEntranceScore = hasValidCuet || hasValidJee;

        app.setApplicantType(hasEntranceScore ? ApplicantType.WITH_ENTRANCE : ApplicantType.WITHOUT_ENTRANCE);

        // 2. Update Statuses
        app.setPaymentComplete(true);
        app.setApplicationStatus("SUBMITTED");
        app.markSubmitted(java.time.LocalDateTime.now());

        applicationRepository.save(app);

        // 3. Fire the Background Event
        eventPublisher.publishEvent(new ApplicationSubmittedEvent(this, app.getApplicationId()));
    }

    // ========================================================================
    // THE BACKGROUND LISTENER IS RIGHT HERE IN THE SAME FILE!
    // ========================================================================

    @Async // Runs safely in the background
    @EventListener
    public void handleApplicationSubmitted(ApplicationSubmittedEvent event) {

        // Fetch fresh from DB to ensure we have the latest state
        Application app = applicationRepository.findById(event.getApplicationId()).orElse(null);

        // Enforce the rule: Only check if fully submitted and paid
        if (app != null && app.isPaymentComplete() && "SUBMITTED".equals(app.getApplicationStatus())) {

            // LATE applicants are excluded from all regular admission rounds.
            // Eligibility is only meaningful (and should only be stored) for
            // REGULAR applicants — those who submitted on or before the
            // admission window's original end date.
            if (!ApplicationPool.REGULAR.equals(app.getApplicantPool())) {
                logger.info("Skipping eligibility calculation for LATE applicant: applicationId={}",
                        app.getApplicationId());
                return;
            }

            try {
                eligibilityCalculationService.calculateAndSaveEligibility(app);
            } catch (Exception e) {
                logger.info("Error occurred while processing request");
            }
        }
    }
}