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

package android.app.appsearch;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchObserverProxy;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.observer.AppSearchObserverCallback;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides a connection to all AppSearch databases the querying application has been granted access
 * to.
 *
 * <p>This class is thread safe.
 *
 * @see AppSearchSession
 */
public class GlobalSearchSession implements Closeable {
    private static final String TAG = "AppSearchGlobalSearchSe";

    private final String mPackageName;
    private final UserHandle mUserHandle;
    private final IAppSearchManager mService;

    // Management of observer callbacks. Key is observed package.
    @GuardedBy("mObserverCallbacksLocked")
    private final Map<String, Map<AppSearchObserverCallback, IAppSearchObserverProxy>>
            mObserverCallbacksLocked = new ArrayMap<>();

    private boolean mIsMutated = false;
    private boolean mIsClosed = false;

    /**
     * Creates a search session for the client, defined by the {@code userHandle} and
     * {@code packageName}.
     */
    static void createGlobalSearchSession(
            @NonNull IAppSearchManager service,
            @NonNull UserHandle userHandle,
            @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        GlobalSearchSession globalSearchSession = new GlobalSearchSession(service, userHandle,
                packageName);
        globalSearchSession.initialize(executor, callback);
    }

    // NOTE: No instance of this class should be created or returned except via initialize().
    // Once the callback.accept has been called here, the class is ready to use.
    private void initialize(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        try {
            mService.initialize(
                    mPackageName,
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<Void> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    callback.accept(
                                            AppSearchResult.newSuccessfulResult(
                                                    GlobalSearchSession.this));
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
                                }
                    });
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private GlobalSearchSession(@NonNull IAppSearchManager service, @NonNull UserHandle userHandle,
            @NonNull String packageName) {
        mService = service;
        mUserHandle = userHandle;
        mPackageName = packageName;
    }

