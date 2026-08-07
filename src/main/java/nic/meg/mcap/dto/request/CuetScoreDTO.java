package nic.meg.mcap.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class CuetScoreDTO {
    private String applicationNumber;
    private Integer yearOfExam;
    private String rollNumber;
    private List<CuetSubjectScoreDTO> subjectScores = new ArrayList<>();

}