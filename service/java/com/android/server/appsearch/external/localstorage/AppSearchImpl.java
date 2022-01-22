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

package com.android.server.appsearch.external.localstorage;

import static android.app.appsearch.AppSearchResult.RESULT_INTERNAL_ERROR;
import static android.app.appsearch.AppSearchResult.RESULT_SECURITY_ERROR;

import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.addPrefixToDocument;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.createPrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getDatabaseName;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPackageName;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.removePrefixesFromDocument;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.VisibilityDocument;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.observer.AppSearchObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.util.LogUtil;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.ResultCodeToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SchemaToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchResultToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchSpecToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SetSchemaResponseToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.TypePropertyPathToProtoConverter;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.OptimizeStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.RemoveStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;
import com.android.server.appsearch.external.localstorage.stats.SetSchemaStats;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;

import com.google.android.icing.IcingSearchEngine;
import com.google.android.icing.proto.DeleteByQueryResultProto;
import com.google.android.icing.proto.DeleteResultProto;
import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.DocumentStorageInfoProto;
import com.google.android.icing.proto.GetAllNamespacesResultProto;
import com.google.android.icing.proto.GetOptimizeInfoResultProto;
import com.google.android.icing.proto.GetResultProto;
import com.google.android.icing.proto.GetResultSpecProto;
import com.google.android.icing.proto.GetSchemaResultProto;
import com.google.android.icing.proto.IcingSearchEngineOptions;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.NamespaceStorageInfoProto;
import com.google.android.icing.proto.OptimizeResultProto;
import com.google.android.icing.proto.PersistToDiskResultProto;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.ReportUsageResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.StorageInfoProto;
import com.google.android.icing.proto.StorageInfoResultProto;
import com.google.android.icing.proto.TypePropertyMask;
import com.google.android.icing.proto.UsageReport;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages interaction with the native IcingSearchEngine and other components to implement AppSearch
 * functionality.
 *
 * <p>Never create two instances using the same folder.
 *
 * <p>A single instance of {@link AppSearchImpl} can support all packages and databases. This is
 * done by combining the package and database name into a unique prefix and prefixing the schemas
 * and documents stored under that owner. Schemas and documents are physically saved together in
 * {@link IcingSearchEngine}, but logically isolated:
 *
 * <ul>
 *   <li>Rewrite SchemaType in SchemaProto by adding the package-database prefix and save into
 *       SchemaTypes set in {@link #setSchema}.
 *   <li>Rewrite namespace and SchemaType in DocumentProto by adding package-database prefix and
 *       save to namespaces set in {@link #putDocument}.
 *   <li>Remove package-database prefix when retrieving documents in {@link #getDocument} and {@link
 *       #query}.
 *   <li>Rewrite filters in {@link SearchSpecProto} to have all namespaces and schema types of the
 *       queried database when user using empty filters in {@link #query}.
 * </ul>
 *
 * <p>Methods in this class belong to two groups, the query group and the mutate group.
 *
 * <ul>
 *   <li>All methods are going to modify global parameters and data in Icing are executed under
 *       WRITE lock to keep thread safety.
 *   <li>All methods are going to access global parameters or query data from Icing are executed
 *       under READ lock to improve query performance.
 * </ul>
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
@WorkerThread
public final class AppSearchImpl implements Closeable {
    private static final String TAG = "AppSearchImpl";

    /** A value 0 means that there're no more pages in the search results. */
    private static final long EMPTY_PAGE_TOKEN = 0;

    @VisibleForTesting static final int CHECK_OPTIMIZE_INTERVAL = 100;

    /** A GetResultSpec that uses projection to skip all properties. */
    private static final GetResultSpecProto GET_RESULT_SPEC_NO_PROPERTIES =
            GetResultSpecProto.newBuilder()
                    .addTypePropertyMasks(
                            TypePropertyMask.newBuilder()
                                    .setSchemaType(
                                            GetByDocumentIdRequest.PROJECTION_SCHEMA_TYPE_WILDCARD))
                    .build();

    /** A ResultSpec that uses projection to skip all properties. */
    private static final ResultSpecProto RESULT_SPEC_NO_PROPERTIES =
            ResultSpecProto.newBuilder()
                    .addTypePropertyMasks(
                            TypePropertyMask.newBuilder()
                                    .setSchemaType(
                                            GetByDocumentIdRequest.PROJECTION_SCHEMA_TYPE_WILDCARD))
                    .build();

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final LogUtil mLogUtil = new LogUtil(TAG);
    private final OptimizeStrategy mOptimizeStrategy;
    private final LimitConfig mLimitConfig;

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    final IcingSearchEngine mIcingSearchEngineLocked;

    // This map contains schema types and SchemaTypeConfigProtos for all package-database
    // prefixes. It maps each package-database prefix to an inner-map. The inner-map maps each
    // prefixed schema type to its respective SchemaTypeConfigProto.
    @GuardedBy("mReadWriteLock")
    private final Map<String, Map<String, SchemaTypeConfigProto>> mSchemaMapLocked =
            new ArrayMap<>();

    // This map contains namespaces for all package-database prefixes. All values in the map are
    // prefixed with the package-database prefix.
    // TODO(b/172360376): Check if this can be replaced with an ArrayMap
    @GuardedBy("mReadWriteLock")
    private final Map<String, Set<String>> mNamespaceMapLocked = new HashMap<>();

    /** Maps package name to active document count. */
    @GuardedBy("mReadWriteLock")
    private final Map<String, Integer> mDocumentCountMapLocked = new ArrayMap<>();

    // Maps packages to the set of valid nextPageTokens that the package can manipulate. A token
    // is unique and constant per query (i.e. the same token '123' is used to iterate through
    // pages of search results). The tokens themselves are generated and tracked by
    // IcingSearchEngine. IcingSearchEngine considers a token valid and won't be reused
    // until we call invalidateNextPageToken on the token.
    //
    // Note that we synchronize on itself because the nextPageToken cache is checked at
    // query-time, and queries are done in parallel with a read lock. Ideally, this would be
    // guarded by the normal mReadWriteLock.writeLock, but ReentrantReadWriteLocks can't upgrade
    // read to write locks. This lock should be acquired at the smallest scope possible.
    // mReadWriteLock is a higher-level lock, so calls shouldn't be made out
    // to any functions that grab the lock.
    @GuardedBy("mNextPageTokensLocked")
    private final Map<String, Set<Long>> mNextPageTokensLocked = new ArrayMap<>();

    private final ObserverManager mObserverManager = new ObserverManager();

    /**
     * VisibilityStore will be used in {@link #setSchema} and {@link #getSchema} to store and query
     * visibility information. But to create a {@link VisibilityStore}, it will call {@link
     * #setSchema} and {@link #getSchema} to get the visibility schema. Make it nullable to avoid
     * call it before we actually create it.
     */
    @Nullable
    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    final VisibilityStore mVisibilityStoreLocked;

    @Nullable
    @GuardedBy("mReadWriteLock")
    private final VisibilityChecker mVisibilityCheckerLocked;

    /**
     * The counter to check when to call {@link #checkForOptimize}. The interval is {@link
     * #CHECK_OPTIMIZE_INTERVAL}.
     */
    @GuardedBy("mReadWriteLock")
    private int mOptimizeIntervalCountLocked = 0;

    /** Whether this instance has been closed, and therefore unusable. */
    @GuardedBy("mReadWriteLock")
    private boolean mClosedLocked = false;

    /**
     * Creates and initializes an instance of {@link AppSearchImpl} which writes data to the given
     * folder.
     *
     * <p>Clients can pass a {@link AppSearchLogger} here through their AppSearchSession, but it
     * can't be saved inside {@link AppSearchImpl}, because the impl will be shared by all the
     * sessions for the same package in JetPack.
     *
     * <p>Instead, logger instance needs to be passed to each individual method, like create, query
     * and putDocument.
     *
     * @param initStatsBuilder collects stats for initialization if provided.
     * @param visibilityChecker The {@link VisibilityChecker} that check whether the caller has
     *     access to aa specific schema. Pass null will lost that ability and global querier could
     *     only get their own data.
     */
    @NonNull
    public static AppSearchImpl create(
            @NonNull File icingDir,
            @NonNull LimitConfig limitConfig,
            @Nullable InitializeStats.Builder initStatsBuilder,
            @NonNull OptimizeStrategy optimizeStrategy,
            @Nullable VisibilityChecker visibilityChecker)
            throws AppSearchException {
        return new AppSearchImpl(
                icingDir, limitConfig, initStatsBuilder, optimizeStrategy, visibilityChecker);
    }

    /** @param initStatsBuilder collects stats for initialization if provided. */
    private AppSearchImpl(
            @NonNull File icingDir,
            @NonNull LimitConfig limitConfig,
            @Nullable InitializeStats.Builder initStatsBuilder,
            @NonNull OptimizeStrategy optimizeStrategy,
            @Nullable VisibilityChecker visibilityChecker)
            throws AppSearchException {
        Objects.requireNonNull(icingDir);
        mLimitConfig = Objects.requireNonNull(limitConfig);
        mOptimizeStrategy = Objects.requireNonNull(optimizeStrategy);
        mVisibilityCheckerLocked = visibilityChecker;

        mReadWriteLock.writeLock().lock();
        try {
            // We synchronize here because we don't want to call IcingSearchEngine.initialize() more
            // than once. It's unnecessary and can be a costly operation.
            IcingSearchEngineOptions options =
                    IcingSearchEngineOptions.newBuilder()
                            .setBaseDir(icingDir.getAbsolutePath())
                            .build();
            mLogUtil.piiTrace("Constructing IcingSearchEngine, request", options);
            mIcingSearchEngineLocked = new IcingSearchEngine(options);
            mLogUtil.piiTrace(
                    "Constructing IcingSearchEngine, response",
                    Objects.hashCode(mIcingSearchEngineLocked));

            // The core initialization procedure. If any part of this fails, we bail into
            // resetLocked(), deleting all data (but hopefully allowing AppSearchImpl to come up).
            try {
                mLogUtil.piiTrace("icingSearchEngine.initialize, request");
                InitializeResultProto initializeResultProto = mIcingSearchEngineLocked.initialize();
                mLogUtil.piiTrace(
                        "icingSearchEngine.initialize, response",
                        initializeResultProto.getStatus(),
                        initializeResultProto);

                if (initStatsBuilder != null) {
                    initStatsBuilder
                            .setStatusCode(
                                    statusProtoToResultCode(initializeResultProto.getStatus()))
                            // TODO(b/173532925) how to get DeSyncs value
                            .setHasDeSync(false);
                    AppSearchLoggerHelper.copyNativeStats(
                            initializeResultProto.getInitializeStats(), initStatsBuilder);
                }
                checkSuccess(initializeResultProto.getStatus());

                // Read all protos we need to construct AppSearchImpl's cache maps
                long prepareSchemaAndNamespacesLatencyStartMillis = SystemClock.elapsedRealtime();
                SchemaProto schemaProto = getSchemaProtoLocked();

                mLogUtil.piiTrace("init:getAllNamespaces, request");
                GetAllNamespacesResultProto getAllNamespacesResultProto =
                        mIcingSearchEngineLocked.getAllNamespaces();
                mLogUtil.piiTrace(
                        "init:getAllNamespaces, response",
                        getAllNamespacesResultProto.getNamespacesCount(),
                        getAllNamespacesResultProto);

                StorageInfoProto storageInfoProto = getRawStorageInfoProto();

                // Log the time it took to read the data that goes into the cache maps
                if (initStatsBuilder != null) {
                    // In case there is some error for getAllNamespaces, we can still
                    // set the latency for preparation.
                    // If there is no error, the value will be overridden by the actual one later.
                    initStatsBuilder
                            .setStatusCode(
                                    statusProtoToResultCode(
                                            getAllNamespacesResultProto.getStatus()))
                            .setPrepareSchemaAndNamespacesLatencyMillis(
                                    (int)
                                            (SystemClock.elapsedRealtime()
                                                    - prepareSchemaAndNamespacesLatencyStartMillis));
                }
                checkSuccess(getAllNamespacesResultProto.getStatus());

                // Populate schema map
                List<SchemaTypeConfigProto> schemaProtoTypesList = schemaProto.getTypesList();
                for (int i = 0; i < schemaProtoTypesList.size(); i++) {
                    SchemaTypeConfigProto schema = schemaProtoTypesList.get(i);
                    String prefixedSchemaType = schema.getSchemaType();
                    addToMap(mSchemaMapLocked, getPrefix(prefixedSchemaType), schema);
                }

                // Populate namespace map
                List<String> prefixedNamespaceList =
                        getAllNamespacesResultProto.getNamespacesList();
                for (int i = 0; i < prefixedNamespaceList.size(); i++) {
                    String prefixedNamespace = prefixedNamespaceList.get(i);
                    addToMap(mNamespaceMapLocked, getPrefix(prefixedNamespace), prefixedNamespace);
                }

                // Populate document count map
                rebuildDocumentCountMapLocked(storageInfoProto);

                // logging prepare_schema_and_namespaces latency
                if (initStatsBuilder != null) {
                    initStatsBuilder.setPrepareSchemaAndNamespacesLatencyMillis(
                            (int)
                                    (SystemClock.elapsedRealtime()
                                            - prepareSchemaAndNamespacesLatencyStartMillis));
                }

                mLogUtil.piiTrace("Init completed successfully");

            } catch (AppSearchException e) {
                // Some error. Reset and see if it fixes it.
                Log.e(TAG, "Error initializing, resetting IcingSearchEngine.", e);
                if (initStatsBuilder != null) {
                    initStatsBuilder.setStatusCode(e.getResultCode());
                }
                resetLocked(initStatsBuilder);
            }

            long prepareVisibilityStoreLatencyStartMillis = SystemClock.elapsedRealtime();
            mVisibilityStoreLocked = new VisibilityStore(this);
            long prepareVisibilityStoreLatencyEndMillis = SystemClock.elapsedRealtime();
            if (initStatsBuilder != null) {
                initStatsBuilder.setPrepareVisibilityStoreLatencyMillis(
                        (int)
                                (prepareVisibilityStoreLatencyEndMillis
                                        - prepareVisibilityStoreLatencyStartMillis));
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private void throwIfClosedLocked() {
        if (mClosedLocked) {
            throw new IllegalStateException("Trying to use a closed AppSearchImpl instance.");
        }
    }

    /**
     * Persists data to disk and closes the instance.
     *
     * <p>This instance is no longer usable after it's been closed. Call {@link #create} to create a
     * new, usable instance.
     */
    @Override
    public void close() {
        mReadWriteLock.writeLock().lock();
        try {
            if (mClosedLocked) {
                return;
            }
            persistToDisk(PersistType.Code.FULL);
            mLogUtil.piiTrace("icingSearchEngine.close, request");
            mIcingSearchEngineLocked.close();
            mLogUtil.piiTrace("icingSearchEngine.close, response");
            mClosedLocked = true;
        } catch (AppSearchException e) {
            Log.w(TAG, "Error when closing AppSearchImpl.", e);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Updates the AppSearch schema for this app.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the schemas.
     * @param databaseName The name of the database where this schema lives.
     * @param schemas Schemas to set for this app.
     * @param visibilityDocuments {@link VisibilityDocument}s that contain all visibility setting
     *     information for those schemas has user custom settings. Other schemas in the list that
     *     don't has a {@link VisibilityDocument} will be treated as having the default visibility,
     *     which is accessible by the system and no other packages.
     * @param forceOverride Whether to force-apply the schema even if it is incompatible. Documents
     *     which do not comply with the new schema will be deleted.
     * @param version The overall version number of the request.
     * @param setSchemaStatsBuilder Builder for {@link SetSchemaStats} to hold stats for setSchema
     * @return The response contains deleted schema types and incompatible schema types of this
     *     call.
     * @throws AppSearchException On IcingSearchEngine error. If the status code is
     *     FAILED_PRECONDITION for the incompatible change, the exception will be converted to the
     *     SetSchemaResponse.
     */
    @NonNull
    public SetSchemaResponse setSchema(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull List<AppSearchSchema> schemas,
            @NonNull List<VisibilityDocument> visibilityDocuments,
            boolean forceOverride,
            int version,
            @Nullable SetSchemaStats.Builder setSchemaStatsBuilder)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            SchemaProto.Builder existingSchemaBuilder = getSchemaProtoLocked().toBuilder();

            SchemaProto.Builder newSchemaBuilder = SchemaProto.newBuilder();
            for (int i = 0; i < schemas.size(); i++) {
                AppSearchSchema schema = schemas.get(i);
                SchemaTypeConfigProto schemaTypeProto =
                        SchemaToProtoConverter.toSchemaTypeConfigProto(schema, version);
                newSchemaBuilder.addTypes(schemaTypeProto);
            }

            String prefix = createPrefix(packageName, databaseName);
            // Combine the existing schema (which may have types from other prefixes) with this
            // prefix's new schema. Modifies the existingSchemaBuilder.
            RewrittenSchemaResults rewrittenSchemaResults =
                    rewriteSchema(prefix, existingSchemaBuilder, newSchemaBuilder.build());

            // Apply schema
            SchemaProto finalSchema = existingSchemaBuilder.build();
            mLogUtil.piiTrace("setSchema, request", finalSchema.getTypesCount(), finalSchema);
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(finalSchema, forceOverride);
            mLogUtil.piiTrace(
                    "setSchema, response", setSchemaResultProto.getStatus(), setSchemaResultProto);

            if (setSchemaStatsBuilder != null) {
                setSchemaStatsBuilder.setStatusCode(
                        statusProtoToResultCode(setSchemaResultProto.getStatus()));
                AppSearchLoggerHelper.copyNativeStats(setSchemaResultProto, setSchemaStatsBuilder);
            }

            // Determine whether it succeeded.
            try {
                checkSuccess(setSchemaResultProto.getStatus());
            } catch (AppSearchException e) {
                // Swallow the exception for the incompatible change case. We will propagate
                // those deleted schemas and incompatible types to the SetSchemaResponse.
                boolean isFailedPrecondition =
                        setSchemaResultProto.getStatus().getCode()
                                == StatusProto.Code.FAILED_PRECONDITION;
                boolean isIncompatible =
                        setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                                || setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0;
                if (isFailedPrecondition && isIncompatible) {
                    return SetSchemaResponseToProtoConverter.toSetSchemaResponse(
                            setSchemaResultProto, prefix);
                } else {
                    throw e;
                }
            }

            // Update derived data structures.
            for (SchemaTypeConfigProto schemaTypeConfigProto :
                    rewrittenSchemaResults.mRewrittenPrefixedTypes.values()) {
                addToMap(mSchemaMapLocked, prefix, schemaTypeConfigProto);
            }

            for (String schemaType : rewrittenSchemaResults.mDeletedPrefixedTypes) {
                removeFromMap(mSchemaMapLocked, prefix, schemaType);
            }
            // Since the constructor of VisibilityStore will set schema. Avoid call visibility
            // store before we have already created it.
            if (mVisibilityStoreLocked != null) {
                // Add prefix to all visibility documents.
                List<VisibilityDocument> prefixedVisibilityDocuments =
                        new ArrayList<>(visibilityDocuments.size());
                // Find out which Visibility document is deleted or changed to all-default settings.
                // We need to remove them from Visibility Store.
                Set<String> deprecatedVisibilityDocuments =
                        new ArraySet<>(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet());
                for (int i = 0; i < visibilityDocuments.size(); i++) {
                    VisibilityDocument unPrefixedDocument = visibilityDocuments.get(i);
                    // The VisibilityDocument is controlled by the client and it's untrusted but we
                    // make it safe by appending a prefix.
                    // We must control the package-database prefix. Therefore even if the client
                    // fake the id, they can only mess their own app. That's totally allowed and
                    // they can do this via the public API too.
                    String prefixedSchemaType = prefix + unPrefixedDocument.getId();
                    prefixedVisibilityDocuments.add(
                            new VisibilityDocument(
                                    unPrefixedDocument.toBuilder()
                                            .setId(prefixedSchemaType)
                                            .build()));
                    // This schema has visibility settings. We should keep it from the removal list.
                    deprecatedVisibilityDocuments.remove(prefixedSchemaType);
                }
                // Now deprecatedVisibilityDocuments contains those existing schemas that has
                // all-default visibility settings, add deleted schemas. That's all we need to
                // remove.
                deprecatedVisibilityDocuments.addAll(rewrittenSchemaResults.mDeletedPrefixedTypes);
                mVisibilityStoreLocked.removeVisibility(deprecatedVisibilityDocuments);
                mVisibilityStoreLocked.setVisibility(prefixedVisibilityDocuments);
            }
            return SetSchemaResponseToProtoConverter.toSetSchemaResponse(
                    setSchemaResultProto, prefix);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Retrieves the AppSearch schema for this package name, database.
     *
     * <p>This method belongs to query group.
     *
     * @param callerPackageName Package name of the calling app
     * @param packageName Package that owns the requested {@link AppSearchSchema} instances.
     * @param databaseName Database that owns the requested {@link AppSearchSchema} instances.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public GetSchemaResponse getSchema(
            @NonNull String callerPackageName,
            @NonNull String packageName,
            @NonNull String databaseName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            SchemaProto fullSchema = getSchemaProtoLocked();
            String prefix = createPrefix(packageName, databaseName);
            GetSchemaResponse.Builder responseBuilder = new GetSchemaResponse.Builder();
            for (int i = 0; i < fullSchema.getTypesCount(); i++) {
                // Check that this type belongs to the requested app and that the caller has
                // access to it.
                SchemaTypeConfigProto typeConfig = fullSchema.getTypes(i);
                String prefixedSchemaType = typeConfig.getSchemaType();
                String typePrefix = getPrefix(prefixedSchemaType);
                String typeName = typeConfig.getSchemaType().substring(typePrefix.length());
                // TODO(b/215624105) use VisibilityChecker to check the access.
                if (!prefix.equals(typePrefix)
                        || !hasAccessToType(
                                callerPackageName, packageName, databaseName, typeName)) {
                    continue;
                }

                // Rewrite SchemaProto.types.schema_type
                SchemaTypeConfigProto.Builder typeConfigBuilder = typeConfig.toBuilder();
                PrefixUtil.removePrefixesFromSchemaType(typeConfigBuilder);
                AppSearchSchema schema =
                        SchemaToProtoConverter.toAppSearchSchema(typeConfigBuilder);

                // TODO(b/183050495) find a place to store the version for the database, rather
                // than read from a schema.
                responseBuilder.setVersion(typeConfig.getVersion());
                responseBuilder.addSchema(schema);

                // Populate visibility info. Since the constructor of VisibilityStore will get
                // schema. Avoid call visibility store before we have already created it.
                if (mVisibilityStoreLocked != null) {
                    VisibilityDocument visibilityDocument =
                            mVisibilityStoreLocked.getVisibility(prefixedSchemaType);
                    if (visibilityDocument != null) {
                        if (visibilityDocument.isNotDisplayedBySystem()) {
                            responseBuilder.addSchemaTypeNotDisplayedBySystem(typeName);
                        }
                        String[] packageNames = visibilityDocument.getPackageNames();
                        byte[][] sha256Certs = visibilityDocument.getSha256Certs();
                        if (packageNames.length != sha256Certs.length) {
                            throw new AppSearchException(
                                    RESULT_INTERNAL_ERROR,
                                    "The length of package names and sha256Crets are different!");
                        }
                        if (packageNames.length != 0) {
                            Set<PackageIdentifier> packageIdentifier = new ArraySet<>();
                            for (int j = 0; j < packageNames.length; j++) {
                                packageIdentifier.add(
                                        new PackageIdentifier(packageNames[j], sha256Certs[j]));
                            }
                            responseBuilder.setSchemaTypeVisibleToPackages(
                                    typeName, packageIdentifier);
                        }
                    }
                }
            }
            return responseBuilder.build();

        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieves the list of namespaces with at least one document for this package name, database.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName Package name that owns this schema
     * @param databaseName The name of the database where this schema lives.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public List<String> getNamespaces(@NonNull String packageName, @NonNull String databaseName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            mLogUtil.piiTrace("getAllNamespaces, request");
            // We can't just use mNamespaceMap here because we have no way to prune namespaces from
            // mNamespaceMap when they have no more documents (e.g. after setting schema to empty or
            // using deleteByQuery).
            GetAllNamespacesResultProto getAllNamespacesResultProto =
                    mIcingSearchEngineLocked.getAllNamespaces();
            mLogUtil.piiTrace(
                    "getAllNamespaces, response",
                    getAllNamespacesResultProto.getNamespacesCount(),
                    getAllNamespacesResultProto);
            checkSuccess(getAllNamespacesResultProto.getStatus());
            String prefix = createPrefix(packageName, databaseName);
            List<String> results = new ArrayList<>();
            for (int i = 0; i < getAllNamespacesResultProto.getNamespacesCount(); i++) {
                String prefixedNamespace = getAllNamespacesResultProto.getNamespaces(i);
                if (prefixedNamespace.startsWith(prefix)) {
                    results.add(prefixedNamespace.substring(prefix.length()));
                }
            }
            return results;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Adds a document to the AppSearch index.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param document The document to index.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void putDocument(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull GenericDocument document,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        PutDocumentStats.Builder pStatsBuilder = null;
        if (logger != null) {
            pStatsBuilder = new PutDocumentStats.Builder(packageName, databaseName);
        }
        long totalStartTimeMillis = SystemClock.elapsedRealtime();

        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            // Generate Document Proto
            long generateDocumentProtoStartTimeMillis = SystemClock.elapsedRealtime();
            DocumentProto.Builder documentBuilder =
                    GenericDocumentToProtoConverter.toDocumentProto(document).toBuilder();
            long generateDocumentProtoEndTimeMillis = SystemClock.elapsedRealtime();

            // Rewrite Document Type
            long rewriteDocumentTypeStartTimeMillis = SystemClock.elapsedRealtime();
            String prefix = createPrefix(packageName, databaseName);
            addPrefixToDocument(documentBuilder, prefix);
            long rewriteDocumentTypeEndTimeMillis = SystemClock.elapsedRealtime();
            DocumentProto finalDocument = documentBuilder.build();

            // Check limits
            int newDocumentCount =
                    enforceLimitConfigLocked(
                            packageName, finalDocument.getUri(), finalDocument.getSerializedSize());

            // Insert document
            mLogUtil.piiTrace("putDocument, request", finalDocument.getUri(), finalDocument);
            PutResultProto putResultProto = mIcingSearchEngineLocked.put(finalDocument);
            mLogUtil.piiTrace("putDocument, response", putResultProto.getStatus(), putResultProto);

            // Update caches
            addToMap(mNamespaceMapLocked, prefix, finalDocument.getNamespace());
            mDocumentCountMapLocked.put(packageName, newDocumentCount);

            // Logging stats
            if (pStatsBuilder != null) {
                pStatsBuilder
                        .setStatusCode(statusProtoToResultCode(putResultProto.getStatus()))
                        .setGenerateDocumentProtoLatencyMillis(
                                (int)
                                        (generateDocumentProtoEndTimeMillis
                                                - generateDocumentProtoStartTimeMillis))
                        .setRewriteDocumentTypesLatencyMillis(
                                (int)
                                        (rewriteDocumentTypeEndTimeMillis
                                                - rewriteDocumentTypeStartTimeMillis));
                AppSearchLoggerHelper.copyNativeStats(
                        putResultProto.getPutDocumentStats(), pStatsBuilder);
            }

            checkSuccess(putResultProto.getStatus());

            // Prepare notifications
            mObserverManager.onDocumentChange(
                    packageName, databaseName, document.getNamespace(), document.getSchemaType());
        } finally {
            mReadWriteLock.writeLock().unlock();

            if (logger != null) {
                long totalEndTimeMillis = SystemClock.elapsedRealtime();
                pStatsBuilder.setTotalLatencyMillis(
                        (int) (totalEndTimeMillis - totalStartTimeMillis));
                logger.logStats(pStatsBuilder.build());
            }
        }
    }

    /**
     * Checks that a new document can be added to the given packageName with the given serialized
     * size without violating our {@link LimitConfig}.
     *
     * @return the new count of documents for the given package, including the new document.
     * @throws AppSearchException with a code of {@link AppSearchResult#RESULT_OUT_OF_SPACE} if the
     *     limits are violated by the new document.
     */
    @GuardedBy("mReadWriteLock")
    private int enforceLimitConfigLocked(String packageName, String newDocUri, int newDocSize)
            throws AppSearchException {
        // Limits check: size of document
        if (newDocSize > mLimitConfig.getMaxDocumentSizeBytes()) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_OUT_OF_SPACE,
                    "Document \""
                            + newDocUri
                            + "\" for package \""
                            + packageName
                            + "\" serialized to "
                            + newDocSize
                            + " bytes, which exceeds "
                            + "limit of "
                            + mLimitConfig.getMaxDocumentSizeBytes()
                            + " bytes");
        }

        // Limits check: number of documents
        Integer oldDocumentCount = mDocumentCountMapLocked.get(packageName);
        int newDocumentCount;
        if (oldDocumentCount == null) {
            newDocumentCount = 1;
        } else {
            newDocumentCount = oldDocumentCount + 1;
        }
        if (newDocumentCount > mLimitConfig.getMaxDocumentCount()) {
            // Our management of mDocumentCountMapLocked doesn't account for document
            // replacements, so our counter might have overcounted if the app has replaced docs.
            // Rebuild the counter from StorageInfo in case this is so.
            // TODO(b/170371356):  If Icing lib exposes something in the result which says
            //  whether the document was a replacement, we could subtract 1 again after the put
            //  to keep the count accurate. That would allow us to remove this code.
            rebuildDocumentCountMapLocked(getRawStorageInfoProto());
            oldDocumentCount = mDocumentCountMapLocked.get(packageName);
            if (oldDocumentCount == null) {
                newDocumentCount = 1;
            } else {
                newDocumentCount = oldDocumentCount + 1;
            }
        }
        if (newDocumentCount > mLimitConfig.getMaxDocumentCount()) {
            // Now we really can't fit it in, even accounting for replacements.
            throw new AppSearchException(
                    AppSearchResult.RESULT_OUT_OF_SPACE,
                    "Package \""
                            + packageName
                            + "\" exceeded limit of "
                            + mLimitConfig.getMaxDocumentCount()
                            + " documents. Some documents "
                            + "must be removed to index additional ones.");
        }

        return newDocumentCount;
    }

    /**
     * Retrieves a document from the AppSearch index by namespace and document ID from any
     * application the caller is allowed to view
     *
     * <p>This method will handle both Icing engine errors as well as permission errors by throwing
     * an obfuscated RESULT_NOT_FOUND exception. This is done so the caller doesn't receive
     * information on whether or not a file they are not allowed to access exists or not. This is
     * different from the behavior of {@link #getDocument}.
     *
     * @param packageName The package that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param id The ID of the document to get.
     * @param typePropertyPaths A map of schema type to a list of property paths to return in the
     *     result.
     * @param callerUid The ID of the caller application
     * @param callerHasSystemAccess A boolean signifying if the caller has system access
     * @return The Document contents
     * @throws AppSearchException on IcingSearchEngine error or invalid permissions
     */
    @Nullable
    public GenericDocument globalGetDocument(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String id,
            @NonNull Map<String, List<String>> typePropertyPaths,
            int callerUid,
            boolean callerHasSystemAccess)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            // We retrieve the document before checking for access, as we do not know which
            // schema the document is under. Schema is required for checking access
            DocumentProto documentProto;
            try {
                documentProto =
                        getDocumentProtoByIdLocked(
                                packageName, databaseName, namespace, id, typePropertyPaths);

                if (mVisibilityCheckerLocked == null
                        || !mVisibilityCheckerLocked.isSchemaSearchableByCaller(
                                packageName,
                                documentProto.getSchema(),
                                callerUid,
                                callerHasSystemAccess,
                                mVisibilityStoreLocked)) {
                    throw new AppSearchException(AppSearchResult.RESULT_NOT_FOUND);
                }
            } catch (AppSearchException e) {
                throw new AppSearchException(
                        AppSearchResult.RESULT_NOT_FOUND,
                        "Document (" + namespace + ", " + id + ") not found.");
            }

            DocumentProto.Builder documentBuilder = documentProto.toBuilder();
            removePrefixesFromDocument(documentBuilder);
            String prefix = createPrefix(packageName, databaseName);
            Map<String, SchemaTypeConfigProto> schemaTypeMap = mSchemaMapLocked.get(prefix);
            return GenericDocumentToProtoConverter.toGenericDocument(
                    documentBuilder.build(), prefix, schemaTypeMap);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieves a document from the AppSearch index by namespace and document ID.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName The package that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param id The ID of the document to get.
     * @param typePropertyPaths A map of schema type to a list of property paths to return in the
     *     result.
     * @return The Document contents
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public GenericDocument getDocument(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String id,
            @NonNull Map<String, List<String>> typePropertyPaths)
            throws AppSearchException {

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            DocumentProto documentProto =
                    getDocumentProtoByIdLocked(
                            packageName, databaseName, namespace, id, typePropertyPaths);
            DocumentProto.Builder documentBuilder = documentProto.toBuilder();
            removePrefixesFromDocument(documentBuilder);

            String prefix = createPrefix(packageName, databaseName);
            // The schema type map cannot be null at this point. It could only be null if no
            // schema had ever been set for that prefix. Given we have retrieved a document from
            // the index, we know a schema had to have been set.
            Map<String, SchemaTypeConfigProto> schemaTypeMap = mSchemaMapLocked.get(prefix);
            return GenericDocumentToProtoConverter.toGenericDocument(
                    documentBuilder.build(), prefix, schemaTypeMap);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Returns a DocumentProto from Icing.
     *
     * @param packageName The package that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param id The ID of the document to get.
     * @param typePropertyPaths A map of schema type to a list of property paths to return in the
     *     result.
     * @return the DocumentProto object
     * @throws AppSearchException on IcingSearchEngine error
     */
    @NonNull
    @GuardedBy("mReadWriteLock")
    private DocumentProto getDocumentProtoByIdLocked(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String id,
            @NonNull Map<String, List<String>> typePropertyPaths)
            throws AppSearchException {
        String prefix = createPrefix(packageName, databaseName);
        List<TypePropertyMask.Builder> nonPrefixedPropertyMaskBuilders =
                TypePropertyPathToProtoConverter.toTypePropertyMaskBuilderList(typePropertyPaths);
        List<TypePropertyMask> prefixedPropertyMasks =
                new ArrayList<>(nonPrefixedPropertyMaskBuilders.size());
        for (int i = 0; i < nonPrefixedPropertyMaskBuilders.size(); ++i) {
            String nonPrefixedType = nonPrefixedPropertyMaskBuilders.get(i).getSchemaType();
            String prefixedType =
                    nonPrefixedType.equals(GetByDocumentIdRequest.PROJECTION_SCHEMA_TYPE_WILDCARD)
                            ? nonPrefixedType
                            : prefix + nonPrefixedType;
            prefixedPropertyMasks.add(
                    nonPrefixedPropertyMaskBuilders.get(i).setSchemaType(prefixedType).build());
        }
        GetResultSpecProto getResultSpec =
                GetResultSpecProto.newBuilder()
                        .addAllTypePropertyMasks(prefixedPropertyMasks)
                        .build();

        String finalNamespace = createPrefix(packageName, databaseName) + namespace;
        if (mLogUtil.isPiiTraceEnabled()) {
            mLogUtil.piiTrace(
                    "getDocument, request", finalNamespace + ", " + id + "," + getResultSpec);
        }
        GetResultProto getResultProto =
                mIcingSearchEngineLocked.get(finalNamespace, id, getResultSpec);
        mLogUtil.piiTrace("getDocument, response", getResultProto.getStatus(), getResultProto);
        checkSuccess(getResultProto.getStatus());

        return getResultProto.getDocument();
    }

    /**
     * Executes a query against the AppSearch index and returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName The package name that is performing the query.
     * @param databaseName The databaseName this query for.
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @param logger logger to collect query stats
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage query(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchStats.Builder sStatsBuilder = null;
        if (logger != null) {
            sStatsBuilder =
                    new SearchStats.Builder(SearchStats.VISIBILITY_SCOPE_LOCAL, packageName)
                            .setDatabase(databaseName);
        }

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            List<String> filterPackageNames = searchSpec.getFilterPackageNames();
            if (!filterPackageNames.isEmpty() && !filterPackageNames.contains(packageName)) {
                // Client wanted to query over some packages that weren't its own. This isn't
                // allowed through local query so we can return early with no results.
                if (logger != null) {
                    sStatsBuilder.setStatusCode(AppSearchResult.RESULT_SECURITY_ERROR);
                }
                return new SearchResultPage(Bundle.EMPTY);
            }

            String prefix = createPrefix(packageName, databaseName);
            SearchSpecToProtoConverter searchSpecToProtoConverter =
                    new SearchSpecToProtoConverter(
                            searchSpec,
                            Collections.singleton(prefix),
                            mNamespaceMapLocked,
                            mSchemaMapLocked);
            if (searchSpecToProtoConverter.isNothingToSearch()) {
                // there is nothing to search over given their search filters, so we can return an
                // empty SearchResult and skip sending request to Icing.
                return new SearchResultPage(Bundle.EMPTY);
            }

            SearchResultPage searchResultPage =
                    doQueryLocked(queryExpression, searchSpecToProtoConverter, sStatsBuilder);
            addNextPageToken(packageName, searchResultPage.getNextPageToken());
            return searchResultPage;
        } finally {
            mReadWriteLock.readLock().unlock();
            if (logger != null) {
                sStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis));
                logger.logStats(sStatsBuilder.build());
            }
        }
    }

    /**
     * Executes a global query, i.e. over all permitted prefixes, against the AppSearch index and
     * returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @param callerPackageName Package name of the caller, should belong to the {@code
     *     callerUserHandle}.
     * @param callerUid UID of the client making the globalQuery call.
     * @param callerHasSystemAccess Whether the caller has been positively identified as having
     *     access to schemas marked system surfaceable.
     * @param logger logger to collect globalQuery stats
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage globalQuery(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull String callerPackageName,
            int callerUid,
            boolean callerHasSystemAccess,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchStats.Builder sStatsBuilder = null;
        if (logger != null) {
            sStatsBuilder =
                    new SearchStats.Builder(SearchStats.VISIBILITY_SCOPE_GLOBAL, callerPackageName);
        }

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            // Convert package filters to prefix filters
            Set<String> packageFilters = new ArraySet<>(searchSpec.getFilterPackageNames());
            Set<String> prefixFilters = new ArraySet<>();
            if (packageFilters.isEmpty()) {
                // Client didn't restrict their search over packages. Try to query over all
                // packages/prefixes
                prefixFilters = mNamespaceMapLocked.keySet();
            } else {
                // Client did restrict their search over packages. Only include the prefixes that
                // belong to the specified packages.
                for (String prefix : mNamespaceMapLocked.keySet()) {
                    String packageName = getPackageName(prefix);
                    if (packageFilters.contains(packageName)) {
                        prefixFilters.add(prefix);
                    }
                }
            }
            SearchSpecToProtoConverter searchSpecToProtoConverter =
                    new SearchSpecToProtoConverter(
                            searchSpec, prefixFilters, mNamespaceMapLocked, mSchemaMapLocked);
            // Remove those inaccessible schemas.
            searchSpecToProtoConverter.removeInaccessibleSchemaFilter(
                    callerPackageName,
                    callerUid,
                    callerHasSystemAccess,
                    mVisibilityStoreLocked,
                    mVisibilityCheckerLocked);
            if (searchSpecToProtoConverter.isNothingToSearch()) {
                // there is nothing to search over given their search filters, so we can return an
                // empty SearchResult and skip sending request to Icing.
                return new SearchResultPage(Bundle.EMPTY);
            }
            SearchResultPage searchResultPage =
                    doQueryLocked(queryExpression, searchSpecToProtoConverter, sStatsBuilder);
            addNextPageToken(callerPackageName, searchResultPage.getNextPageToken());
            return searchResultPage;
        } finally {
            mReadWriteLock.readLock().unlock();

            if (logger != null) {
                sStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis));
                logger.logStats(sStatsBuilder.build());
            }
        }
    }

    @GuardedBy("mReadWriteLock")
    private SearchResultPage doQueryLocked(
            @NonNull String queryExpression,
            @NonNull SearchSpecToProtoConverter searchSpecToProtoConverter,
            @Nullable SearchStats.Builder sStatsBuilder)
            throws AppSearchException {
        // Rewrite the given SearchSpec into SearchSpecProto, ResultSpecProto and ScoringSpecProto.
        // All processes are counted in rewriteSearchSpecLatencyMillis
        long rewriteSearchSpecLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchSpecProto finalSearchSpec =
                searchSpecToProtoConverter.toSearchSpecProto(queryExpression);
        ResultSpecProto finalResultSpec =
                searchSpecToProtoConverter.toResultSpecProto(mNamespaceMapLocked);
        ScoringSpecProto scoringSpec = searchSpecToProtoConverter.toScoringSpecProto();
        if (sStatsBuilder != null) {
            sStatsBuilder.setRewriteSearchSpecLatencyMillis(
                    (int) (SystemClock.elapsedRealtime() - rewriteSearchSpecLatencyStartMillis));
        }

        // Send request to Icing.
        SearchResultProto searchResultProto =
                searchInIcingLocked(finalSearchSpec, finalResultSpec, scoringSpec, sStatsBuilder);

        long rewriteSearchResultLatencyStartMillis = SystemClock.elapsedRealtime();
        // Rewrite search result before we return.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto, mSchemaMapLocked);
        if (sStatsBuilder != null) {
            sStatsBuilder.setRewriteSearchResultLatencyMillis(
                    (int) (SystemClock.elapsedRealtime() - rewriteSearchResultLatencyStartMillis));
        }
        return searchResultPage;
    }

    @GuardedBy("mReadWriteLock")
    private SearchResultProto searchInIcingLocked(
            @NonNull SearchSpecProto searchSpec,
            @NonNull ResultSpecProto resultSpec,
            @NonNull ScoringSpecProto scoringSpec,
            @Nullable SearchStats.Builder sStatsBuilder)
            throws AppSearchException {
        if (mLogUtil.isPiiTraceEnabled()) {
            mLogUtil.piiTrace(
                    "search, request",
                    searchSpec.getQuery(),
                    searchSpec + ", " + scoringSpec + ", " + resultSpec);
        }
        SearchResultProto searchResultProto =
                mIcingSearchEngineLocked.search(searchSpec, scoringSpec, resultSpec);
        mLogUtil.piiTrace(
                "search, response", searchResultProto.getResultsCount(), searchResultProto);
        if (sStatsBuilder != null) {
            sStatsBuilder.setStatusCode(statusProtoToResultCode(searchResultProto.getStatus()));
            AppSearchLoggerHelper.copyNativeStats(searchResultProto.getQueryStats(), sStatsBuilder);
        }
        checkSuccess(searchResultProto.getStatus());
        return searchResultProto;
    }

    /**
     * Returns a mapping of package names to all the databases owned by that package.
     *
     * <p>This method is inefficient to call repeatedly.
     */
    @NonNull
    public Map<String, Set<String>> getPackageToDatabases() {
        mReadWriteLock.readLock().lock();
        try {
            Map<String, Set<String>> packageToDatabases = new ArrayMap<>();
            for (String prefix : mSchemaMapLocked.keySet()) {
                String packageName = getPackageName(prefix);

                Set<String> databases = packageToDatabases.get(packageName);
                if (databases == null) {
                    databases = new ArraySet<>();
                    packageToDatabases.put(packageName, databases);
                }

                String databaseName = getDatabaseName(prefix);
                databases.add(databaseName);
            }

            return packageToDatabases;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Fetches the next page of results of a previously executed query. Results can be empty if
     * next-page token is invalid or all pages have been returned.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName Package name of the caller.
     * @param nextPageToken The token of pre-loaded results of previously executed query.
     * @return The next page of results of previously executed query.
     * @throws AppSearchException on IcingSearchEngine error or if can't advance on nextPageToken.
     */
    @NonNull
    public SearchResultPage getNextPage(
            @NonNull String packageName,
            long nextPageToken,
            @Nullable SearchStats.Builder statsBuilder)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            mLogUtil.piiTrace("getNextPage, request", nextPageToken);
            checkNextPageToken(packageName, nextPageToken);
            SearchResultProto searchResultProto =
                    mIcingSearchEngineLocked.getNextPage(nextPageToken);

            if (statsBuilder != null) {
                statsBuilder.setStatusCode(statusProtoToResultCode(searchResultProto.getStatus()));
                AppSearchLoggerHelper.copyNativeStats(
                        searchResultProto.getQueryStats(), statsBuilder);
            }

            mLogUtil.piiTrace(
                    "getNextPage, response",
                    searchResultProto.getResultsCount(),
                    searchResultProto);
            checkSuccess(searchResultProto.getStatus());
            if (nextPageToken != EMPTY_PAGE_TOKEN
                    && searchResultProto.getNextPageToken() == EMPTY_PAGE_TOKEN) {
                // At this point, we're guaranteed that this nextPageToken exists for this package,
                // otherwise checkNextPageToken would've thrown an exception.
                // Since the new token is 0, this is the last page. We should remove the old token
                // from our cache since it no longer refers to this query.
                synchronized (mNextPageTokensLocked) {
                    mNextPageTokensLocked.get(packageName).remove(nextPageToken);
                }
            }
            long rewriteSearchResultLatencyStartMillis = SystemClock.elapsedRealtime();
            // Rewrite search result before we return.
            SearchResultPage searchResultPage =
                    SearchResultToProtoConverter.toSearchResultPage(
                            searchResultProto, mSchemaMapLocked);
            if (statsBuilder != null) {
                statsBuilder.setRewriteSearchResultLatencyMillis(
                        (int)
                                (SystemClock.elapsedRealtime()
                                        - rewriteSearchResultLatencyStartMillis));
            }
            return searchResultPage;
        } finally {
            mReadWriteLock.readLock().unlock();
            if (statsBuilder != null) {
                statsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis));
            }
        }
    }

    /**
     * Invalidates the next-page token so that no more results of the related query can be returned.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName Package name of the caller.
     * @param nextPageToken The token of pre-loaded results of previously executed query to be
     *     Invalidated.
     * @throws AppSearchException if nextPageToken is unusable.
     */
    public void invalidateNextPageToken(@NonNull String packageName, long nextPageToken)
            throws AppSearchException {
        if (nextPageToken == EMPTY_PAGE_TOKEN) {
            // (b/208305352) Directly return here since we are no longer caching EMPTY_PAGE_TOKEN
            // in the cached token set. So no need to remove it anymore.
            return;
        }

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            mLogUtil.piiTrace("invalidateNextPageToken, request", nextPageToken);
            checkNextPageToken(packageName, nextPageToken);
            mIcingSearchEngineLocked.invalidateNextPageToken(nextPageToken);

            synchronized (mNextPageTokensLocked) {
                Set<Long> tokens = mNextPageTokensLocked.get(packageName);
                if (tokens != null) {
                    tokens.remove(nextPageToken);
                } else {
                    Log.wtf(
                            TAG,
                            "Failed to invalidate token "
                                    + nextPageToken
                                    + ": tokens are not "
                                    + "cached.");
                }
            }
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /** Reports a usage of the given document at the given timestamp. */
    public void reportUsage(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String documentId,
            long usageTimestampMillis,
            boolean systemUsage)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            String prefixedNamespace = createPrefix(packageName, databaseName) + namespace;
            UsageReport.UsageType usageType =
                    systemUsage
                            ? UsageReport.UsageType.USAGE_TYPE2
                            : UsageReport.UsageType.USAGE_TYPE1;
            UsageReport report =
                    UsageReport.newBuilder()
                            .setDocumentNamespace(prefixedNamespace)
                            .setDocumentUri(documentId)
                            .setUsageTimestampMs(usageTimestampMillis)
                            .setUsageType(usageType)
                            .build();

            mLogUtil.piiTrace("reportUsage, request", report.getDocumentUri(), report);
            ReportUsageResultProto result = mIcingSearchEngineLocked.reportUsage(report);
            mLogUtil.piiTrace("reportUsage, response", result.getStatus(), result);
            checkSuccess(result.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Removes the given document by id.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the document.
     * @param databaseName The databaseName the document is in.
     * @param namespace Namespace of the document to remove.
     * @param documentId ID of the document to remove.
     * @param removeStatsBuilder builder for {@link RemoveStats} to hold stats for remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void remove(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String documentId,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            String prefixedNamespace = createPrefix(packageName, databaseName) + namespace;
            String schemaType = null;
            if (mObserverManager.isPackageObserved(packageName)) {
                // Someone might be observing the type this document is under, but we have no way to
                // know its type without retrieving it. Do so now.
                // TODO(b/193494000): If Icing Lib can return information about the deleted
                //  document's type we can remove this code.
                if (mLogUtil.isPiiTraceEnabled()) {
                    mLogUtil.piiTrace(
                            "removeById, getRequest", prefixedNamespace + ", " + documentId);
                }
                GetResultProto getResult =
                        mIcingSearchEngineLocked.get(
                                prefixedNamespace, documentId, GET_RESULT_SPEC_NO_PROPERTIES);
                mLogUtil.piiTrace("removeById, getResponse", getResult.getStatus(), getResult);
                checkSuccess(getResult.getStatus());
                schemaType = PrefixUtil.removePrefix(getResult.getDocument().getSchema());
            }

            if (mLogUtil.isPiiTraceEnabled()) {
                mLogUtil.piiTrace("removeById, request", prefixedNamespace + ", " + documentId);
            }
            DeleteResultProto deleteResultProto =
                    mIcingSearchEngineLocked.delete(prefixedNamespace, documentId);
            mLogUtil.piiTrace(
                    "removeById, response", deleteResultProto.getStatus(), deleteResultProto);

            if (removeStatsBuilder != null) {
                removeStatsBuilder.setStatusCode(
                        statusProtoToResultCode(deleteResultProto.getStatus()));
                AppSearchLoggerHelper.copyNativeStats(
                        deleteResultProto.getDeleteStats(), removeStatsBuilder);
            }
            checkSuccess(deleteResultProto.getStatus());

            // Update derived maps
            updateDocumentCountAfterRemovalLocked(packageName, /*numDocumentsDeleted=*/ 1);

            // Prepare notifications
            if (schemaType != null) {
                mObserverManager.onDocumentChange(packageName, databaseName, namespace, schemaType);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
            if (removeStatsBuilder != null) {
                removeStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis));
            }
        }
    }

    /**
     * Removes documents by given query.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the documents.
     * @param databaseName The databaseName the document is in.
     * @param queryExpression Query String to search.
     * @param searchSpec Defines what and how to remove
     * @param removeStatsBuilder builder for {@link RemoveStats} to hold stats for remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByQuery(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            List<String> filterPackageNames = searchSpec.getFilterPackageNames();
            if (!filterPackageNames.isEmpty() && !filterPackageNames.contains(packageName)) {
                // We're only removing documents within the parameter `packageName`. If we're not
                // restricting our remove-query to this package name, then there's nothing for us to
                // remove.
                return;
            }

            String prefix = createPrefix(packageName, databaseName);
            if (!mNamespaceMapLocked.containsKey(prefix)) {
                // The target database is empty so we can return early and skip sending request to
                // Icing.
                return;
            }

            SearchSpecToProtoConverter searchSpecToProtoConverter =
                    new SearchSpecToProtoConverter(
                            searchSpec,
                            Collections.singleton(prefix),
                            mNamespaceMapLocked,
                            mSchemaMapLocked);
            if (searchSpecToProtoConverter.isNothingToSearch()) {
                // there is nothing to search over given their search filters, so we can return
                // early and skip sending request to Icing.
                return;
            }

            SearchSpecProto finalSearchSpec =
                    searchSpecToProtoConverter.toSearchSpecProto(queryExpression);

            Set<String> prefixedObservedSchemas = null;
            if (mObserverManager.isPackageObserved(packageName)) {
                prefixedObservedSchemas = new ArraySet<>();
                List<String> prefixedTargetSchemaTypes = finalSearchSpec.getSchemaTypeFiltersList();
                for (int i = 0; i < prefixedTargetSchemaTypes.size(); i++) {
                    String prefixedType = prefixedTargetSchemaTypes.get(i);
                    String shortTypeName = PrefixUtil.removePrefix(prefixedType);
                    if (mObserverManager.isSchemaTypeObserved(packageName, shortTypeName)) {
                        prefixedObservedSchemas.add(prefixedType);
                    }
                }
            }

            if (prefixedObservedSchemas != null && !prefixedObservedSchemas.isEmpty()) {
                doRemoveByQueryWithChangeNotificationLocked(
                        packageName, finalSearchSpec, prefixedObservedSchemas, removeStatsBuilder);
            } else {
                doRemoveByQueryNoChangeNotificationLocked(
                        packageName, finalSearchSpec, removeStatsBuilder);
            }

        } finally {
            mReadWriteLock.writeLock().unlock();
            if (removeStatsBuilder != null) {
                removeStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis));
            }
        }
    }

    /**
     * Executes removeByQuery, creating change notifications for removal.
     *
     * @param packageName The package name that owns the documents.
     * @param finalSearchSpec The final search spec that has been written through {@link
     *     SearchSpecToProtoConverter}.
     * @param prefixedObservedSchemas The set of prefixed schemas that have valid registered
     *     observers. Only changes to schemas in this set will be queued.
     */
    // TODO(b/193494000): Have Icing Lib return the URIs and types that were actually
    //  deleted instead of querying in two passes like this.
    @GuardedBy("mReadWriteLock")
    private void doRemoveByQueryWithChangeNotificationLocked(
            @NonNull String packageName,
            @NonNull SearchSpecProto finalSearchSpec,
            @NonNull Set<String> prefixedObservedSchemas,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        mLogUtil.piiTrace("removeByQuery.withChangeNotification, query request", finalSearchSpec);
        SearchResultProto searchResultProto =
                mIcingSearchEngineLocked.search(
                        finalSearchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        RESULT_SPEC_NO_PROPERTIES);
        mLogUtil.piiTrace(
                "removeByQuery.withChangeNotification, query response",
                searchResultProto.getStatus(),
                searchResultProto);

        // TODO(b/187206766) also log query stats here once it's added to RemoveStats.Builder
        checkSuccess(searchResultProto.getStatus());

        long nextPageToken = searchResultProto.getNextPageToken();
        int numDocumentsDeleted = 0;
        while (true) {
            for (int i = 0; i < searchResultProto.getResultsCount(); i++) {
                DocumentProto document = searchResultProto.getResults(i).getDocument();

                if (mLogUtil.isPiiTraceEnabled()) {
                    mLogUtil.piiTrace(
                            "removeByQuery.withChangeNotification, removeById request",
                            document.getNamespace() + ", " + document.getUri());
                }
                DeleteResultProto deleteResultProto =
                        mIcingSearchEngineLocked.delete(document.getNamespace(), document.getUri());
                mLogUtil.piiTrace(
                        "removeByQuery.withChangeNotification, removeById response",
                        deleteResultProto.getStatus(),
                        deleteResultProto);

                if (removeStatsBuilder != null) {
                    removeStatsBuilder.setStatusCode(
                            statusProtoToResultCode(deleteResultProto.getStatus()));
                    // TODO(b/187206766): This will keep overwriting the remove stats. This whole
                    //  method should be replaced with native handling within icinglib for returning
                    //  namespaces, types and URIs. That should populate the same proto as the
                    //  non-observed case and remove the need for this hacky implementation and log.
                    AppSearchLoggerHelper.copyNativeStats(
                            deleteResultProto.getDeleteStats(), removeStatsBuilder);
                }

                // It shouldn't be possible for this to fail; we have the write lock!
                checkSuccess(deleteResultProto.getStatus());
                numDocumentsDeleted++;

                // Prepare change notifications
                if (prefixedObservedSchemas.contains(document.getSchema())) {
                    mObserverManager.onDocumentChange(
                            packageName,
                            /*databaseName=*/ PrefixUtil.getDatabaseName(document.getNamespace()),
                            /*namespace=*/ PrefixUtil.removePrefix(document.getNamespace()),
                            /*schemaType=*/ PrefixUtil.removePrefix(document.getSchema()));
                }
            }

            // Fetch next page
            if (nextPageToken == EMPTY_PAGE_TOKEN) {
                break;
            }
            mLogUtil.piiTrace(
                    "removeByQuery.withChangeNotification, getNextPage request", nextPageToken);
            searchResultProto = mIcingSearchEngineLocked.getNextPage(nextPageToken);
            mLogUtil.piiTrace(
                    "removeByQuery.withChangeNotification, getNextPage response",
                    searchResultProto.getResultsCount(),
                    searchResultProto);

            // TODO(b/187206766) also log query stats here once it's added to RemoveStats.Builder
            checkSuccess(searchResultProto.getStatus());

            nextPageToken = searchResultProto.getNextPageToken();
        }

        // Update derived maps
        updateDocumentCountAfterRemovalLocked(packageName, numDocumentsDeleted);
    }

    /**
     * Executes removeByQuery without dispatching any change notifications.
     *
     * <p>This is faster than {@link #doRemoveByQueryWithChangeNotificationLocked}.
     *
     * @param packageName The package name that owns the documents.
     * @param rewrittenSearchSpec A search spec that has been run through {@link
     *     #rewriteSearchSpecForPrefixesLocked}.
     */
    @GuardedBy("mReadWriteLock")
    private void doRemoveByQueryNoChangeNotificationLocked(
            @NonNull String packageName,
            @NonNull SearchSpecProto rewrittenSearchSpec,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        mLogUtil.piiTrace("removeByQuery, request", rewrittenSearchSpec);
        DeleteByQueryResultProto deleteResultProto =
                mIcingSearchEngineLocked.deleteByQuery(rewrittenSearchSpec);
        mLogUtil.piiTrace(
                "removeByQuery, response", deleteResultProto.getStatus(), deleteResultProto);

        if (removeStatsBuilder != null) {
            removeStatsBuilder.setStatusCode(
                    statusProtoToResultCode(deleteResultProto.getStatus()));
            // TODO(b/187206766) also log query stats here once IcingLib returns it
            AppSearchLoggerHelper.copyNativeStats(
                    deleteResultProto.getDeleteByQueryStats(), removeStatsBuilder);
        }

        // It seems that the caller wants to get success if the data matching the query is
        // not in the DB because it was not there or was successfully deleted.
        checkCodeOneOf(
                deleteResultProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);

        // Update derived maps
        int numDocumentsDeleted =
                deleteResultProto.getDeleteByQueryStats().getNumDocumentsDeleted();
        updateDocumentCountAfterRemovalLocked(packageName, numDocumentsDeleted);
    }

    @GuardedBy("mReadWriteLock")
    private void updateDocumentCountAfterRemovalLocked(
            @NonNull String packageName, int numDocumentsDeleted) {
        if (numDocumentsDeleted > 0) {
            Integer oldDocumentCount = mDocumentCountMapLocked.get(packageName);
            // This should always be true: how can we delete documents for a package without
            // having seen that package during init? This is just a safeguard.
            if (oldDocumentCount != null) {
                // This should always be >0; how can we remove more documents than we've indexed?
                // This is just a safeguard.
                int newDocumentCount = Math.max(oldDocumentCount - numDocumentsDeleted, 0);
                mDocumentCountMapLocked.put(packageName, newDocumentCount);
            }
        }
    }

    /** Estimates the storage usage info for a specific package. */
    @NonNull
    public StorageInfo getStorageInfoForPackage(@NonNull String packageName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            Set<String> databases = packageToDatabases.get(packageName);
            if (databases == null) {
                // Package doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }

            // Accumulate all the namespaces we're interested in.
            Set<String> wantedPrefixedNamespaces = new ArraySet<>();
            for (String database : databases) {
                Set<String> prefixedNamespaces =
                        mNamespaceMapLocked.get(createPrefix(packageName, database));
                if (prefixedNamespaces != null) {
                    wantedPrefixedNamespaces.addAll(prefixedNamespaces);
                }
            }
            if (wantedPrefixedNamespaces.isEmpty()) {
                return new StorageInfo.Builder().build();
            }

            return getStorageInfoForNamespaces(getRawStorageInfoProto(), wantedPrefixedNamespaces);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /** Estimates the storage usage info for a specific database in a package. */
    @NonNull
    public StorageInfo getStorageInfoForDatabase(
            @NonNull String packageName, @NonNull String databaseName) throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            Set<String> databases = packageToDatabases.get(packageName);
            if (databases == null) {
                // Package doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }
            if (!databases.contains(databaseName)) {
                // Database doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }

            Set<String> wantedPrefixedNamespaces =
                    mNamespaceMapLocked.get(createPrefix(packageName, databaseName));
            if (wantedPrefixedNamespaces == null || wantedPrefixedNamespaces.isEmpty()) {
                return new StorageInfo.Builder().build();
            }

            return getStorageInfoForNamespaces(getRawStorageInfoProto(), wantedPrefixedNamespaces);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Returns the native storage info capsuled in {@link StorageInfoResultProto} directly from
     * IcingSearchEngine.
     */
    @NonNull
    public StorageInfoProto getRawStorageInfoProto() throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            mLogUtil.piiTrace("getStorageInfo, request");
            StorageInfoResultProto storageInfoResult = mIcingSearchEngineLocked.getStorageInfo();
            mLogUtil.piiTrace(
                    "getStorageInfo, response", storageInfoResult.getStatus(), storageInfoResult);
            checkSuccess(storageInfoResult.getStatus());
            return storageInfoResult.getStorageInfo();
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Extracts and returns {@link StorageInfo} from {@link StorageInfoProto} based on prefixed
     * namespaces.
     */
    @NonNull
    private static StorageInfo getStorageInfoForNamespaces(
            @NonNull StorageInfoProto storageInfoProto, @NonNull Set<String> prefixedNamespaces) {
        if (!storageInfoProto.hasDocumentStorageInfo()) {
            return new StorageInfo.Builder().build();
        }

        long totalStorageSize = storageInfoProto.getTotalStorageSize();
        DocumentStorageInfoProto documentStorageInfo = storageInfoProto.getDocumentStorageInfo();
        int totalDocuments =
                documentStorageInfo.getNumAliveDocuments()
                        + documentStorageInfo.getNumExpiredDocuments();

        if (totalStorageSize == 0 || totalDocuments == 0) {
            // Maybe we can exit early and also avoid a divide by 0 error.
            return new StorageInfo.Builder().build();
        }

        // Accumulate stats across the package's namespaces.
        int aliveDocuments = 0;
        int expiredDocuments = 0;
        int aliveNamespaces = 0;
        List<NamespaceStorageInfoProto> namespaceStorageInfos =
                documentStorageInfo.getNamespaceStorageInfoList();
        for (int i = 0; i < namespaceStorageInfos.size(); i++) {
            NamespaceStorageInfoProto namespaceStorageInfo = namespaceStorageInfos.get(i);
            // The namespace from icing lib is already the prefixed format
            if (prefixedNamespaces.contains(namespaceStorageInfo.getNamespace())) {
                if (namespaceStorageInfo.getNumAliveDocuments() > 0) {
                    aliveNamespaces++;
                    aliveDocuments += namespaceStorageInfo.getNumAliveDocuments();
                }
                expiredDocuments += namespaceStorageInfo.getNumExpiredDocuments();
            }
        }
        int namespaceDocuments = aliveDocuments + expiredDocuments;

        // Since we don't have the exact size of all the documents, we do an estimation. Note
        // that while the total storage takes into account schema, index, etc. in addition to
        // documents, we'll only calculate the percentage based on number of documents a
        // client has.
        return new StorageInfo.Builder()
                .setSizeBytes((long) (namespaceDocuments * 1.0 / totalDocuments * totalStorageSize))
                .setAliveDocumentsCount(aliveDocuments)
                .setAliveNamespacesCount(aliveNamespaces)
                .build();
    }

    /**
     * Persists all update/delete requests to the disk.
     *
     * <p>If the app crashes after a call to PersistToDisk with {@link PersistType.Code#FULL}, Icing
     * would be able to fully recover all data written up to this point without a costly recovery
     * process.
     *
     * <p>If the app crashes after a call to PersistToDisk with {@link PersistType.Code#LITE}, Icing
     * would trigger a costly recovery process in next initialization. After that, Icing would still
     * be able to recover all written data - excepting Usage data. Usage data is only guaranteed to
     * be safe after a call to PersistToDisk with {@link PersistType.Code#FULL}
     *
     * <p>If the app crashes after an update/delete request has been made, but before any call to
     * PersistToDisk, then all data in Icing will be lost.
     *
     * @param persistType the amount of data to persist. {@link PersistType.Code#LITE} will only
     *     persist the minimal amount of data to ensure all data can be recovered. {@link
     *     PersistType.Code#FULL} will persist all data necessary to prevent data loss without
     *     needing data recovery.
     * @throws AppSearchException on any error that AppSearch persist data to disk.
     */
    public void persistToDisk(@NonNull PersistType.Code persistType) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            mLogUtil.piiTrace("persistToDisk, request", persistType);
            PersistToDiskResultProto persistToDiskResultProto =
                    mIcingSearchEngineLocked.persistToDisk(persistType);
            mLogUtil.piiTrace(
                    "persistToDisk, response",
                    persistToDiskResultProto.getStatus(),
                    persistToDiskResultProto);
            checkSuccess(persistToDiskResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Remove all {@link AppSearchSchema}s and {@link GenericDocument}s under the given package.
     *
     * @param packageName The name of package to be removed.
     * @throws AppSearchException if we cannot remove the data.
     */
    public void clearPackageData(@NonNull String packageName) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();
            // TODO(b/193494000): We are calling getPackageToDatabases here and in several other
            //  places within AppSearchImpl. This method is not efficient and does a lot of string
            //  manipulation. We should find a way to cache the package to database map so it can
            //  just be obtained from a local variable instead of being parsed out of the prefixed
            //  map.
            Set<String> existingPackages = getPackageToDatabases().keySet();
            if (existingPackages.contains(packageName)) {
                existingPackages.remove(packageName);
                prunePackageData(existingPackages);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Remove all {@link AppSearchSchema}s and {@link GenericDocument}s that doesn't belong to any
     * of the given installed packages
     *
     * @param installedPackages The name of all installed package.
     * @throws AppSearchException if we cannot remove the data.
     */
    public void prunePackageData(@NonNull Set<String> installedPackages) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();
            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            if (installedPackages.containsAll(packageToDatabases.keySet())) {
                // No package got removed. We are good.
                return;
            }

            // Prune schema proto
            SchemaProto existingSchema = getSchemaProtoLocked();
            SchemaProto.Builder newSchemaBuilder = SchemaProto.newBuilder();
            for (int i = 0; i < existingSchema.getTypesCount(); i++) {
                String packageName = getPackageName(existingSchema.getTypes(i).getSchemaType());
                if (installedPackages.contains(packageName)) {
                    newSchemaBuilder.addTypes(existingSchema.getTypes(i));
                }
            }

            SchemaProto finalSchema = newSchemaBuilder.build();

            // Apply schema, set force override to true to remove all schemas and documents that
            // doesn't belong to any of these installed packages.
            mLogUtil.piiTrace(
                    "clearPackageData.setSchema, request",
                    finalSchema.getTypesCount(),
                    finalSchema);
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(
                            finalSchema, /*ignoreErrorsAndDeleteDocuments=*/ true);
            mLogUtil.piiTrace(
                    "clearPackageData.setSchema, response",
                    setSchemaResultProto.getStatus(),
                    setSchemaResultProto);

            // Determine whether it succeeded.
            checkSuccess(setSchemaResultProto.getStatus());

            // Prune cached maps
            for (Map.Entry<String, Set<String>> entry : packageToDatabases.entrySet()) {
                String packageName = entry.getKey();
                Set<String> databaseNames = entry.getValue();
                if (!installedPackages.contains(packageName) && databaseNames != null) {
                    mDocumentCountMapLocked.remove(packageName);
                    synchronized (mNextPageTokensLocked) {
                        mNextPageTokensLocked.remove(packageName);
                    }
                    for (String databaseName : databaseNames) {
                        String removedPrefix = createPrefix(packageName, databaseName);
                        mSchemaMapLocked.remove(removedPrefix);
                        mNamespaceMapLocked.remove(removedPrefix);
                    }
                }
            }
            // TODO(b/145759910) clear visibility setting for package.
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Clears documents and schema across all packages and databaseNames.
     *
     * <p>This method belongs to mutate group.
     *
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @GuardedBy("mReadWriteLock")
    private void resetLocked(@Nullable InitializeStats.Builder initStatsBuilder)
            throws AppSearchException {
        mLogUtil.piiTrace("icingSearchEngine.reset, request");
        ResetResultProto resetResultProto = mIcingSearchEngineLocked.reset();
        mLogUtil.piiTrace(
                "icingSearchEngine.reset, response",
                resetResultProto.getStatus(),
                resetResultProto);
        mOptimizeIntervalCountLocked = 0;
        mSchemaMapLocked.clear();
        mNamespaceMapLocked.clear();
        mDocumentCountMapLocked.clear();
        synchronized (mNextPageTokensLocked) {
            mNextPageTokensLocked.clear();
        }
        if (initStatsBuilder != null) {
            initStatsBuilder
                    .setHasReset(true)
                    .setResetStatusCode(statusProtoToResultCode(resetResultProto.getStatus()));
        }

        checkSuccess(resetResultProto.getStatus());
    }

    @GuardedBy("mReadWriteLock")
    private void rebuildDocumentCountMapLocked(@NonNull StorageInfoProto storageInfoProto) {
        mDocumentCountMapLocked.clear();
        List<NamespaceStorageInfoProto> namespaceStorageInfoProtoList =
                storageInfoProto.getDocumentStorageInfo().getNamespaceStorageInfoList();
        for (int i = 0; i < namespaceStorageInfoProtoList.size(); i++) {
            NamespaceStorageInfoProto namespaceStorageInfoProto =
                    namespaceStorageInfoProtoList.get(i);
            String packageName = getPackageName(namespaceStorageInfoProto.getNamespace());
            Integer oldCount = mDocumentCountMapLocked.get(packageName);
            int newCount;
            if (oldCount == null) {
                newCount = namespaceStorageInfoProto.getNumAliveDocuments();
            } else {
                newCount = oldCount + namespaceStorageInfoProto.getNumAliveDocuments();
            }
            mDocumentCountMapLocked.put(packageName, newCount);
        }
    }

    /** Wrapper around schema changes */
    @VisibleForTesting
    static class RewrittenSchemaResults {
        // Any prefixed types that used to exist in the schema, but are deleted in the new one.
        final Set<String> mDeletedPrefixedTypes = new ArraySet<>();

        // Map of prefixed schema types to SchemaTypeConfigProtos that were part of the new schema.
        final Map<String, SchemaTypeConfigProto> mRewrittenPrefixedTypes = new ArrayMap<>();
    }

    /**
     * Rewrites all types mentioned in the given {@code newSchema} to prepend {@code prefix}.
     * Rewritten types will be added to the {@code existingSchema}.
     *
     * @param prefix The full prefix to prepend to the schema.
     * @param existingSchema A schema that may contain existing types from across all prefixes. Will
     *     be mutated to contain the properly rewritten schema types from {@code newSchema}.
     * @param newSchema Schema with types to add to the {@code existingSchema}.
     * @return a RewrittenSchemaResults that contains all prefixed schema type names in the given
     *     prefix as well as a set of schema types that were deleted.
     */
    @VisibleForTesting
    static RewrittenSchemaResults rewriteSchema(
            @NonNull String prefix,
            @NonNull SchemaProto.Builder existingSchema,
            @NonNull SchemaProto newSchema)
            throws AppSearchException {
        HashMap<String, SchemaTypeConfigProto> newTypesToProto = new HashMap<>();
        // Rewrite the schema type to include the typePrefix.
        for (int typeIdx = 0; typeIdx < newSchema.getTypesCount(); typeIdx++) {
            SchemaTypeConfigProto.Builder typeConfigBuilder =
                    newSchema.getTypes(typeIdx).toBuilder();

            // Rewrite SchemaProto.types.schema_type
            String newSchemaType = prefix + typeConfigBuilder.getSchemaType();
            typeConfigBuilder.setSchemaType(newSchemaType);

            // Rewrite SchemaProto.types.properties.schema_type
            for (int propertyIdx = 0;
                    propertyIdx < typeConfigBuilder.getPropertiesCount();
                    propertyIdx++) {
                PropertyConfigProto.Builder propertyConfigBuilder =
                        typeConfigBuilder.getProperties(propertyIdx).toBuilder();
                if (!propertyConfigBuilder.getSchemaType().isEmpty()) {
                    String newPropertySchemaType = prefix + propertyConfigBuilder.getSchemaType();
                    propertyConfigBuilder.setSchemaType(newPropertySchemaType);
                    typeConfigBuilder.setProperties(propertyIdx, propertyConfigBuilder);
                }
            }

            newTypesToProto.put(newSchemaType, typeConfigBuilder.build());
        }

        // newTypesToProto is modified below, so we need a copy first
        RewrittenSchemaResults rewrittenSchemaResults = new RewrittenSchemaResults();
        rewrittenSchemaResults.mRewrittenPrefixedTypes.putAll(newTypesToProto);

        // Combine the existing schema (which may have types from other prefixes) with this
        // prefix's new schema. Modifies the existingSchemaBuilder.
        // Check if we need to replace any old schema types with the new ones.
        for (int i = 0; i < existingSchema.getTypesCount(); i++) {
            String schemaType = existingSchema.getTypes(i).getSchemaType();
            SchemaTypeConfigProto newProto = newTypesToProto.remove(schemaType);
            if (newProto != null) {
                // Replacement
                existingSchema.setTypes(i, newProto);
            } else if (prefix.equals(getPrefix(schemaType))) {
                // All types existing before but not in newSchema should be removed.
                existingSchema.removeTypes(i);
                --i;
                rewrittenSchemaResults.mDeletedPrefixedTypes.add(schemaType);
            }
        }
        // We've been removing existing types from newTypesToProto, so everything that remains is
        // new.
        existingSchema.addAllTypes(newTypesToProto.values());

        return rewrittenSchemaResults;
    }

    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    SchemaProto getSchemaProtoLocked() throws AppSearchException {
        mLogUtil.piiTrace("getSchema, request");
        GetSchemaResultProto schemaProto = mIcingSearchEngineLocked.getSchema();
        mLogUtil.piiTrace("getSchema, response", schemaProto.getStatus(), schemaProto);
        // TODO(b/161935693) check GetSchemaResultProto is success or not. Call reset() if it's not.
        // TODO(b/161935693) only allow GetSchemaResultProto NOT_FOUND on first run
        checkCodeOneOf(schemaProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        return schemaProto.getSchema();
    }

    private void addNextPageToken(String packageName, long nextPageToken) {
        if (nextPageToken == EMPTY_PAGE_TOKEN) {
            // There is no more pages. No need to add it.
            return;
        }
        synchronized (mNextPageTokensLocked) {
            Set<Long> tokens = mNextPageTokensLocked.get(packageName);
            if (tokens == null) {
                tokens = new ArraySet<>();
                mNextPageTokensLocked.put(packageName, tokens);
            }
            tokens.add(nextPageToken);
        }
    }

    private void checkNextPageToken(String packageName, long nextPageToken)
            throws AppSearchException {
        if (nextPageToken == EMPTY_PAGE_TOKEN) {
            // Swallow the check for empty page token, token = 0 means there is no more page and it
            // won't return anything from Icing.
            return;
        }
        synchronized (mNextPageTokensLocked) {
            Set<Long> nextPageTokens = mNextPageTokensLocked.get(packageName);
            if (nextPageTokens == null || !nextPageTokens.contains(nextPageToken)) {
                throw new AppSearchException(
                        RESULT_SECURITY_ERROR,
                        "Package \""
                                + packageName
                                + "\" cannot use nextPageToken: "
                                + nextPageToken);
            }
        }
    }

    /**
     * Adds an {@link AppSearchObserverCallback} to monitor changes within the databases owned by
     * {@code observedPackage} if they match the given {@link
     * android.app.appsearch.observer.ObserverSpec}.
     *
     * <p>If the data owned by {@code observedPackage} is not visible to you, the registration call
     * will succeed but no notifications will be dispatched. Notifications could start flowing later
     * if {@code observedPackage} changes its schema visibility settings.
     *
     * <p>If no package matching {@code observedPackage} exists on the system, the registration call
     * will succeed but no notifications will be dispatched. Notifications could start flowing later
     * if {@code observedPackage} is installed and starts indexing data.
     *
     * <p>Note that this method does not take the standard read/write lock that guards I/O, so it
     * will not queue behind I/O. Therefore it is safe to call from any thread including UI or
     * binder threads.
     */
    public void addObserver(
            @NonNull String observedPackage,
            @NonNull ObserverSpec spec,
            @NonNull Executor executor,
            @NonNull AppSearchObserverCallback observer) {
        // This method doesn't consult mSchemaMap or mNamespaceMap, and it will register
        // observers for types that don't exist. This is intentional because we notify for types
        // being created or removed. If we only registered observer for existing types, it would
        // be impossible to ever dispatch a notification of a type being added.
        mObserverManager.addObserver(observedPackage, spec, executor, observer);
    }

    /**
     * Removes an {@link AppSearchObserverCallback} from watching the databases owned by {@code
     * observedPackage}.
     *
     * <p>All observers which compare equal to the given observer via {@link
     * AppSearchObserverCallback#equals} are removed. This may be 0, 1, or many observers.
     *
     * <p>Note that this method does not take the standard read/write lock that guards I/O, so it
     * will not queue behind I/O. Therefore it is safe to call from any thread including UI or
     * binder threads.
     */
    public void removeObserver(
            @NonNull String observedPackage, @NonNull AppSearchObserverCallback observer) {
        mObserverManager.removeObserver(observedPackage, observer);
    }

    /**
     * Dispatches the pending change notifications one at a time.
     *
     * <p>The notifications are dispatched on the respective executors that were provided at the
     * time of observer registration. This method does not take the standard read/write lock that
     * guards I/O, so it is safe to call from any thread including UI or binder threads.
     *
     * <p>Exceptions thrown from notification dispatch are logged but otherwise suppressed.
     */
    public void dispatchAndClearChangeNotifications() {
        mObserverManager.dispatchAndClearPendingNotifications();
    }

    private static void addToMap(
            Map<String, Set<String>> map, String prefix, String prefixedValue) {
        Set<String> values = map.get(prefix);
        if (values == null) {
            values = new ArraySet<>();
            map.put(prefix, values);
        }
        values.add(prefixedValue);
    }

    private static void addToMap(
            Map<String, Map<String, SchemaTypeConfigProto>> map,
            String prefix,
            SchemaTypeConfigProto schemaTypeConfigProto) {
        Map<String, SchemaTypeConfigProto> schemaTypeMap = map.get(prefix);
        if (schemaTypeMap == null) {
            schemaTypeMap = new ArrayMap<>();
            map.put(prefix, schemaTypeMap);
        }
        schemaTypeMap.put(schemaTypeConfigProto.getSchemaType(), schemaTypeConfigProto);
    }

    private static void removeFromMap(
            Map<String, Map<String, SchemaTypeConfigProto>> map, String prefix, String schemaType) {
        Map<String, SchemaTypeConfigProto> schemaTypeMap = map.get(prefix);
        if (schemaTypeMap != null) {
            schemaTypeMap.remove(schemaType);
        }
    }

    /**
     * Checks whether the caller package has been granted access to the typeName type created by
     * packageName package for the databaseName database.
     *
     * @param unusedCallerPackageName the package name of the caller
     * @param unusedPackageName the name of the package that owns the database
     * @param unusedDatabaseName the name of the database that owns the type
     * @param unusedTypeName the (non-prefixed) type
     */
    private boolean hasAccessToType(
            String unusedCallerPackageName,
            String unusedPackageName,
            String unusedDatabaseName,
            String unusedTypeName) {
        // TODO(b/215624105): Update this after permission's refactoring is complete to allow
        //  access beyond the callerPackage.
        return true;
    }

    /**
     * Checks the given status code and throws an {@link AppSearchException} if code is an error.
     *
     * @throws AppSearchException on error codes.
     */
    private static void checkSuccess(StatusProto statusProto) throws AppSearchException {
        checkCodeOneOf(statusProto, StatusProto.Code.OK);
    }

    /**
     * Checks the given status code is one of the provided codes, and throws an {@link
     * AppSearchException} if it is not.
     */
    private static void checkCodeOneOf(StatusProto statusProto, StatusProto.Code... codes)
            throws AppSearchException {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == statusProto.getCode()) {
                // Everything's good
                return;
            }
        }

        if (statusProto.getCode() == StatusProto.Code.WARNING_DATA_LOSS) {
            // TODO: May want to propagate WARNING_DATA_LOSS up to AppSearchSession so they can
            //  choose to log the error or potentially pass it on to clients.
            Log.w(TAG, "Encountered WARNING_DATA_LOSS: " + statusProto.getMessage());
            return;
        }

        throw new AppSearchException(
                ResultCodeToProtoConverter.toResultCode(statusProto.getCode()),
                statusProto.getMessage());
    }

    /**
     * Checks whether {@link IcingSearchEngine#optimize()} should be called to release resources.
     *
     * <p>This method should be only called after a mutation to local storage backend which deletes
     * a mass of data and could release lots resources after {@link IcingSearchEngine#optimize()}.
     *
     * <p>This method will trigger {@link IcingSearchEngine#getOptimizeInfo()} to check resources
     * that could be released for every {@link #CHECK_OPTIMIZE_INTERVAL} mutations.
     *
     * <p>{@link IcingSearchEngine#optimize()} should be called only if {@link
     * GetOptimizeInfoResultProto} shows there is enough resources could be released.
     *
     * @param mutationSize The number of how many mutations have been executed for current request.
     *     An inside counter will accumulates it. Once the counter reaches {@link
     *     #CHECK_OPTIMIZE_INTERVAL}, {@link IcingSearchEngine#getOptimizeInfo()} will be triggered
     *     and the counter will be reset.
     */
    public void checkForOptimize(int mutationSize, @Nullable OptimizeStats.Builder builder)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            mOptimizeIntervalCountLocked += mutationSize;
            if (mOptimizeIntervalCountLocked >= CHECK_OPTIMIZE_INTERVAL) {
                checkForOptimize(builder);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Checks whether {@link IcingSearchEngine#optimize()} should be called to release resources.
     *
     * <p>This method will directly trigger {@link IcingSearchEngine#getOptimizeInfo()} to check
     * resources that could be released.
     *
     * <p>{@link IcingSearchEngine#optimize()} should be called only if {@link
     * OptimizeStrategy#shouldOptimize(GetOptimizeInfoResultProto)} return true.
     */
    public void checkForOptimize(@Nullable OptimizeStats.Builder builder)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            GetOptimizeInfoResultProto optimizeInfo = getOptimizeInfoResultLocked();
            checkSuccess(optimizeInfo.getStatus());
            mOptimizeIntervalCountLocked = 0;
            if (mOptimizeStrategy.shouldOptimize(optimizeInfo)) {
                optimize(builder);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        // TODO(b/147699081): Return OptimizeResultProto & log lost data detail once we add
        //  a field to indicate lost_schema and lost_documents in OptimizeResultProto.
        //  go/icing-library-apis.
    }

    /** Triggers {@link IcingSearchEngine#optimize()} directly. */
    public void optimize(@Nullable OptimizeStats.Builder builder) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            mLogUtil.piiTrace("optimize, request");
            OptimizeResultProto optimizeResultProto = mIcingSearchEngineLocked.optimize();
            mLogUtil.piiTrace(
                    "optimize, response", optimizeResultProto.getStatus(), optimizeResultProto);
            if (builder != null) {
                builder.setStatusCode(statusProtoToResultCode(optimizeResultProto.getStatus()));
                AppSearchLoggerHelper.copyNativeStats(
                        optimizeResultProto.getOptimizeStats(), builder);
            }
            checkSuccess(optimizeResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    GetOptimizeInfoResultProto getOptimizeInfoResultLocked() {
        mLogUtil.piiTrace("getOptimizeInfo, request");
        GetOptimizeInfoResultProto result = mIcingSearchEngineLocked.getOptimizeInfo();
        mLogUtil.piiTrace("getOptimizeInfo, response", result.getStatus(), result);
        return result;
    }

    /**
     * Returns all prefixed schema types saved in AppSearch.
     *
     * <p>This method is inefficient to call repeatedly.
     */
    @NonNull
    public List<String> getAllPrefixedSchemaTypes() {
        mReadWriteLock.readLock().lock();
        try {
            List<String> cachedPrefixedSchemaTypes = new ArrayList<>();
            for (Map<String, SchemaTypeConfigProto> value : mSchemaMapLocked.values()) {
                cachedPrefixedSchemaTypes.addAll(value.keySet());
            }
            return cachedPrefixedSchemaTypes;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Converts an erroneous status code from the Icing status enums to the AppSearchResult enums.
     *
     * <p>Callers should ensure that the status code is not OK or WARNING_DATA_LOSS.
     *
     * @param statusProto StatusProto with error code to translate into an {@link AppSearchResult}
     *     code.
     * @return {@link AppSearchResult} error code
     */
    private static @AppSearchResult.ResultCode int statusProtoToResultCode(
            @NonNull StatusProto statusProto) {
        return ResultCodeToProtoConverter.toResultCode(statusProto.getCode());
    }
}
