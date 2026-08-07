package nic.meg.mcap.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CounselingRoundResponseDTO {

    private Long admissionWindowId;
    private String stepName;

    // NEW
    private String roundType;   // "CUET" / "NON_CUET"
    private Integer phaseNo;    // 1..N

    private String status;      // PENDING/ACCEPTED/REJECTED/NOT_ALLOTTED
    private Long allotmentId;
    private boolean slideFeePaid;

    // Backward-compatible constructor for existing 5-arg call sites (defaults slideFeePaid to false)
    public CounselingRoundResponseDTO(Long admissionWindowId, String stepName, String roundType,
                                      Integer phaseNo, String status, Long allotmentId) {
        this.admissionWindowId = admissionWindowId;
        this.stepName = stepName;
        this.roundType = roundType;
        this.phaseNo = phaseNo;
        this.status = status;
        this.allotmentId = allotmentId;
        this.slideFeePaid = false;
    }
}