    /**
     * Gets {@link GenericDocument} objects by document IDs in a namespace in a database in a
     * package from the {@link GlobalSearchSession} database. If the package or database doesn't
     * exist or if the calling package doesn't have access, the gets will be handled as
     * failures in an {@link AppSearchBatchResult} object in the callback.
     *
     * @param packageName the name of the package to get from
     * @param databaseName the name of the database to get from
     * @param request a request containing a namespace and IDs to get documents for.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the input IDs. The values
     *                 are the returned {@link GenericDocument}s on success, or a failed
     *                 {@link AppSearchResult} otherwise. IDs that are not found will return a
     *                 failed {@link AppSearchResult} with a result code of
     *                 {@link AppSearchResult#RESULT_NOT_FOUND}. If an unexpected internal error
     *                 occurs in the AppSearch service, {@link BatchResultCallback#onSystemError}
     *                 will be invoked with a {@link Throwable}.
     */
    public void getByDocumentId(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull GetByDocumentIdRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, GenericDocument> callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(databaseName);
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");

        try {
            mService.getDocuments(
                    /*callerPackageName=*/mPackageName,
                    /*targetPackageName=*/packageName,
                    databaseName,
                    request.getNamespace(),
                    new ArrayList<>(request.getIds()),
                    request.getProjectionsInternal(),
                    mUserHandle,
                    SystemClock.elapsedRealtime(),
                    SearchSessionUtil.createGetDocumentCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves documents from all AppSearch databases that the querying application has access to.
     *
     * <p>Applications can be granted access to documents by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage} when building a schema.
     *
     * <p>Document access can also be granted to system UIs by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeDisplayedBySystem} when building a schema.
     *
     * <p>See {@link AppSearchSession#search} for a detailed explanation on forming a query string.
     *
     * <p>This method is lightweight. The heavy work will be done in {@link
     * SearchResults#getNextPage}.
     *
     * @param queryExpression query string to search.
     * @param searchSpec spec for setting document filters, adding projection, setting term match
     *     type, etc.
     * @return a {@link SearchResults} object for retrieved matched documents.
     */
    @NonNull
    public SearchResults search(@NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        Objects.requireNonNull(queryExpression);
        Objects.requireNonNull(searchSpec);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");
        return new SearchResults(mService, mPackageName, /*databaseName=*/null, queryExpression,
                searchSpec, mUserHandle);
    }

    /**
     * Reports that a particular document has been used from a system surface.
     *
     * <p>See {@link AppSearchSession#reportUsage} for a general description of document usage, as
     * well as an API that can be used by the app itself.
     *
     * <p>Usage reported via this method is accounted separately from usage reported via
     * {@link AppSearchSession#reportUsage} and may be accessed using the constants
     * {@link SearchSpec#RANKING_STRATEGY_SYSTEM_USAGE_COUNT} and
     * {@link SearchSpec#RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP}.
     *
     * @param request The usage reporting request.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors. If the operation succeeds, the callback will be
     *                 invoked with an {@link AppSearchResult} whose value is {@code null}. The
     *                 callback will be invoked with an {@link AppSearchResult} of
     *                 {@link AppSearchResult#RESULT_SECURITY_ERROR} if this API is invoked by an
     *                 app which is not part of the system.
     */
    public void reportSystemUsage(
            @NonNull ReportSystemUsageRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<Void>> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");
        try {
            mService.reportUsage(
                    request.getPackageName(),
                    request.getDatabaseName(),
                    request.getNamespace(),
                    request.getDocumentId(),
                    request.getUsageTimestampMillis(),
                    /*systemUsage=*/ true,
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> callback.accept(resultParcel.getResult()));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the collection of schemas most recently successfully provided to {@link
     * AppSearchSession#setSchema} for any types belonging to the requested package and database
     * that the caller has been granted access to.
     *
     * <p>If the requested package/database combination does not exist or the caller has not been
     * granted access to it, then an empty GetSchemaResponse will be returned.
     *
     * @param packageName the package that owns the requested {@link AppSearchSchema} instances.
     * @param databaseName the database that owns the requested {@link AppSearchSchema} instances.
     * @return The pending {@link GetSchemaResponse} containing the schemas that the caller has
     *     access to or an empty GetSchemaResponse if the request package and database does not
     *     exist, has not set a schema or contains no schemas that are accessible to the caller.
     */
    // This call hits disk; async API prevents us from treating these calls as properties.
    public void getSchema(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GetSchemaResponse>> callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(databaseName);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");
        try {
            mService.getSchema(
                mPackageName,
                packageName,
                databaseName,
                mUserHandle,
                new IAppSearchResultCallback.Stub() {
                    @Override
                    public void onResult(AppSearchResultParcel resultParcel) {
                        executor.execute(() -> {
                            AppSearchResult<Bundle> result = resultParcel.getResult();
                            if (result.isSuccess()) {
                                GetSchemaResponse response =
                                        new GetSchemaResponse(result.getResultValue());
                                callback.accept(AppSearchResult.newSuccessfulResult(response));
                            } else {
                                callback.accept(AppSearchResult.newFailedResult(result));
                            }
                        });
                    }
                });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds an {@link AppSearchObserverCallback} to monitor changes within the
     * databases owned by {@code observedPackage} if they match the given
     * {@link android.app.appsearch.observer.ObserverSpec}.
     *
     * <p>If the data owned by {@code observedPackage} is not visible to you, the registration call
     * will succeed but no notifications will be dispatched. Notifications could start flowing later
     * if {@code observedPackage} changes its schema visibility settings.
     *
     * <p>If no package matching {@code observedPackage} exists on the system, the registration call
     * will succeed but no notifications will be dispatched. Notifications could start flowing later
     * if {@code observedPackage} is installed and starts indexing data.
     *
     * @param observedPackage Package whose changes to monitor
     * @param spec            Specification of what types of changes to listen for
     * @param executor        Executor on which to call the {@code observer} callback methods.
     * @param observer        Callback to trigger when a schema or document changes
     * @throws AppSearchException If an unexpected error occurs when trying to register an observer.
     */
    public void addObserver(
            @NonNull String observedPackage,
            @NonNull ObserverSpec spec,
            @NonNull Executor executor,
            @NonNull AppSearchObserverCallback observer) throws AppSearchException {
        Objects.requireNonNull(observedPackage);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(observer);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");

        synchronized (mObserverCallbacksLocked) {
            IAppSearchObserverProxy stub = null;
            Map<AppSearchObserverCallback, IAppSearchObserverProxy> observersForPackage =
                    mObserverCallbacksLocked.get(observedPackage);
            if (observersForPackage != null) {
                stub = observersForPackage.get(observer);
            }
            if (stub == null) {
                // No stub is associated with this package and observer, so we must create one.
                stub = new IAppSearchObserverProxy.Stub() {
                    @Override
                    public void onSchemaChanged(
                            @NonNull String packageName,
                            @NonNull String databaseName,
                            @NonNull List<String> changedSchemaNames) {
                        executor.execute(() -> {
                            SchemaChangeInfo changeInfo = new SchemaChangeInfo(
                                    packageName, databaseName, new ArraySet<>(changedSchemaNames));
                            observer.onSchemaChanged(changeInfo);
                        });
                    }

                    @Override
                    public void onDocumentChanged(
                            @NonNull String packageName,
                            @NonNull String databaseName,
                            @NonNull String namespace,
                            @NonNull String schemaName,
                            @NonNull List<String> changedDocumentIds) {
                        executor.execute(() -> {
                            DocumentChangeInfo changeInfo = new DocumentChangeInfo(
                                    packageName,
                                    databaseName,
                                    namespace,
                                    schemaName,
                                    new ArraySet<>(changedDocumentIds));
                            observer.onDocumentChanged(changeInfo);
                        });
                    }
                };
            }

            // Regardless of whether this stub was fresh or not, we have to register it again
            // because the user might be supplying a different spec.
            AppSearchResultParcel<Void> resultParcel;
            try {
                resultParcel = mService.addObserver(
                        mPackageName, observedPackage, spec.getBundle(), mUserHandle, stub);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            // See whether registration was successful
            AppSearchResult<Void> result = resultParcel.getResult();
            if (!result.isSuccess()) {
                throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
            }

            // Now that registration has succeeded, save this stub into our in-memory cache. This
            // isn't done when errors occur because the user may not call removeObserver if
            // addObserver threw.
            if (observersForPackage == null) {
                observersForPackage = new ArrayMap<>();
                mObserverCallbacksLocked.put(observedPackage, observersForPackage);
            }
            observersForPackage.put(observer, stub);
        }
    }

    /**
     * Removes previously registered {@link AppSearchObserverCallback} instances from the system.
     *
     * <p>All instances of {@link AppSearchObserverCallback} which are registered to observe
     * {@code observedPackage} and compare equal to the provided callback using
     * {@code AppSearchObserverCallback#equals} will be removed.
     *
     * <p>If no matching observers have been registered, this method has no effect. If multiple
     * matching observers have been registered, all will be removed.
     *
     * @param observedPackage Package in which the observers to be removed are registered
     * @param observer        Callback to unregister
     */
    public void removeObserver(
            @NonNull String observedPackage,
            @NonNull AppSearchObserverCallback observer) throws AppSearchException {
        Objects.requireNonNull(observedPackage);
        Objects.requireNonNull(observer);
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");

        IAppSearchObserverProxy stub;
        synchronized (mObserverCallbacksLocked) {
            Map<AppSearchObserverCallback, IAppSearchObserverProxy> observersForPackage =
                    mObserverCallbacksLocked.get(observedPackage);
            if (observersForPackage == null) {
                return;  // No observers registered for this package. Nothing to do.
            }
            stub = observersForPackage.get(observer);
            if (stub == null) {
                return;  // No such observer registered. Nothing to do.
            }

            AppSearchResultParcel<Void> resultParcel;
            try {
                resultParcel = mService.removeObserver(
                        mPackageName, observedPackage, mUserHandle, stub);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            AppSearchResult<Void> result = resultParcel.getResult();
            if (!result.isSuccess()) {
                throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
            }

            // Only remove from the in-memory map once removal from the service side succeeds
            observersForPackage.remove(observer);
            if (observersForPackage.isEmpty()) {
                mObserverCallbacksLocked.remove(observedPackage);
            }
        }
    }

    /**
     * Closes the {@link GlobalSearchSession}. Persists all mutations, including usage reports, to
     * disk.
     */
    @Override
    public void close() {
        if (mIsMutated && !mIsClosed) {
            try {
                mService.persistToDisk(
                        mPackageName,
                        mUserHandle,
                        /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime());
                mIsClosed = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to close the GlobalSearchSession", e);
            }
        }
    }
}
