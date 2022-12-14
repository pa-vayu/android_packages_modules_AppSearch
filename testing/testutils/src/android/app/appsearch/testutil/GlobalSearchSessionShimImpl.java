/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.appsearch.testutil;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.Features;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.ReportSystemUsageRequest;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.AlwaysSupportedFeatures;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This test class adapts the AppSearch Framework API to ListenableFuture, so it can be tested via a
 * consistent interface.
 *
 * @hide
 */
public class GlobalSearchSessionShimImpl implements GlobalSearchSessionShim {
    private final GlobalSearchSession mGlobalSearchSession;
    private final ExecutorService mExecutor;

    @NonNull
    public static ListenableFuture<GlobalSearchSessionShim> createGlobalSearchSessionAsync() {
        return createGlobalSearchSessionAsync(ApplicationProvider.getApplicationContext());
    }

    /** Only for use when called from a non-instrumented context. */
    @NonNull
    public static ListenableFuture<GlobalSearchSessionShim> createGlobalSearchSessionAsync(
            @NonNull Context context) {
        AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
        SettableFuture<AppSearchResult<GlobalSearchSession>> future = SettableFuture.create();
        ExecutorService executor = Executors.newCachedThreadPool();
        appSearchManager.createGlobalSearchSession(executor, future::set);
        return Futures.transform(
                future,
                instance -> new GlobalSearchSessionShimImpl(instance.getResultValue(), executor),
                executor);
    }

    private GlobalSearchSessionShimImpl(
            @NonNull GlobalSearchSession session, @NonNull ExecutorService executor) {
        mGlobalSearchSession = Objects.requireNonNull(session);
        mExecutor = Objects.requireNonNull(executor);
    }

    @NonNull
    @Override
    public ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByDocumentIdAsync(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull GetByDocumentIdRequest request) {
        SettableFuture<AppSearchBatchResult<String, GenericDocument>> future =
                SettableFuture.create();
        mGlobalSearchSession.getByDocumentId(
                packageName, databaseName, request, mExecutor,
                new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @NonNull
    @Override
    public SearchResultsShim search(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SearchResults searchResults = mGlobalSearchSession.search(queryExpression, searchSpec);
        return new SearchResultsShimImpl(searchResults, mExecutor);
    }

    @NonNull
    @Override
    public ListenableFuture<Void> reportSystemUsageAsync(
            @NonNull ReportSystemUsageRequest request) {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        mGlobalSearchSession.reportSystemUsage(request, mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @NonNull
    @Override
    public ListenableFuture<GetSchemaResponse> getSchemaAsync(
        @NonNull String packageName, @NonNull String databaseName) {
      SettableFuture<AppSearchResult<GetSchemaResponse>> future = SettableFuture.create();
      mGlobalSearchSession.getSchema(packageName, databaseName, mExecutor, future::set);
      return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @Override
    public void registerObserverCallback(
            @NonNull String targetPackageName,
            @NonNull ObserverSpec spec,
            @NonNull Executor executor,
            @NonNull ObserverCallback observer) throws AppSearchException {
        mGlobalSearchSession.registerObserverCallback(targetPackageName, spec, mExecutor, observer);
    }

    @Override
    public void unregisterObserverCallback(
            @NonNull String targetPackageName, @NonNull ObserverCallback observer)
            throws AppSearchException {
        mGlobalSearchSession.unregisterObserverCallback(targetPackageName, observer);
    }

    @NonNull
    @Override
    public Features getFeatures() {
        return new AlwaysSupportedFeatures();
    }

    @Override
    public void close() {
        mGlobalSearchSession.close();
    }

    private <T> ListenableFuture<T> transformResult(
            @NonNull AppSearchResult<T> result) throws AppSearchException {
        if (!result.isSuccess()) {
            throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
        }
        return Futures.immediateFuture(result.getResultValue());
    }
}

