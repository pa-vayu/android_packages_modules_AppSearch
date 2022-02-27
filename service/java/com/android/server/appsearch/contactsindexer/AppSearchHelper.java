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
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.AndroidRuntimeException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Collection;
import java.util.Collections;
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

    private final Context mContext;
    private final Executor mExecutor;
    private volatile AppSearchSession mAppSearchSession;

    /**
     * Creates an initialized {@link AppSearchHelper}.
     *
     * @param executor Executor used to handle result callbacks from AppSearch.
     */
    @WorkerThread
    @NonNull
    public static AppSearchHelper createAppSearchHelper(@NonNull Context context,
            @NonNull Executor executor) throws InterruptedException, ExecutionException {
        AppSearchHelper appSearchHelper = new AppSearchHelper(Objects.requireNonNull(context),
                Objects.requireNonNull(executor));
        appSearchHelper.initialize();
        return appSearchHelper;
    }

    @VisibleForTesting
    AppSearchHelper(@NonNull Context context, @NonNull Executor executor) {
        mContext = Objects.requireNonNull(context);
        mExecutor = Objects.requireNonNull(executor);
    }

    /** Initializes the {@link AppSearchHelper}. */
    @WorkerThread
    private void initialize()
            throws InterruptedException, ExecutionException {
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            throw new AndroidRuntimeException(
                    "Can't get AppSearchManager to initialize AppSearchHelper.");
        }

        try {
            mAppSearchSession = createAppSearchSessionAsync(appSearchManager).get();
            // Always force set the schema. We are at the 1st version, so it should be fine for
            // doing it.
            // For future schema changes, we could also force set it, and rely on a full update
            // to bring back wiped data.
            setPersonSchemaAsync(mAppSearchSession, /*forceOverride=*/ true).get();
        } catch (InterruptedException | ExecutionException | RuntimeException e) {
            Log.e(TAG, "Failed to create or config a AppSearchSession during initialization.", e);
            mAppSearchSession = null;
            throw e;
        }
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
    private CompletableFuture<Void> setPersonSchemaAsync(@NonNull AppSearchSession session,
            boolean forceOverride) {
        Objects.requireNonNull(session);

        CompletableFuture<Void> future = new CompletableFuture<>();
        SetSchemaRequest.Builder schemaBuilder = new SetSchemaRequest.Builder()
                .addSchemas(ContactPoint.SCHEMA, Person.SCHEMA)
                .addRequiredPermissionsForSchemaTypeVisibility(Person.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_CONTACTS))
                .setForceOverride(forceOverride);
        session.setSchema(schemaBuilder.build(), mExecutor, mExecutor,
                result -> {
                    if (result.isSuccess()) {
                        future.complete(null);
                    } else {
                        Log.e(TAG, "SetSchema failed: code " + result.getResultCode() + " message:"
                                + result.getErrorMessage());
                        future.completeExceptionally(new AppSearchException(result.getResultCode(),
                                result.getErrorMessage()));
                    }
                });
        return future;
    }

    @VisibleForTesting
    @Nullable
    AppSearchSession getSession() {
        return mAppSearchSession;
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

        // Get the size before doing an async call.
        int size = contacts.size();
        CompletableFuture<Void> future = new CompletableFuture<>();
        PutDocumentsRequest request = new PutDocumentsRequest.Builder().addGenericDocuments(
                contacts).build();
        mAppSearchSession.put(request, mExecutor, new BatchResultCallback<String, Void>() {
            @Override
            public void onResult(AppSearchBatchResult<String, Void> result) {
                if (result.isSuccess()) {
                    Log.v(TAG, size + " documents successfully added in AppSearch.");
                    future.complete(null);
                } else {
                    // TODO(b/203605504) we can only have 20,000(default) contacts stored. In order
                    //  to save the latest contacts, we need to remove the oldest ones in this ELSE.
                    //  RESULT_OUT_OF_SPACE is the error code for this case.
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

        CompletableFuture<Void> future = new CompletableFuture<>();
        RemoveByDocumentIdRequest request = new RemoveByDocumentIdRequest.Builder(
                NAMESPACE_NAME).addIds(ids).build();
        mAppSearchSession.remove(request, mExecutor, new BatchResultCallback<String, Void>() {
            @Override
            public void onResult(AppSearchBatchResult<String, Void> result) {
                if (result.isSuccess()) {
                    Log.v(TAG, result.getSuccesses().size()
                            + " documents successfully deleted from AppSearch.");
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
    }
}
