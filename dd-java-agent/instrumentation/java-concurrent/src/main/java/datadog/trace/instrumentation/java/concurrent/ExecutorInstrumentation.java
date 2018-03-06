package datadog.trace.instrumentation.java.concurrent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.ContextPropagator;
import io.opentracing.Scope;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ExecutorInstrumentation extends Instrumenter.Configurable {
  public static final String EXEC_NAME = "java_concurrent";
  public static final HelperInjector EXEC_HELPER_INJECTOR =
      new HelperInjector(
          ExecutorInstrumentation.class.getName() + "$ConcurrentUtils",
          ExecutorInstrumentation.class.getName() + "$DatadogWrapper",
          ExecutorInstrumentation.class.getName() + "$CallableWrapper",
          ExecutorInstrumentation.class.getName() + "$RunnableWrapper");

  public ExecutorInstrumentation() {
    super(EXEC_NAME);
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(not(isInterface()).and(hasSuperType(named(Executor.class.getName()))))
        .transform(EXEC_HELPER_INJECTOR)
        .transform(
            DDAdvice.create()
                .advice(
                    named("execute").and(takesArgument(0, Runnable.class)),
                    WrapRunnableAdvice.class.getName()))
        .asDecorator()
        .type(not(isInterface()).and(hasSuperType(named(ExecutorService.class.getName()))))
        .transform(EXEC_HELPER_INJECTOR)
        .transform(
            DDAdvice.create()
                .advice(
                    named("submit").and(takesArgument(0, Runnable.class)),
                    WrapRunnableAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    named("submit").and(takesArgument(0, Callable.class)),
                    WrapCallableAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    nameMatches("invoke(Any|All)$").and(takesArgument(0, Callable.class)),
                    WrapCallableCollectionAdvice.class.getName()))
        .asDecorator();
  }

  public static class WrapRunnableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static DatadogWrapper wrapJob(
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator && task != null && !(task instanceof DatadogWrapper)) {
        task = new RunnableWrapper(task, (ContextPropagator) scope);
        return (DatadogWrapper) task;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void checkCancel(
        @Advice.Enter final DatadogWrapper wrapper, @Advice.Thrown final Throwable throwable) {
      if (null != wrapper && null != throwable) {
        wrapper.cancel();
      }
    }
  }

  public static class WrapCallableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static DatadogWrapper wrapJob(
        @Advice.Argument(value = 0, readOnly = false) Callable task) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator && task != null && !(task instanceof DatadogWrapper)) {
        task = new CallableWrapper(task, (ContextPropagator) scope);
        return (DatadogWrapper) task;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void checkCancel(
        @Advice.Enter final DatadogWrapper wrapper, @Advice.Thrown final Throwable throwable) {
      if (null != wrapper && null != throwable) {
        wrapper.cancel();
      }
    }
  }

  public static class WrapCallableCollectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Collection<?> wrapJob(
        @Advice.Argument(value = 0, readOnly = false) Collection<? extends Callable<?>> tasks) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator) {
        Collection<Callable<?>> wrappedTasks = new ArrayList<>(tasks.size());
        for (Callable task : tasks) {
          if (task != null) {
            if (!(task instanceof CallableWrapper)) {
              task = new CallableWrapper(task, (ContextPropagator) scope);
            }
            wrappedTasks.add(task);
          }
        }
        tasks = wrappedTasks;
        return tasks;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void checkCancel(
        @Advice.Enter final Collection<?> wrappedJobs, @Advice.Thrown final Throwable throwable) {
      if (null != wrappedJobs && null != throwable) {
        for (Object wrapper : wrappedJobs) {
          if (wrapper instanceof DatadogWrapper) {
            ((DatadogWrapper) wrapper).cancel();
          }
        }
      }
    }
  }

  /** Marker interface for tasks which are wrapped to propagate the trace context. */
  @Slf4j
  public abstract static class DatadogWrapper {
    protected final ContextPropagator.Continuation continuation;

    public DatadogWrapper(ContextPropagator scope) {
      continuation = scope.capture(true);
      log.debug("created continuation {} from scope {}", continuation, scope);
    }

    public void cancel() {
      if (null != continuation) {
        continuation.activate().close();
        log.debug("canceled continuation {}", continuation);
      }
    }
  }

  @Slf4j
  public static class RunnableWrapper extends DatadogWrapper implements Runnable {
    private final Runnable delegatee;

    public RunnableWrapper(Runnable toWrap, ContextPropagator scope) {
      super(scope);
      delegatee = toWrap;
    }

    @Override
    public void run() {
      final Scope scope = continuation.activate();
      try {
        delegatee.run();
      } finally {
        scope.close();
      }
    }
  }

  @Slf4j
  public static class CallableWrapper<T> extends DatadogWrapper implements Callable<T> {
    private final Callable<T> delegatee;

    public CallableWrapper(Callable<T> toWrap, ContextPropagator scope) {
      super(scope);
      delegatee = toWrap;
    }

    @Override
    public T call() throws Exception {
      final Scope scope = continuation.activate();
      try {
        return delegatee.call();
      } finally {
        scope.close();
      }
    }
  }

  public static class ConcurrentUtils {
    private static Map<Class<?>, Field> fieldCache = new ConcurrentHashMap<>();
    private static String[] wrapperFields = {"runnable", "callable"};

    public static boolean safeToWrap(Future<?> f) {
      return null != getDatadogWrapper(f);
    }

    public static DatadogWrapper getDatadogWrapper(Future<?> f) {
      final Field field;
      if (fieldCache.containsKey(f.getClass())) {
        field = fieldCache.get(f.getClass());
      } else {
        field = getWrapperField(f.getClass());
        fieldCache.put(f.getClass(), field);
      }

      if (field != null) {
        try {
          field.setAccessible(true);
          Object o = field.get(f);
          if (o instanceof DatadogWrapper) {
            return (DatadogWrapper) o;
          }
        } catch (IllegalArgumentException | IllegalAccessException e) {
        } finally {
          field.setAccessible(false);
        }
      }
      return null;
    }

    private static Field getWrapperField(Class<?> clazz) {
      Field field = null;
      while (field == null && clazz != null) {
        for (int i = 0; i < wrapperFields.length; ++i) {
          try {
            field = clazz.getDeclaredField(wrapperFields[i]);
            break;
          } catch (Exception e) {
          }
        }
        clazz = clazz.getSuperclass();
      }
      return field;
    }
  }
}
