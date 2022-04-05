/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appsearch.contactsindexer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.AndroidRuntimeException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Helper class to manage the Person corpus in AppSearch.
 *
 * <p>It wraps AppSearch API calls using {@link CompletableFuture}, which is easier to use.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class AppSearchHelper {
    static final String TAG = "ContactsIndexerAppSearchHelper";

    public static final String DATABASE_NAME = "contacts";
    // Namespace needed to be used for ContactsIndexer to index the contacts
    public static final String NAMESPACE_NAME = "";

    private static final int GET_CONTACT_IDS_PAGE_SIZE = 500;

    private final Context mContext;
    private final Executor mExecutor;
    // Holds the result of an asynchronous operation to create an AppSearchSession
    // and set the builtin:Person schema in it.
    private volatile CompletableFuture<AppSearchSession> mAppSearchSessionFuture;

    /**
     * Creates an initialized {@link AppSearchHelper}.
     *
     * @param executor Executor used to handle result callbacks from AppSearch.
     */
    @NonNull
    public static AppSearchHelper createAppSearchHelper(@NonNull Context context,
            @NonNull Executor executor) {
        AppSearchHelper appSearchHelper = new AppSearchHelper(Objects.requireNonNull(context),
                Objects.requireNonNull(executor));
        appSearchHelper.initializeAsync();
        return appSearchHelper;
    }

    @VisibleForTesting
    AppSearchHelper(@NonNull Context context, @NonNull Executor executor) {
        mContext = Objects.requireNonNull(context);
        mExecutor = Objects.requireNonNull(executor);
    }

    /** Initializes {@link AppSearchHelper} asynchronously.
     *
     * <p>Chains {@link CompletableFuture}s to create an {@link AppSearchSession} and
     * set builtin:Person schema.
     */
    private void initializeAsync() {
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            throw new AndroidRuntimeException(
                    "Can't get AppSearchManager to initialize AppSearchHelper.");
        }

        CompletableFuture<AppSearchSession> createSessionFuture =
                createAppSearchSessionAsync(appSearchManager);
        mAppSearchSessionFuture = createSessionFuture.thenCompose(appSearchSession -> {
            // Always force set the schema. We are at the 1st version, so it should be fine for
            // doing it.
            // For future schema changes, we could also force set it, and rely on a full update
            // to bring back wiped data.
            return setPersonSchemaAsync(appSearchSession, /*forceOverride=*/ true);
        });
    }

    /**
     * Creates the {@link AppSearchSession}.
     *
     * <p>It returns {@link CompletableFuture} so caller can wait for a valid AppSearchSession
     * created, which must be done before ContactsIndexer starts handling CP2 changes.
     */
    private CompletableFuture<AppSearchSession> createAppSearchSessionAsync(
            @NonNull AppSearchManager appSearchManager) {
        Objects.requireNonNull(appSearchManager);

        CompletableFuture<AppSearchSession> future = new CompletableFuture<>();
        final AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(DATABASE_NAME).build();
        appSearchManager.createSearchSession(searchContext, mExecutor, result -> {
            if (result.isSuccess()) {
                future.complete(result.getResultValue());
            } else {
                Log.e(TAG, "Failed to create an AppSearchSession - code: " + result.getResultCode()
                        + " errorMessage: " + result.getErrorMessage());
                future.completeExceptionally(
                        new AppSearchException(result.getResultCode(), result.getErrorMessage()));
            }
        });

        return future;
    }

    /**
     * Sets the Person schemas for the {@link AppSearchSession}.
     *
     * <p>It returns {@link CompletableFuture} so caller can wait for valid schemas set, which must
     * be done before ContactsIndexer starts handling CP2 changes.
     *
     * @param session       {@link AppSearchSession} created before.
     * @param forceOverride whether the incompatible schemas should be overridden.
     */
    @NonNull
    private CompletableFuture<AppSearchSession> setPersonSchemaAsync(
            @NonNull AppSearchSession session, boolean forceOverride) {
        Objects.requireNonNull(session);

        CompletableFuture<AppSearchSession> future = new CompletableFuture<>();
        SetSchemaRequest.Builder schemaBuilder = new SetSchemaRequest.Builder()
                .addSchemas(ContactPoint.SCHEMA, Person.SCHEMA)
                .addRequiredPermissionsForSchemaTypeVisibility(Person.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_CONTACTS))
                .setForceOverride(forceOverride);
        session.setSchema(schemaBuilder.build(), mExecutor, mExecutor,
                result -> {
                    if (result.isSuccess()) {
                        future.complete(session);
                    } else {
                        Log.e(TAG, "SetSchema failed: code " + result.getResultCode() + " message:"
                                + result.getErrorMessage());
                        future.completeExceptionally(new AppSearchException(result.getResultCode(),
                                result.getErrorMessage()));
                    }
                });
        return future;
    }

    @WorkerThread
    @VisibleForTesting
    @Nullable
    AppSearchSession getSession() throws ExecutionException, InterruptedException {
        return mAppSearchSessionFuture.get();
    }

    /**
     * Indexes contacts into AppSearch
     *
     * @param contacts a collection of contacts. AppSearch batch put will be used to send the
     *                 documents over in one call. So the size of this collection can't be too
     *                 big, otherwise binder {@link android.os.TransactionTooLargeException} will
     *                 be thrown.
     */
    @NonNull
    public CompletableFuture<Void> indexContactsAsync(@NonNull Collection<Person> contacts) {
        Objects.requireNonNull(contacts);

        PutDocumentsRequest request = new PutDocumentsRequest.Builder()
                .addGenericDocuments(contacts)
                .build();
        return mAppSearchSessionFuture.thenCompose(appSearchSession -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            appSearchSession.put(request, mExecutor, new BatchResultCallback<String, Void>() {
                @Override
                public void onResult(AppSearchBatchResult<String, Void> result) {
                    if (result.isSuccess()) {
                        int numDocs = result.getSuccesses().size();
                        Log.v(TAG, numDocs + " documents successfully added in AppSearch.");
                        future.complete(null);
                    } else {
                        // TODO(b/203605504) we can only have 20,000(default) contacts stored.
                        //  In order to save the latest contacts, we need to remove the oldest ones
                        //  in this ELSE. RESULT_OUT_OF_SPACE is the error code for this case.
                        future.completeExceptionally(new AppSearchException(
                                AppSearchResult.RESULT_INTERNAL_ERROR,
                                "Not all documented are added: " + result.toString()));
                    }
                }

                @Override
                public void onSystemError(Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        });
    }

    /**
     * Remove contacts from AppSearch
     *
     * @param ids a collection of contact ids. AppSearch batch remove will be used to send the
     *            ids over in one call. So the size of this collection can't be too
     *            big, otherwise binder {@link android.os.TransactionTooLargeException} will
     *            be thrown.
     */
    @NonNull
    public CompletableFuture<Void> removeContactsByIdAsync(@NonNull Collection<String> ids) {
        Objects.requireNonNull(ids);

        RemoveByDocumentIdRequest request = new RemoveByDocumentIdRequest.Builder(NAMESPACE_NAME)
                .addIds(ids)
                .build();
        return mAppSearchSessionFuture.thenCompose(appSearchSession -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            appSearchSession.remove(request, mExecutor, new BatchResultCallback<String, Void>() {
                @Override
                public void onResult(AppSearchBatchResult<String, Void> result) {
                    if (result.isSuccess()) {
                        int numDocs = result.getSuccesses().size();
                        Log.v(TAG, numDocs + " documents successfully deleted from AppSearch.");
                        future.complete(null);
                    } else {
                        future.completeExceptionally(new AppSearchException(
                                AppSearchResult.RESULT_INTERNAL_ERROR,
                                "Not all documents are deleted: " + result.toString()));
                    }
                }

                @Override
                public void onSystemError(Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        });
    }

    /**
     * Returns IDs of all contacts indexed in AppSearch
     *
     * <p>Issues an empty query with an empty projection and pages through all results, collecting
     * the document IDs to return to the caller.
     */
    @NonNull
    public CompletableFuture<List<String>> getAllContactIdsAsync() {
        return mAppSearchSessionFuture.thenCompose(appSearchSession -> {
            SearchSpec allDocumentIdsSpec = new SearchSpec.Builder()
                    .addFilterNamespaces(NAMESPACE_NAME)
                    .addFilterSchemas(Person.SCHEMA_TYPE)
                    .addProjection(Person.SCHEMA_TYPE, /*propertyPaths=*/ Collections.emptyList())
                    .setResultCountPerPage(GET_CONTACT_IDS_PAGE_SIZE)
                    .build();
            SearchResults results =
                    appSearchSession.search(/*queryExpression=*/ "", allDocumentIdsSpec);
            List<String> allContactIds = new ArrayList<>();
            return collectDocumentIdsFromAllPagesAsync(results, allContactIds)
                    .thenCompose(unused -> {
                        results.close();
                        return CompletableFuture.supplyAsync(() -> allContactIds);
                    });
        });
    }

    /**
     * Recursively pages through all search results and collects document IDs into given list.
     *
     * @param results Iterator for paging through the search results.
     * @param contactIds List for collecting and returning document IDs.
     * @return A future indicating if more results might be available.
     */
    private CompletableFuture<Boolean> collectDocumentIdsFromAllPagesAsync(
            @NonNull SearchResults results,
            @NonNull List<String> contactIds) {
        Objects.requireNonNull(results);
        Objects.requireNonNull(contactIds);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        results.getNextPage(mExecutor, callback -> {
            if (!callback.isSuccess()) {
                future.completeExceptionally(new AppSearchException(callback.getResultCode(),
                        callback.getErrorMessage()));
                return;
            }
            List<SearchResult> resultList = callback.getResultValue();
            for (int i = 0; i < resultList.size(); i++) {
                SearchResult result = resultList.get(i);
                contactIds.add(result.getGenericDocument().getId());
            }
            future.complete(!resultList.isEmpty());
        });
        return future.thenCompose(moreResults -> {
            // Recurse if there might be more results to page through.
            if (moreResults) {
                return collectDocumentIdsFromAllPagesAsync(results, contactIds);
            }
            return CompletableFuture.supplyAsync(() -> false);
        });
    }
}
