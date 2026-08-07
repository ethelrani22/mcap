package nic.meg.mcap.dto.response;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CorrectionWindowResponseDTO {

    private String admissionCode;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private boolean currentlyOpen;
}