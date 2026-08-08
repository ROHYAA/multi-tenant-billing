package legacy.saasbilling.billing.dto;

import legacy.saasbilling.shared.enums.UsageMetric;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageResponse {

    private UsageMetric metric;
    private Long current;
    private Long limit;
    private Long remaining;
    private Double percentUsed;
    private Instant periodStart;
    private Instant periodEnd;
}
