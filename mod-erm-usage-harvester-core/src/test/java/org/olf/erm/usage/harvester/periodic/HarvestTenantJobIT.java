package org.olf.erm.usage.harvester.periodic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.io.Resources;
import freemarker.template.TemplateException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import org.folio.rest.jaxrs.model.PeriodicConfig;
import org.folio.rest.jaxrs.model.PeriodicConfig.PeriodicInterval;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.ddlgen.Schema;
import org.folio.rest.persist.ddlgen.SchemaMaker;
import org.folio.rest.persist.ddlgen.TenantOperation;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.KeyMatcher;
import org.quartz.listeners.JobListenerSupport;

@RunWith(VertxUnitRunner.class)
public class HarvestTenantJobIT {

  public static final String START_PATH = "/erm-usage-harvester/start";
  private static Vertx vertx;
  private static Context vertxContext;
  private static Scheduler scheduler;
  private static final String TENANT = "tnanet";

  private static PeriodicConfig config =
      new PeriodicConfig()
          .withStartAt(Date.from(Instant.now()))
          .withPeriodicInterval(PeriodicInterval.DAILY);

  private static final JobKey jobKey = new JobKey(TENANT);
  private static JobDetail job =
      JobBuilder.newJob()
          .ofType(HarvestTenantJob.class)
          .usingJobData("tenantId", TENANT)
          .withIdentity(jobKey)
          .storeDurably(true)
          .build();

  @ClassRule
  public static WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

  @Rule public Timeout timeout = Timeout.seconds(5);

  @BeforeClass
  public static void beforeClass(TestContext context) throws IOException, TemplateException {
    Async async = context.async();
    vertx = Vertx.vertx();
    vertxContext = vertx.getOrCreateContext();
    vertxContext.config().put("okapiUrl", "http://localhost:" + wireMockRule.port());
    PostgresClient.getInstance(vertx).startEmbeddedPostgres();

    String tableInput =
        Resources.toString(
            Resources.getResource("templates/db_scripts/schema.json"), StandardCharsets.UTF_8);
    SchemaMaker sMaker =
        new SchemaMaker(TENANT, PostgresClient.getModuleName(), TenantOperation.CREATE, null, null);
    sMaker.setSchema(new ObjectMapper().readValue(tableInput, Schema.class));
    String sqlFile = sMaker.generateDDL();

    Future<List<String>> createSchema = Future.future();
    PostgresClient.getInstance(vertx).runSQLFile(sqlFile, true, createSchema.completer());

    Future<String> createConfig = Future.future();
    createSchema
        .compose(
            l ->
                PeriodicConfigPgUtil.upsert(vertxContext, TENANT, config)
                    .setHandler(createConfig.completer()),
            createConfig)
        .setHandler(
            ar -> {
              if (ar.succeeded()) {
                async.complete();
              } else {
                context.fail(ar.cause());
              }
            });
  }

  @AfterClass
  public static void afterClass() {
    PostgresClient.stopEmbeddedPostgres();
  }

  @Before
  public void before() throws SchedulerException {
    WireMock.resetAllRequests();
    scheduler = StdSchedulerFactory.getDefaultScheduler();
    scheduler.clear();
    scheduler.getContext().put("vertxContext", vertxContext);
    scheduler.addJob(job, true);
    scheduler.start();
  }

  @Test
  public void testContextNull(TestContext context) throws SchedulerException {
    Async async = context.async();
    scheduler.getContext().put("vertxContext", null);
    scheduler
        .getListenerManager()
        .addJobListener(
            new JobWasExecutedListener(
                ar -> {
                  if (ar.failed()) {
                    context.verify(
                        v -> assertThat(ar.cause().getMessage()).contains("vert.x context"));
                    async.complete();
                  } else {
                    context.fail(ar.cause());
                  }
                }),
            KeyMatcher.keyEquals(jobKey));
    scheduler.triggerJob(jobKey);
  }

  @Test
  public void testStartNoConnection(TestContext context) throws SchedulerException {
    stubFor(
        get(urlPathEqualTo(START_PATH))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    Async async = context.async();
    scheduler
        .getListenerManager()
        .addJobListener(
            new JobWasExecutedListener(
                ar -> {
                  if (ar.failed()) {
                    context.verify(
                        v -> {
                          assertThat(ar.cause().getMessage()).contains("error connecting");
                          verify(1, getRequestedFor(urlPathEqualTo(START_PATH)));
                        });
                    async.complete();
                  } else {
                    context.fail(ar.cause());
                  }
                }),
            KeyMatcher.keyEquals(jobKey));
    scheduler.triggerJob(jobKey);
  }

  @Test
  public void testStartError(TestContext context) throws SchedulerException {
    stubFor(get(urlPathEqualTo(START_PATH)).willReturn(notFound()));
    Async async = context.async();
    scheduler
        .getListenerManager()
        .addJobListener(
            new JobWasExecutedListener(
                ar -> {
                  if (ar.failed()) {
                    context.verify(
                        v -> {
                          assertThat(ar.cause().getMessage()).contains("received 404");
                          verify(1, getRequestedFor(urlPathEqualTo(START_PATH)));
                        });
                    PeriodicConfigPgUtil.get(vertxContext, TENANT)
                        .setHandler(
                            ar2 -> {
                              if (ar2.succeeded()) {
                                context.verify(
                                    v ->
                                        assertThat(ar2.result().withId(null))
                                            .isEqualToComparingFieldByFieldRecursively(config));
                                async.complete();
                              } else {
                                context.fail(ar2.cause());
                              }
                            });
                  } else {
                    context.fail(ar.cause());
                  }
                }),
            KeyMatcher.keyEquals(jobKey));
    scheduler.triggerJob(jobKey);
  }

  @Test
  public void testStartOk(TestContext context) throws SchedulerException {
    stubFor(get(urlPathEqualTo(START_PATH)).willReturn(ok()));
    Async async = context.async();
    scheduler
        .getListenerManager()
        .addJobListener(
            new JobWasExecutedListener(
                ar -> {
                  if (ar.succeeded()) {
                    context.verify(v -> verify(1, getRequestedFor(urlPathEqualTo(START_PATH))));
                    PeriodicConfigPgUtil.get(vertxContext, TENANT)
                        .setHandler(
                            ar2 -> {
                              if (ar2.succeeded()) {
                                context.verify(
                                    v -> assertThat(ar2.result().getLastTriggeredAt()).isNotNull());
                                async.complete();
                              } else {
                                context.fail(ar2.cause());
                              }
                            });
                  } else {
                    context.fail(ar.cause());
                  }
                }),
            KeyMatcher.keyEquals(jobKey));
    scheduler.triggerJob(jobKey);
  }

  private static class JobWasExecutedListener extends JobListenerSupport {

    private Handler<AsyncResult<String>> handler;

    @Override
    public String getName() {
      return "JobWasExecutedListener";
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
      Future<String> result = (Future<String>) context.getResult();
      result.setHandler(handler);
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
      System.out.println("veto");
    }

    public JobWasExecutedListener(Handler<AsyncResult<String>> handler) {
      this.handler = handler;
    }
  }
}
