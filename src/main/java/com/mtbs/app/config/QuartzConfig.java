package com.mtbs.app.config;

import com.mtbs.billing.scheduler.job.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class QuartzConfig {

    // â”€â”€â”€ Billing Cycle Job â€” Daily at midnight â”€â”€â”€
    @Bean
    public JobDetail billingCycleJobDetail() {
        return JobBuilder.newJob(BillingCycleJob.class)
                .withIdentity("billingCycleJob", "billing")
                .withDescription("Daily billing cycle processing")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger billingCycleTrigger(JobDetail billingCycleJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(billingCycleJobDetail)
                .withIdentity("billingCycleTrigger", "billing")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0 * * ?")
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    // â”€â”€â”€ Subscription Expiry Job â€” Hourly â”€â”€â”€
    @Bean
    public JobDetail subscriptionExpiryJobDetail() {
        return JobBuilder.newJob(SubscriptionExpiryJob.class)
                .withIdentity("subscriptionExpiryJob", "billing")
                .withDescription("Hourly check for expired PAST_DUE subscriptions")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger subscriptionExpiryTrigger(JobDetail subscriptionExpiryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(subscriptionExpiryJobDetail)
                .withIdentity("subscriptionExpiryTrigger", "billing")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    // Subscription Cancel Job
    @Bean
    public JobDetail subscriptionCancelJobDetail() {
        return JobBuilder.newJob(SubscriptionCancelJob.class)
                .withIdentity("subscriptionCancelJob", "billing")
                .withDescription("Hourly check for cancelAtPeriodEnd subscriptions")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger subscriptionCancelTrigger(JobDetail subscriptionCancelJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(subscriptionCancelJobDetail)
                .withIdentity("subscriptionCancelTrigger", "billing")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    // â”€â”€â”€ Payment Retry Job â€” Every 6 hours â”€â”€â”€
    @Bean
    public JobDetail paymentRetryJobDetail() {
        return JobBuilder.newJob(PaymentRetryJob.class)
                .withIdentity("paymentRetryJob", "billing")
                .withDescription("Retry failed payments every 6 hours")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger paymentRetryTrigger(JobDetail paymentRetryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(paymentRetryJobDetail)
                .withIdentity("paymentRetryTrigger", "billing")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 */6 * * ?")
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    // â”€â”€â”€ Trial Expiry Job â€” Daily at midnight â”€â”€â”€
    @Bean
    public JobDetail trialExpiryJobDetail() {
        return JobBuilder.newJob(TrialExpiryJob.class)
                .withIdentity("trialExpiryJob", "billing")
                .withDescription("Daily check for expired trials")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger trialExpiryTrigger(JobDetail trialExpiryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(trialExpiryJobDetail)
                .withIdentity("trialExpiryTrigger", "billing")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0 * * ?")
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    /**
     * Runs daily at 08:00 UTC — fires TRIAL_ENDING_SOON for trials expiring
     * within 3 days. Runs before office hours so users see the warning
     * when they start their day.
     */
    @Bean
    public JobDetail trialEndingSoonJobDetail() {
        return JobBuilder.newJob(TrialEndingSoonJob.class)
                .withIdentity("trialEndingSoonJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger trialEndingSoonTrigger(JobDetail trialEndingSoonJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(trialEndingSoonJobDetail)
                .withIdentity("trialEndingSoonTrigger")
                .withSchedule(
                        CronScheduleBuilder
                                .cronSchedule("0 0 8 * * ?")   // 08:00 UTC daily
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }
}
