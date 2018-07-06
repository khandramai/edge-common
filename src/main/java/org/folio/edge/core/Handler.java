package org.folio.edge.core;

import static org.folio.edge.core.Constants.PARAM_API_KEY;
import static org.folio.edge.core.Constants.TEXT_PLAIN;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.folio.edge.core.model.ClientInfo;
import org.folio.edge.core.security.SecureStore;
import org.folio.edge.core.utils.ApiKeyUtils;
import org.folio.edge.core.utils.ApiKeyUtils.MalformedApiKeyException;
import org.folio.edge.core.utils.OkapiClient;
import org.folio.edge.core.utils.OkapiClientFactory;

import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

public class Handler {

  private static final Logger logger = Logger.getLogger(Handler.class);

  protected InstitutionalUserHelper iuHelper;
  protected OkapiClientFactory ocf;

  public Handler(SecureStore secureStore, OkapiClientFactory ocf) {
    this.ocf = ocf;
    this.iuHelper = new InstitutionalUserHelper(secureStore);
  }

  protected void handleCommon(RoutingContext ctx, String[] requiredParams, String[] optionalParams,
      TwoParamVoidFunction<OkapiClient, Map<String, String>> action) {
    String key = ctx.request().getParam(PARAM_API_KEY);
    if (key == null || key.isEmpty()) {
      accessDenied(ctx, "Missing required parameter: " + PARAM_API_KEY);
      return;
    }

    Map<String, String> params = new HashMap<>(requiredParams.length);
    for (String param : requiredParams) {
      String value = ctx.request().getParam(param);
      if (value == null || value.isEmpty()) {
        badRequest(ctx, "Missing required parameter: " + param);
        return;
      } else {
        params.put(param, value);
      }
    }

    for (String param : optionalParams) {
      params.put(param, ctx.request().getParam(param));
    }

    ClientInfo clientInfo;
    try {
      clientInfo = ApiKeyUtils.parseApiKey(key);
    } catch (MalformedApiKeyException e) {
      accessDenied(ctx, e.getMessage());
      return;
    }

    final OkapiClient client = ocf.getOkapiClient(clientInfo.tenantId);

    iuHelper.getToken(client,
        clientInfo.clientId,
        clientInfo.tenantId,
        clientInfo.username)
      .thenAcceptAsync(token -> {
        client.setToken(token);
        action.apply(client, params);
      })
      .exceptionally(t -> {
        if (t instanceof TimeoutException) {
          requestTimeout(ctx, t.getMessage());
        } else {
          accessDenied(ctx, t.getMessage());
        }
        return null;
      });
  }

  protected void handleProxyResponse(RoutingContext ctx, HttpClientResponse resp) {
    final StringBuilder body = new StringBuilder();
    resp.handler(buf -> {

      if (logger.isTraceEnabled()) {
        logger.trace("read bytes: " + buf.toString());
      }

      body.append(buf);
    }).endHandler(v -> {
      ctx.response().setStatusCode(resp.statusCode());

      String respBody = body.toString();

      if (logger.isDebugEnabled()) {
        logger.debug("response: " + respBody);
      }

      String contentType = resp.getHeader(HttpHeaders.CONTENT_TYPE);
      if (contentType != null) {
        ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, contentType);
      }

      ctx.response().end(respBody);
    });
  }

  protected void handleProxyException(RoutingContext ctx, Throwable t) {
    logger.error("Exception calling OKAPI", t);
    if (t instanceof TimeoutException) {
      requestTimeout(ctx, t.getMessage());
    } else {
      internalServerError(ctx, t.getMessage());
    }
  }

  protected void accessDenied(RoutingContext ctx, String msg) {
    ctx.response()
      .setStatusCode(403)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(msg);
  }

  protected void badRequest(RoutingContext ctx, String msg) {
    ctx.response()
      .setStatusCode(400)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(msg);
  }

  protected void notFound(RoutingContext ctx, String msg) {
    ctx.response()
      .setStatusCode(404)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(msg);
  }

  protected void requestTimeout(RoutingContext ctx, String msg) {
    ctx.response()
      .setStatusCode(408)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(msg);
  }

  protected void internalServerError(RoutingContext ctx, String msg) {
    if (!ctx.response().ended()) {
      ctx.response()
        .setStatusCode(500)
        .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
        .end(msg);
    }
  }

  @FunctionalInterface
  public interface TwoParamVoidFunction<A, B> {
    public void apply(A a, B b);
  }
}
