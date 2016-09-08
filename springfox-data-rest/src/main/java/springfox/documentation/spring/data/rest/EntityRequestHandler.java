/*
 *
 *  Copyright 2016 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package springfox.documentation.spring.data.rest;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.TypeResolver;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import org.springframework.data.rest.core.mapping.ResourceType;
import org.springframework.data.rest.webmvc.PersistentEntityResource;
import org.springframework.data.rest.webmvc.support.BackendId;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.Resources;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.NameValueExpression;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import springfox.documentation.RequestHandlerKey;
import springfox.documentation.service.ResolvedMethodParameter;
import springfox.documentation.spring.web.readers.operation.HandlerMethodResolver;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.common.collect.Lists.*;
import static com.google.common.collect.Sets.*;
import static org.springframework.util.StringUtils.*;

public class EntityRequestHandler implements springfox.documentation.RequestHandler {
  private final ResourceMetadata resource;
  private final ResourceType resourceType;
  private final Class<? extends Serializable> idType;
  private final Class<?> domainType;
  private final RequestMappingInfo requestMapping;
  private final HandlerMethod handlerMethod;
  private final TypeResolver resolver;
  private static final ArrayList<MediaType> COLLECTION_COMPACT_MEDIA_TYPES = newArrayList(
      MediaType.valueOf("application/x-spring-data-compact+json"),
      MediaType.valueOf("text/uri-list"));

  public EntityRequestHandler(
      TypeResolver resolver, ResourceMetadata resource,
      Class<? extends Serializable> idType,
      Class<?> domainType,
      RequestMappingInfo requestMapping,
      HandlerMethod handlerMethod) {
    this.resolver = resolver;

    this.resource = resource;
    this.idType = idType;
    this.domainType = domainType;
    this.requestMapping = requestMapping;
    this.handlerMethod = handlerMethod;
    this.resourceType = resourceType();
  }

  @Override
  public Class<?> declaringClass() {
    return handlerMethod.getBeanType();
  }

  @Override
  public boolean isAnnotatedWith(Class<? extends Annotation> annotation) {
    return null != AnnotationUtils.findAnnotation(handlerMethod.getMethod(), annotation);
  }

  @Override
  public PatternsRequestCondition getPatternsCondition() {
    PatternsRequestCondition repositoryPatterns = requestMapping.getPatternsCondition();
    Set<String> patterns = newHashSet();
    for (String each : repositoryPatterns.getPatterns()) {
      patterns.add(each.replace("{repository}", resource.getPath().toString()));
    }
    return new PatternsRequestCondition(patterns.toArray(new String[patterns.size()]));
  }

  @Override
  public String groupName() {
    return capitalize(resource.getRel());
  }

  @Override
  public String getName() {
    return handlerMethod.getMethod().getName();
  }

  @Override
  public Set<RequestMethod> supportedMethods() {
    return FluentIterable.from(resource
        .getSupportedHttpMethods()
        .getMethodsFor(resourceType))
        .transform(toRequestMethod())
        .toSet();
  }

  private Function<HttpMethod, RequestMethod> toRequestMethod() {
    return new Function<HttpMethod, RequestMethod>() {
      @Override
      public RequestMethod apply(HttpMethod input) {
        return RequestMethod.valueOf(input.name());
      }
    };
  }

  @Override
  public Set<? extends MediaType> produces() {
    return requestMapping.getProducesCondition().getProducibleMediaTypes();
  }

  @Override
  public Set<? extends MediaType> consumes() {
    return requestMapping.getConsumesCondition().getConsumableMediaTypes();
  }

  @Override
  public Set<NameValueExpression<String>> headers() {
    return requestMapping.getHeadersCondition().getExpressions();
  }

  @Override
  public Set<NameValueExpression<String>> params() {
    return requestMapping.getParamsCondition().getExpressions();
  }

  @Override
  public <T extends Annotation> Optional<T> findAnnotation(Class<T> annotation) {
    return Optional.fromNullable(AnnotationUtils.findAnnotation(handlerMethod.getMethod(), annotation));
  }

  @Override
  public RequestHandlerKey key() {
    return new RequestHandlerKey(
        requestMapping.getPatternsCondition().getPatterns(),
        requestMapping.getMethodsCondition().getMethods(),
        requestMapping.getConsumesCondition().getConsumableMediaTypes(),
        requestMapping.getProducesCondition().getProducibleMediaTypes());
  }

  @Override
  public List<ResolvedMethodParameter> getParameters() {
    HandlerMethodResolver handlerMethodResolver = new HandlerMethodResolver(resolver);
    return FluentIterable.from(handlerMethodResolver.methodParameters(handlerMethod))
        .transform(toEntitySpecificParameters())
        .toList();
  }

  private Function<ResolvedMethodParameter, ResolvedMethodParameter> toEntitySpecificParameters() {
    return new Function<ResolvedMethodParameter, ResolvedMethodParameter>() {
      @Override
      public ResolvedMethodParameter apply(ResolvedMethodParameter input) {
        if (isIdParameter(input)) {
          return transformToId(input);
        } else if (isDomainParameter(input)) {
          return transformToDomainType(input);
        }
        return input;
      }
    };
  }

  private ResolvedMethodParameter transformToDomainType(ResolvedMethodParameter input) {
    return new ResolvedMethodParameter(input.getMethodParameter(), resolver.resolve(domainType));
  }

  private boolean isDomainParameter(ResolvedMethodParameter input) {
    return PersistentEntityResource.class.equals(input.getResolvedParameterType().getErasedType());
  }

  private ResolvedMethodParameter transformToId(ResolvedMethodParameter idParam) {
    return new ResolvedMethodParameter(idParam.getMethodParameter(), resolver.resolve(idType));
  }

  private boolean isIdParameter(ResolvedMethodParameter input) {
    return input.getMethodParameter().hasParameterAnnotation(BackendId.class);
  }

  @Override
  public ResolvedType getReturnType() {
    if (resourceType == ResourceType.COLLECTION) {
      if (COLLECTION_COMPACT_MEDIA_TYPES.containsAll(
          requestMapping.getProducesCondition().getProducibleMediaTypes())) {
        return resolver.resolve(Resources.class, Link.class);
      } else if (requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.HEAD)
              || requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.OPTIONS)) {
        return resolver.resolve(Void.TYPE);
      } else if (requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)) {
        return resolver.resolve(Resource.class, domainType);
      }
      return resolver.resolve(Resources.class, domainType);
    } else {
      if (requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.GET)
          || requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.PUT)
          || requestMapping.getMethodsCondition().getMethods().contains(RequestMethod.PATCH)) {
        return resolver.resolve(Resource.class, domainType);
      }
    }
    return resolver.resolve(Void.TYPE);
  }

  private ResourceType resourceType() {
    if (requestMapping.getPatternsCondition().getPatterns().contains("/{repository}")) {
      return ResourceType.COLLECTION;
    } else {
      return ResourceType.ITEM;
    }
  }

  @Override
  public <T extends Annotation> Optional<T> findControllerAnnotation(Class<T> annotation) {
    return Optional.fromNullable(AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), annotation));
  }

  @Override
  public RequestMappingInfo getRequestMapping() {
    return requestMapping;
  }

  @Override
  public HandlerMethod getHandlerMethod() {
    return handlerMethod;
  }
}