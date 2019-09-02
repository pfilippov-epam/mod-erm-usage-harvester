package org.olf.erm.usage.harvester.periodic;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.ext.web.client.WebClient;
import java.util.Date;
import org.folio.okapi.common.XOkapiHeaders;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HarvestTenantJob implements Job {

  private static final Logger log = LoggerFactory.getLogger(HarvestTenantJob.class);
  private String tenantId;

  private Future<String> updateLastTriggeredAt(Context vertxContext, Date fireTime) {
    return PeriodicConfigPgUtil.get(vertxContext, tenantId)
        .compose(
            pc ->
                PeriodicConfigPgUtil.upsert(
                    vertxContext, tenantId, pc.withLastTriggeredAt(fireTime)));
  }

  private void failAndLog(Future future, String message) {
    log.error(message);
    future.fail(message);
  }

  @Override
  public void execute(JobExecutionContext context) {
    Future<String> future = Future.future();
    context.setResult(future);

    Context vertxContext;
    try {
      Object o = context.getScheduler().getContext().get("vertxContext");
      vertxContext = o instanceof Context ? (Context) o : null;
    } catch (SchedulerException e) {
      failAndLog(
          future,
          String.format(
              "Tenant: %s, error getting scheduler context: %s", tenantId, e.getMessage()));
      return;
    }

    if (vertxContext == null) {
      failAndLog(future, String.format("Tenant: %s, error getting vert.x context", tenantId));
      return;
    }

    String okapiUrl = vertxContext.config().getString("okapiUrl");
    WebClient.create(vertxContext.owner())
        .getAbs(okapiUrl + "/erm-usage-harvester/start")
        .putHeader(XOkapiHeaders.TENANT, tenantId)
        .send(
            ar -> {
              if (ar.succeeded()) {
                if (ar.result().statusCode() != 200) {
                  failAndLog(
                      future,
                      String.format(
                          "Tenant: %s, error starting job, received %s %s from start interface: %s",
                          tenantId,
                          ar.result().statusCode(),
                          ar.result().statusMessage(),
                          ar.result().bodyAsString()));
                } else {
                  log.info("Tenant: {}, job started", tenantId);
                  updateLastTriggeredAt(vertxContext, context.getFireTime())
                      .setHandler(
                          ar2 -> {
                            if (ar2.succeeded()) {
                              future.complete();
                            } else {
                              failAndLog(
                                  future,
                                  String.format(
                                      "Tenant: %s, failed updating lastTriggeredAt: %s",
                                      tenantId, ar2.cause().getMessage()));
                            }
                          });
                }
              } else {
                failAndLog(
                    future,
                    String.format(
                        "Tenant: %s, error connecting to start interface: %s",
                        tenantId, ar.cause().getMessage()));
              }
            });
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }
}
