package nic.meg.mcap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Default reservation policy percentages, kept in application.properties so
 * they can be changed without a code deploy once the real Govt. of Meghalaya
 * category breakdown (Major Tribe vs SC vs other-ST vs Unreserved) is
 * finalized for MCAP.
 *
 * PWD (5%) is a horizontal reservation applied on top of every category.
 * The govt reservation values below are a TEMPLATE based on the Meghalaya
 * State Reservation Policy, 1972 (as applied e.g. by NIT Meghalaya's home
 * state quota: 80% Major Tribes, 5% SC & other STs, 15% Unreserved).
 *
 * NOTE: mcap.community_category currently only has 4 generic codes
 * (GEN, OBC, SC, ST) -- there is no separate "Major Tribe" vs "Other ST"
 * category. Until that distinction is added, govtStMajorTribePercentage
 * maps to the existing ST category and govtScOtherStPercentage maps to the
 * existing SC category. Revisit once the real category split is decided.
 */
@Configuration
@ConfigurationProperties(prefix = "mcap.reservation")
public class ReservationPolicyConfig {

    /** Horizontal PwD reservation, applied across all categories. */
    private double pwdPercentage = 5.0;

    private Govt govt = new Govt();

    public double getPwdPercentage() {
        return pwdPercentage;
    }

    public void setPwdPercentage(double pwdPercentage) {
        this.pwdPercentage = pwdPercentage;
    }

    public Govt getGovt() {
        return govt;
    }

    public void setGovt(Govt govt) {
        this.govt = govt;
    }

    public static class Govt {
        // Meghalaya State Reservation Policy, 1972 template -- TEMPLATE VALUES,
        // to be confirmed/adjusted with the actual DHTE/govt notification for MCAP.
        private double stMajorTribePercentage = 80.0;   // maps to category_code 'ST '
        private double scOtherStPercentage = 5.0;        // maps to category_code 'SC '
        private double unreservedPercentage = 15.0;      // maps to category_code 'GEN'

        public double getStMajorTribePercentage() {
            return stMajorTribePercentage;
        }

        public void setStMajorTribePercentage(double stMajorTribePercentage) {
            this.stMajorTribePercentage = stMajorTribePercentage;
        }

        public double getScOtherStPercentage() {
            return scOtherStPercentage;
        }

        public void setScOtherStPercentage(double scOtherStPercentage) {
            this.scOtherStPercentage = scOtherStPercentage;
        }

        public double getUnreservedPercentage() {
            return unreservedPercentage;
        }

        public void setUnreservedPercentage(double unreservedPercentage) {
            this.unreservedPercentage = unreservedPercentage;
        }
    }
}