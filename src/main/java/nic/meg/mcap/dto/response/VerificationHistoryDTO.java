package nic.meg.mcap.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import nic.meg.mcap.enums.VerificationActionType;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationHistoryDTO {
    private Long id;
    private String instituteName;
    private String programmeName;
    private String roundType;
    private Integer phaseNo;
    private VerificationActionType actionType;
    private String remarks;
    private String changedFields;
    private LocalDateTime performedAt;
}