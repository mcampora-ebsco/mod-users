package org.folio.moduserstest;

import static io.vertx.core.json.Json.encode;
import static org.folio.moduserstest.RestITSupport.assertStatus;
import static org.folio.moduserstest.RestITSupport.getJson;
import static org.folio.moduserstest.RestITSupport.post;
import static org.folio.moduserstest.RestITSupport.put;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.rest.utils.TenantInit;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

@RunWith(VertxUnitRunner.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RestVerticleIT {

  private static final Logger log = LogManager.getLogger(RestVerticleIT.class);

  @Rule
  public Timeout rule = Timeout.seconds(20);

  @BeforeClass
  public static void setup(TestContext context) {
    Vertx vertx = Vertx.vertx();

    PostgresClient.setPostgresTester(new PostgresTesterContainer());

    final var port = NetworkUtils.nextFreePort();
    RestITSupport.setUp(port);
    TenantClient tenantClient = new TenantClient("http://localhost:" + port,
      "diku", "diku", WebClient.create(vertx));

    DeploymentOptions options = new DeploymentOptions()
        .setConfig(new JsonObject().put("http.port", port));

    //module version number doesn't matter to RAML Module Builder,
    //this is used as a marker for a new activation rather than an upgrade
    vertx.deployVerticle(RestVerticle.class.getName(), options, context.asyncAssertSuccess(res -> {
      TenantAttributes ta = new TenantAttributes();
      ta.setModuleTo("mod-users-1.0.0");
      List<Parameter> parameters = new LinkedList<>();
      parameters.add(new Parameter().withKey("loadReference").withValue("true"));
      parameters.add(new Parameter().withKey("loadSample").withValue("false"));
      ta.setParameters(parameters);
      TenantInit.init(tenantClient, ta).onComplete(context.asyncAssertSuccess());
    }));
  }

  private Future<Void> createProxyfor(TestContext context) {
    log.info("Creating a new proxyfor entry\n");

    JsonObject proxyObject = new JsonObject()
      .put("userId", "2498aeb2-23ca-436a-87ea-a4e1bfaa5bb5")
      .put("proxyUserId", "2062d0ef-3f3e-40c5-a870-5912554bc0fa");

    Future<HttpResponse<Buffer>> future = post("/proxiesfor", proxyObject.encode());

    return future.map(response -> {
      assertStatus(context, response, 201);
      return null;
    });
  }

  private Future<Void> findAndUpdateProxyfor(TestContext context) {
    log.info("Find and update a particular proxyfor entry\n");

    log.info("Making CQL request\n");
    Future<String> proxyId = getProxyId(context
    );

    JsonObject modifiedProxyObject = new JsonObject()
      .put("userId", "2498aeb2-23ca-436a-87ea-a4e1bfaa5bb5")
      .put("proxyUserId", "2062d0ef-3f3e-40c5-a870-5912554bc0fa");

    log.info("Making put-by-id request\n");
    return proxyId.compose(id -> put("/proxiesfor/" + id, encode(modifiedProxyObject)))
      .map(response -> {
        assertStatus(context, response, 204);
        return null;
      });
  }

  private Future<String> getProxyId(TestContext context) {
    Future<JsonObject> resultJson = getJson(context,
      "/proxiesfor?query=userId=2498aeb2-23ca-436a-87ea-a4e1bfaa5bb5+AND+proxyUserId=2062d0ef-3f3e-40c5-a870-5912554bc0fa");

    return resultJson.map(result -> {
      JsonArray proxyForArray = result.getJsonArray("proxiesFor");
      if (proxyForArray.size() != 1) {
        fail("Expected 1 entry, found " + proxyForArray.size());
      }

      JsonObject proxyForObject = proxyForArray.getJsonObject(0);
      return proxyForObject.getString("id");
    });
  }

  @Test
  public void test1Sequential(TestContext context) {
    Async async = context.async();

    Future<Void> startFuture = createProxyfor(context)
      .compose(v -> findAndUpdateProxyfor(context));

    startFuture.onComplete(res -> {
      if (res.succeeded()) {
        async.complete();
      } else {
        res.cause().printStackTrace();
        context.fail(res.cause());
      }
    });
  }

  @Test
  public void testExpiryOK(TestContext context) {
    Map<String,String> headers = new HashMap<>();
    headers.put("Accept", "*/*");
    Future<HttpResponse<Buffer>> future = post("/users/expire/timer", "", headers);
    future.onComplete(context.asyncAssertSuccess(res ->
      context.assertEquals(204, res.statusCode())));
  }

  @Test
  public void testExpiryBadTenant(TestContext context) {
    Map<String,String> headers = new HashMap<>();
    headers.put("Accept", "*/*");
    headers.put("X-Okapi-Tenant", "badTenant");
    Future<HttpResponse<Buffer>> future = post("/users/expire/timer", "", headers);
    future.onComplete(context.asyncAssertSuccess(res ->
      context.assertEquals(500, res.statusCode())));
  }
}
