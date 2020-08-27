/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.instrumentation.auto.awssdk.v1_11;

import static io.opentelemetry.context.ContextUtils.withScopedContext;
import static io.opentelemetry.trace.TracingContextUtils.withSpan;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.xray.handlers.config.AWSOperationHandler;
import com.amazonaws.xray.handlers.config.AWSOperationHandlerManifest;
import com.amazonaws.xray.handlers.config.AWSServiceHandlerManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import io.grpc.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator.Setter;
import io.opentelemetry.instrumentation.api.tracer.HttpClientTracer;
import io.opentelemetry.trace.Span;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsSdkClientTracer extends HttpClientTracer<Request<?>, Request<?>, Response<?>> {

  private static final Logger log = LoggerFactory.getLogger(AwsSdkClientTracer.class);

  static final String COMPONENT_NAME = "java-aws-sdk";

  public static final AwsSdkClientTracer TRACER = new AwsSdkClientTracer();

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

  private static final URL DEFAULT_OPERATION_PARAMETER_WHITELIST =
      AwsSdkClientTracer.class.getResource("/io/opentelemetry/instrumentation/auto/awssdk/v1_11/DefaultOperationParameterWhitelist.json");

  private static final String GETTER_METHOD_NAME_PREFIX = "get";

  private static final String TO_SNAKE_CASE_REGEX = "([a-z])([A-Z]+)";
  private static final String TO_SNAKE_CASE_REPLACE = "$1_$2";

  private final Map<String, String> serviceNames = new ConcurrentHashMap<>();
  private final Map<Class, String> operationNames = new ConcurrentHashMap<>();
  private AWSServiceHandlerManifest awsServiceHandlerManifest;

  public AwsSdkClientTracer() {
    initRequestManifest();
  }

  private void initRequestManifest() {
    try {
      awsServiceHandlerManifest = MAPPER.readValue(
          DEFAULT_OPERATION_PARAMETER_WHITELIST, AWSServiceHandlerManifest.class);
    } catch (IOException e) {
      log.error("Unable to parse default operation parameter whitelist at "
          + DEFAULT_OPERATION_PARAMETER_WHITELIST.getPath()
          + ". This will affect this handler's ability to capture AWS operation parameter information.", e);
    }
  }

  @Override
  public String spanNameForRequest(Request<?> request) {
    if (request == null) {
      return DEFAULT_SPAN_NAME;
    }
    String awsServiceName = request.getServiceName();
    Class<?> awsOperation = request.getOriginalRequest().getClass();
    return remapServiceName(awsServiceName) + "." + remapOperationName(awsOperation);
  }

  public Span startSpan(Request<?> request, RequestMeta requestMeta) {
    Span span = super.startSpan(request);

    String awsServiceName = request.getServiceName();
    AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    Class<?> awsOperation = originalRequest.getClass();

    span.setAttribute("aws.agent", COMPONENT_NAME);
    span.setAttribute("aws.service", awsServiceName);
    span.setAttribute("aws.operation", awsOperation.getSimpleName());
    span.setAttribute("aws.endpoint", request.getEndpoint().toString());

    if (requestMeta != null) {
      span.setAttribute("aws.bucket.name", requestMeta.getBucketName());
      span.setAttribute("aws.queue.url", requestMeta.getQueueUrl());
      span.setAttribute("aws.queue.name", requestMeta.getQueueName());
      span.setAttribute("aws.stream.name", requestMeta.getStreamName());
      span.setAttribute("aws.table.name", requestMeta.getTableName());
    }

    span.setAttributes(extractRequestParameters(request));

    return span;
  }

  /**
   * Override startScope not to inject context into the request since no need to propagate context
   * to AWS backend services.
   */
  @Override
  public Scope startScope(Span span, Request<?> request) {
    Context context = withSpan(span, Context.current());
    context = context.withValue(CONTEXT_CLIENT_SPAN_KEY, span);
    return withScopedContext(context);
  }

  @Override
  public Span onResponse(Span span, Response<?> response) {
    if (response != null && response.getAwsResponse() instanceof AmazonWebServiceResponse) {
      AmazonWebServiceResponse awsResp = (AmazonWebServiceResponse) response.getAwsResponse();
      span.setAttribute("aws.requestId", awsResp.getRequestId());
    }
    return super.onResponse(span, response);
  }

  private String remapServiceName(String serviceName) {
    if (!serviceNames.containsKey(serviceName)) {
      serviceNames.put(serviceName, serviceName.replace("Amazon", "").trim());
    }
    return serviceNames.get(serviceName);
  }

  private String remapOperationName(Class<?> awsOperation) {
    if (!operationNames.containsKey(awsOperation)) {
      operationNames.put(awsOperation, awsOperation.getSimpleName().replace("Request", ""));
    }
    return operationNames.get(awsOperation);
  }

  @Override
  protected String method(Request<?> request) {
    return request.getHttpMethod().name();
  }

  @Override
  protected URI url(Request<?> request) {
    return request.getEndpoint();
  }

  @Override
  protected Integer status(Response<?> response) {
    return response.getHttpResponse().getStatusCode();
  }

  @Override
  protected String requestHeader(Request<?> request, String name) {
    return request.getHeaders().get(name);
  }

  @Override
  protected String responseHeader(Response<?> response, String name) {
    return response.getHttpResponse().getHeaders().get(name);
  }

  @Override
  protected Setter<Request<?>> getSetter() {
    return null;
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.aws-sdk-1.11";
  }


  private static String toSnakeCase(String camelCase) {
    return camelCase.replaceAll(TO_SNAKE_CASE_REGEX, TO_SNAKE_CASE_REPLACE).toLowerCase();
  }

  private Map<String, Object> extractRequestParameters(Request<?> request) {
    HashMap<String, Object> ret = new HashMap<>();
    if (null == awsServiceHandlerManifest) {
      return ret;
    }

    AWSOperationHandlerManifest serviceHandler =
        awsServiceHandlerManifest.getOperationHandlerManifest(extractServiceName(request));
    if (null == serviceHandler) {
      return ret;
    }

    AWSOperationHandler operationHandler = serviceHandler.getOperationHandler(extractOperationName(request));
    if (null == operationHandler) {
      return ret;
    }

    Object originalRequest = request.getOriginalRequest();

    if (null != operationHandler.getRequestParameters()) {
      operationHandler.getRequestParameters().forEach(parameterName -> {
        try {
          Object parameterValue = originalRequest
              .getClass().getMethod(GETTER_METHOD_NAME_PREFIX + parameterName).invoke(originalRequest);
          if (null != parameterValue) {
            ret.put(toSnakeCase(parameterName), parameterValue);
          }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
          log.error("Error getting request parameter: " + parameterName, e);
        }
      });
    }

    if (null != operationHandler.getRequestDescriptors()) {
      operationHandler.getRequestDescriptors().forEach((requestKeyName, requestDescriptor) -> {
        try {
          if (requestDescriptor.isMap() && requestDescriptor.shouldGetKeys()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameterValue =
                (Map<String, Object>) originalRequest
                    .getClass()
                    .getMethod(GETTER_METHOD_NAME_PREFIX + requestKeyName).invoke(originalRequest);
            if (null != parameterValue) {
              String renameTo =
                  null != requestDescriptor.getRenameTo() ? requestDescriptor.getRenameTo() : requestKeyName;
              ret.put(toSnakeCase(renameTo), parameterValue.keySet());
            }
          } else if (requestDescriptor.isList() && requestDescriptor.shouldGetCount()) {
            @SuppressWarnings("unchecked")
            List<Object> parameterValue =
                (List<Object>) originalRequest
                    .getClass().getMethod(GETTER_METHOD_NAME_PREFIX + requestKeyName).invoke(originalRequest);
            if (null != parameterValue) {
              String renameTo =
                  null != requestDescriptor.getRenameTo() ? requestDescriptor.getRenameTo() : requestKeyName;
              ret.put(toSnakeCase(renameTo), parameterValue.size());
            }
          }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException e) {
          log.error("Error getting request parameter: " + requestKeyName, e);
        }
      });
    }

    return ret;
  }
}
