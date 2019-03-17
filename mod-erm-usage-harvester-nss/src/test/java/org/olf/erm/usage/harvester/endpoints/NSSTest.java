package org.olf.erm.usage.harvester.endpoints;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.io.Resources;
import io.vertx.core.Future;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.io.File;
import java.io.IOException;
import javax.xml.bind.JAXB;
import org.folio.rest.jaxrs.model.AggregatorSetting;
import org.folio.rest.jaxrs.model.UsageDataProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.niso.schemas.counter.Report;
import org.niso.schemas.sushi.counter.CounterReportResponse;
import org.olf.erm.usage.counter41.Counter4Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(VertxUnitRunner.class)
public class NSSTest {

  @Rule public Timeout timeoutRule = Timeout.seconds(5);
  @Rule public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());
  @Rule public RunTestOnContext ctx = new RunTestOnContext();

  private static final Logger LOG = LoggerFactory.getLogger(NSSTest.class);
  private UsageDataProvider provider;
  private AggregatorSetting aggregator;

  private static final String reportType = "JR1";
  private static final String endDate = "2016-03-31";
  private static final String beginDate = "2016-03-01";

  @Before
  public void setup() throws IOException {
    provider =
        new ObjectMapper()
            .readValue(
                new File(Resources.getResource("__files/usage-data-provider.json").getFile()),
                UsageDataProvider.class);
    aggregator =
        new ObjectMapper()
            .readValue(
                new File(Resources.getResource("__files/aggregator-setting.json").getFile()),
                AggregatorSetting.class)
            .withServiceUrl(wireMockRule.url("mockedAPI"));
    LOG.info("Setting Aggregator URL to: " + aggregator.getServiceUrl());
  }

  @Test
  public void fetchSingleReportWithAggregatorValidReport(TestContext context) {

    final ServiceEndpoint sep = ServiceEndpoint.create(provider, aggregator);
    final String url = ((NSS) sep).buildURL(reportType, beginDate, endDate);

    LOG.info("Creating stub for: " + url);
    stubFor(
        get(urlEqualTo(url.replaceAll(wireMockRule.url(""), "/")))
            .willReturn(aResponse().withBodyFile("nss-report-2016-03.xml")));

    Async async = context.async();
    Future<String> fetchSingleReport = sep.fetchSingleReport(reportType, beginDate, endDate);
    fetchSingleReport.setHandler(
        ar -> {
          if (ar.succeeded()) {
            context.assertTrue(ar.succeeded());

            Report origReport =
                JAXB.unmarshal(
                        Resources.getResource("__files/nss-report-2016-03.xml"),
                        CounterReportResponse.class)
                    .getReport()
                    .getReport()
                    .get(0);
            Report respReport = Counter4Utils.fromJSON(ar.result());
            assertThat(origReport).isEqualToComparingFieldByFieldRecursively(respReport);

            async.complete();
          } else {
            context.fail(ar.cause());
          }
        });
  }

  @Test
  public void fetchSingleReportWithAggregatorInvalidReport(TestContext context) {
    final ServiceEndpoint sep = ServiceEndpoint.create(provider, aggregator);
    final String url = ((NSS) sep).buildURL(reportType, beginDate, endDate);

    LOG.info("Creating stub for: " + url);
    stubFor(
        get(urlEqualTo(url.replaceAll(wireMockRule.url(""), "/")))
            .willReturn(aResponse().withBodyFile("nss-report-2018-03-fail.xml")));

    Async async = context.async();
    Future<String> fetchSingleReport = sep.fetchSingleReport(reportType, beginDate, endDate);
    fetchSingleReport.setHandler(
        ar -> {
          if (ar.failed()) {
            LOG.info(ar.cause().getMessage());
            assertThat(ar.cause().getMessage()).contains("1030", "RequestorID", "Insufficient");
            assertThat(ar.cause().getMessage()).doesNotContain("HelpUrl");
            async.complete();
          } else {
            context.fail();
          }
        });
  }

  @Test
  public void fetchSingleReportWithAggregatorInvalidResponse(TestContext context) {
    final ServiceEndpoint sep = ServiceEndpoint.create(provider, aggregator);
    final String url = ((NSS) sep).buildURL(reportType, beginDate, endDate);

    LOG.info("Creating stub for: " + url);
    stubFor(
        get(urlEqualTo(url.replaceAll(wireMockRule.url(""), "/")))
            .willReturn(aResponse().withStatus(404)));

    Async async = context.async();
    Future<String> fetchSingleReport = sep.fetchSingleReport(reportType, beginDate, endDate);
    fetchSingleReport.setHandler(
        ar -> {
          if (ar.succeeded()) {
            context.fail();
          } else {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause().getMessage().contains("404"));
            async.complete();
          }
        });
  }

  @Test
  public void fetchSingleReportWithAggregatorNoService(TestContext context) {
    final ServiceEndpoint sep = ServiceEndpoint.create(provider, aggregator);

    wireMockRule.stop();

    Async async = context.async();
    Future<String> fetchSingleReport = sep.fetchSingleReport(reportType, beginDate, endDate);
    fetchSingleReport.setHandler(
        ar -> {
          if (ar.succeeded()) {
            context.fail();
          } else {
            context.assertTrue(ar.failed());
            async.complete();
          }
        });
  }

  @Test
  public void testIsValidReport() {
    CounterReportResponse reportValid =
        JAXB.unmarshal(
            Resources.getResource("__files/nss-report-2016-03.xml"), CounterReportResponse.class);
    CounterReportResponse reportInvalid =
        JAXB.unmarshal(
            Resources.getResource("__files/nss-report-2018-03-fail.xml"),
            CounterReportResponse.class);
    assertThat(Counter4Utils.getExceptions(reportValid)).isEmpty();
    assertThat(Counter4Utils.getExceptions(reportInvalid)).isNotEmpty();
  }
}
