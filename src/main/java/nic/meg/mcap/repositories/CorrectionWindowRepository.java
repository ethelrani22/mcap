package nic.meg.mcap.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import nic.meg.mcap.entities.AdmissionWindow;
import nic.meg.mcap.entities.CorrectionWindow;

public interface CorrectionWindowRepository extends JpaRepository<CorrectionWindow, Long> {

    Optional<CorrectionWindow> findByAdmissionWindow(AdmissionWindow admissionWindow);

    Optional<CorrectionWindow> findByAdmissionWindow_AdmissionCode(String admissionCode);
}