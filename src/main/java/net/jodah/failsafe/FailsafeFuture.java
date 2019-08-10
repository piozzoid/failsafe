/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package net.jodah.failsafe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A CompletableFuture implementation that propogates cancellations and calls completion handlers.
 *
 * @param <T> result type
 * @author Jonathan Halterman
 */
class FailsafeFuture<T> extends CompletableFuture<T> {
  private final FailsafeExecutor<T> executor;
  private ExecutionContext execution;

  // Mutable state, guarded by "this"
  private Future<T> delegate;
  private List<Future<T>> timeoutDelegates;

  FailsafeFuture(FailsafeExecutor<T> executor) {
    this.executor = executor;
  }

  /**
   * If not already completed, completes  the future with the {@code value}, calling the complete and success handlers.
   */
  @Override
  public synchronized boolean complete(T value) {
    return completeResult(ExecutionResult.success(value));
  }

  /**
   * If not already completed, completes the future with the {@code failure}, calling the complete and failure
   * handlers.
   */
  @Override
  public synchronized boolean completeExceptionally(Throwable failure) {
    return completeResult(ExecutionResult.failure(failure));
  }

  /**
   * Cancels this and the internal delegate.
   */
  @Override
  public synchronized boolean cancel(boolean mayInterruptIfRunning) {
    if (isDone())
      return false;

    boolean cancelResult = super.cancel(mayInterruptIfRunning);
    cancelResult = cancelDelegates(mayInterruptIfRunning, cancelResult);
    ExecutionResult result = ExecutionResult.failure(new CancellationException());
    super.completeExceptionally(result.getFailure());
    executor.handleComplete(result, execution);
    return cancelResult;
  }

  /**
   * Completes the execution with the {@code result} and calls completion listeners.
   */
  @SuppressWarnings("unchecked")
  synchronized boolean completeResult(ExecutionResult result) {
    if (isDone())
      return false;

    Throwable failure = result.getFailure();
    boolean completed;
    if (failure == null)
      completed = super.complete((T) result.getResult());
    else
      completed = super.completeExceptionally(failure);
    if (completed)
      executor.handleComplete(result, execution);
    return completed;
  }

  synchronized Future<T> getDelegate() {
    return delegate;
  }

  synchronized boolean cancelDelegates(boolean interruptDelegate, boolean result) {
    if (delegate != null)
      result = delegate.cancel(interruptDelegate);
    if (timeoutDelegates != null)
      for (Future<T> timeoutDelegate : timeoutDelegates)
        timeoutDelegate.cancel(false);
    return result;
  }

  synchronized List<Future<T>> getTimeoutDelegates() {
    return timeoutDelegates;
  }

  synchronized void inject(Future<T> delegate) {
    this.delegate = delegate;
    timeoutDelegates = null;
  }

  synchronized void injectTimeout(Future<T> timeoutDelegate) {
    if (timeoutDelegates == null)
      timeoutDelegates = new ArrayList<>(3);
    timeoutDelegates.add(timeoutDelegate);
  }

  void inject(ExecutionContext execution) {
    this.execution = execution;
  }
}
