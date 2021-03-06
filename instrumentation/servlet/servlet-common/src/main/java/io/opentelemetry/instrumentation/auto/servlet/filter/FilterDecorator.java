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

package io.opentelemetry.instrumentation.auto.servlet.filter;

import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.instrumentation.api.decorator.BaseDecorator;
import io.opentelemetry.trace.Tracer;

public class FilterDecorator extends BaseDecorator {
  public static final FilterDecorator DECORATE = new FilterDecorator();
  public static final Tracer TRACER = OpenTelemetry.getTracer("io.opentelemetry.auto.servlet");
}
