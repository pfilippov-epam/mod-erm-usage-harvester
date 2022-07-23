package org.olf.erm.usage.harvester.periodic;

import static org.olf.erm.usage.harvester.periodic.AbstractHarvestJob.DATAKEY_PROVIDER_ID;
import static org.olf.erm.usage.harvester.periodic.AbstractHarvestJob.DATAKEY_TENANT;
import static org.olf.erm.usage.harvester.periodic.AbstractHarvestJob.DATAKEY_TOKEN;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import org.folio.rest.jaxrs.model.PeriodicConfig;
import org.quartz.CronScheduleBuilder;
import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchedulingUtil {

  private static final Logger log = LoggerFactory.getLogger(SchedulingUtil.class);

  public static void scheduleProviderJob(
      Scheduler scheduler, String tenantId, String token, String providerId)
      throws SchedulerException {
    JobKey jobKey = new JobKey(providerId, tenantId);
    if (scheduler.checkExists(jobKey)) {
      throw new SchedulerException(
          "A job for provider with id '" + providerId + "' is already scheduled/running");
    } else {
      scheduler.scheduleJob(
          JobBuilder.newJob(HarvestProviderJob.class)
              .withIdentity(jobKey)
              .usingJobData(DATAKEY_TENANT, tenantId)
              .usingJobData(DATAKEY_TOKEN, token)
              .usingJobData(DATAKEY_PROVIDER_ID, providerId)
              .build(),
          TriggerBuilder.newTrigger().startNow().build());
    }
  }

  public static void scheduleTenantJob(Scheduler scheduler, String tenantId, String token)
      throws SchedulerException {
    JobKey jobKey = new JobKey(tenantId, tenantId);
    if (scheduler.checkExists(jobKey) || scheduler.getJobGroupNames().contains(tenantId)) {
      throw new SchedulerException(
          "Harvesting for tenant '" + tenantId + "' is already in progress");
    } else {
      scheduler.scheduleJob(
          JobBuilder.newJob(HarvestTenantManualJob.class)
              .withIdentity(jobKey)
              .usingJobData(DATAKEY_TENANT, tenantId)
              .usingJobData(DATAKEY_TOKEN, token)
              .build(),
          TriggerBuilder.newTrigger().startNow().build());
    }
  }

  public static void createOrUpdateJob(PeriodicConfig config, String tenantId) {
    try {
      createOrUpdateJob(StdSchedulerFactory.getDefaultScheduler(), config, tenantId);
    } catch (SchedulerException e) {
      log.error("Tenant: {}, unable to get default scheduler: {}", tenantId, e.getMessage());
    }
  }

  public static void createOrUpdateJob(
      Scheduler scheduler, PeriodicConfig config, String tenantId) {
    if (config == null) {
      log.info("Tenant: {}, No PeriodicConfig present", tenantId);
      return;
    }
    JobDetail job =
        JobBuilder.newJob()
            .ofType(HarvestTenantJob.class)
            .usingJobData("tenantId", tenantId)
            .withIdentity(new JobKey(tenantId))
            .build();

    Trigger trigger = createTrigger(tenantId, config);
    if (trigger != null) {
      try {
        if (scheduler.checkExists(new JobKey(tenantId))) {
          scheduler.rescheduleJob(new TriggerKey(tenantId), trigger);
          log.info(
              "Tenant: {}, Updated job trigger, next trigger: {}",
              tenantId,
              trigger.getNextFireTime());
        } else {
          scheduler.scheduleJob(job, trigger);
          log.info(
              "Tenant: {}, Scheduled new job, next trigger: {}",
              tenantId,
              trigger.getNextFireTime());
        }
      } catch (SchedulerException e) {
        log.error("Tenant: {}, Error scheduling job for tenant, {}", tenantId, e.getMessage());
      }
    } else {
      log.error("Tenant: {}, Error creating job trigger", tenantId);
    }
  }

  public static void deleteJob(String tenantId) {
    try {
      deleteJob(StdSchedulerFactory.getDefaultScheduler(), tenantId);
    } catch (SchedulerException e) {
      log.error("Tenant: {}, unable to get default scheduler: {}", tenantId, e.getMessage());
    }
  }

  public static void deleteJob(Scheduler scheduler, String tenantId) {
    JobKey jobKey = new JobKey(tenantId);
    try {
      if (scheduler.checkExists(jobKey)) {
        scheduler.deleteJob(jobKey);
        log.info("Tenant: {}, removed job from schedule", tenantId);
      } else log.warn("Tenant: {}, no scheduled job found", tenantId);
    } catch (SchedulerException e) {
      log.error("Tenant: {}, error deleting job: {}", tenantId, e.getMessage());
    }
  }

  private static Trigger createTrigger(String tenantId, PeriodicConfig config) {
    LocalDateTime start =
        config.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    CronScheduleBuilder cronScheduleBuilder;
    switch (config.getPeriodicInterval()) {
      case DAILY:
        cronScheduleBuilder =
            CronScheduleBuilder.dailyAtHourAndMinute(start.getHour(), start.getMinute());
        break;
      case WEEKLY:
        cronScheduleBuilder =
            CronScheduleBuilder.cronSchedule(
                String.format(
                    "0 %d %d ? * %s",
                    start.getMinute(),
                    start.getHour(),
                    start.getDayOfWeek().toString().substring(0, 3)));
        break;
      case MONTHLY:
        if (start.getDayOfMonth() > 28) {
          cronScheduleBuilder =
              CronScheduleBuilder.cronSchedule(
                  String.format("0 %d %d L * ?", start.getMinute(), start.getHour()));
        } else {
          cronScheduleBuilder =
              CronScheduleBuilder.monthlyOnDayAndHourAndMinute(
                  start.getDayOfMonth(), start.getHour(), start.getMinute());
        }
        break;
      default:
        return null;
    }

    Date startAt =
        (config.getLastTriggeredAt() != null
                && config.getLastTriggeredAt().compareTo(config.getStartAt()) >= 0)
            ? DateBuilder.nextGivenSecondDate(config.getLastTriggeredAt(), 1)
            : config.getStartAt();

    return TriggerBuilder.newTrigger()
        .startAt(startAt)
        .withIdentity(new TriggerKey(tenantId))
        .withSchedule(cronScheduleBuilder.withMisfireHandlingInstructionFireAndProceed())
        .build();
  }

  private SchedulingUtil() {}
}
