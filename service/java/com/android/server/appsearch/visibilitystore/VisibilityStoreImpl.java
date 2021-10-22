/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.appsearch.visibilitystore;

import static android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA;
import static android.app.appsearch.AppSearchResult.RESULT_NOT_FOUND;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;
import com.android.server.appsearch.util.PackageUtil;

import com.google.android.icing.proto.PersistType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages any visibility settings for all the package's databases that AppSearchImpl knows about.
 * Persists the visibility settings and reloads them on initialization.
 *
 * <p>The VisibilityStore creates a document for each package's databases. This document holds the
 * visibility settings that apply to that package's database. The VisibilityStore also creates a
 * schema for these documents and has its own package and database so that its data doesn't
 * interfere with any clients' data. It persists the document and schema through AppSearchImpl.
 *
 * <p>These visibility settings are used to ensure AppSearch queries respect the clients' settings
 * on who their data is visible to.
 *
 * <p>This class doesn't handle any locking itself. Its callers should handle the locking at a
 * higher level.
 *
 * @hide
 */
public class VisibilityStoreImpl implements VisibilityStore {
    private static final String TAG = "AppSearchVisibilityStor";
    /** Version for the visibility schema */
    private static final int SCHEMA_VERSION = 1;

    private final AppSearchImpl mAppSearchImpl;

    // Context of the user that the call is being made as.
    private final Context mUserContext;

    /**
     * Map of PrefixedSchemaType and VisibilityDocument stores visibility information for each
     * schema type.
     */
    private final Map<String, VisibilityDocument> mVisibilityDocumentMap = new ArrayMap<>();

    /**
     * Creates and initializes VisibilityStore.
     *
     * @param appSearchImpl AppSearchImpl instance
     * @param userContext Context of the user that the call is being made as
     */
    @NonNull
    public static VisibilityStoreImpl create(
            @NonNull AppSearchImpl appSearchImpl, @NonNull Context userContext)
            throws AppSearchException {
        return new VisibilityStoreImpl(appSearchImpl, userContext);
    }

    private VisibilityStoreImpl(@NonNull AppSearchImpl appSearchImpl, @NonNull Context userContext)
            throws AppSearchException {
        mAppSearchImpl = Objects.requireNonNull(appSearchImpl);
        mUserContext = Objects.requireNonNull(userContext);

        // TODO(b/202194495) handle schema migration from version 0 to 1.
        GetSchemaResponse getSchemaResponse =
            mAppSearchImpl.getSchema(PACKAGE_NAME, PACKAGE_NAME, DATABASE_NAME);
        boolean hasVisibilityType = false;
        for (AppSearchSchema schema : getSchemaResponse.getSchemas()) {
            if (schema.getSchemaType().equals(VisibilityDocument.SCHEMA_TYPE)) {
                hasVisibilityType = true;
                // Found our type, can exit early.
                break;
            }
        }
        if (!hasVisibilityType) {
            // The latest schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(
                    PACKAGE_NAME,
                    DATABASE_NAME,
                    Collections.singletonList(VisibilityDocument.SCHEMA),
                    /*visibilityStore=*/ null,  // Avoid recursive calls
                    /*prefixedVisibilityDocuments=*/ Collections.emptyList(),
                    /*forceOverride=*/ false,
                    /*version=*/ SCHEMA_VERSION,
                    /*setSchemaStatsBuilder=*/ null);
        } else {
            loadVisibilityDocumentMap();
        }
    }

    @Override
    public void setVisibility(@NonNull List<VisibilityDocument> prefixedVisibilityDocuments)
            throws AppSearchException {
        Objects.requireNonNull(prefixedVisibilityDocuments);
        // Save new setting.
        for (int i = 0; i < prefixedVisibilityDocuments.size(); i++) {
            // put VisibilityDocument to AppSearchImpl and mVisibilityDocumentMap. If there is a
            // VisibilityDocument with same prefixed schema exists, it will be replaced by new
            // VisibilityDocument in both AppSearch and memory look up map.
            VisibilityDocument prefixedVisibilityDocument = prefixedVisibilityDocuments.get(i);
            mAppSearchImpl.putDocument(PACKAGE_NAME, DATABASE_NAME,
                    prefixedVisibilityDocument, /*logger=*/ null);
            mVisibilityDocumentMap.put(prefixedVisibilityDocument.getId(),
                    prefixedVisibilityDocument);
        }

        // Now that the visibility document has been written. Persist the newly written data.
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);
    }

    /**
     * Checks whether the given package has access to system-surfaceable schemas.
     *
     * @param callerPackageName Package name of the caller.
     */
    public boolean doesCallerHaveSystemAccess(@NonNull String callerPackageName) {
        Objects.requireNonNull(callerPackageName);
        return mUserContext.getPackageManager()
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, callerPackageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean isSchemaSearchableByCaller(
            @NonNull String packageName,
            @NonNull String prefixedSchema,
            int callerUid,
            boolean callerHasSystemAccess) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(prefixedSchema);

        if (packageName.equals(PACKAGE_NAME)) {
            return false; // VisibilityStore schemas are for internal bookkeeping.
        }

        VisibilityDocument visibilityDocument = mVisibilityDocumentMap.get(prefixedSchema);

        if (visibilityDocument == null) {
            // The target schema doesn't exist yet. We will treat it as default setting and the only
            // accessible case is that the caller has system access.
            return callerHasSystemAccess;
        }

        if (callerHasSystemAccess && !visibilityDocument.isNotDisplayedBySystem()) {
            return true;
        }

        // May not be platform surfaceable, but might still be accessible through 3p access.
        return isSchemaVisibleToPackages(visibilityDocument, callerUid);
    }

    @Override
    public void removeVisibility(@NonNull Set<String> deletedPrefixedSchemaTypes)
            throws AppSearchException {
        for (String prefixedSchemaType : deletedPrefixedSchemaTypes) {
            if (mVisibilityDocumentMap.remove(prefixedSchemaType) == null) {
                // The deleted schema is not all-default setting, we need to remove its
                // VisibilityDocument from Icing.
                try {
                    mAppSearchImpl.remove(PACKAGE_NAME, DATABASE_NAME, VisibilityDocument.NAMESPACE,
                            prefixedSchemaType, /*removeStatsBuilder=*/null);
                } catch (AppSearchException e) {
                    if (e.getResultCode() == RESULT_NOT_FOUND) {
                        // We are trying to remove this visibility setting, so it's weird but seems
                        // to be fine if we cannot find it.
                        Log.e(TAG, "Cannot find visibility document for " + prefixedSchemaType
                                + " to remove.");
                        return;
                    }
                    throw e;
                }
            }
        }
    }

    /**
     * Returns whether the schema is accessible by the {@code callerUid}. Checks that the callerUid
     * has one of the allowed PackageIdentifier's package. And if so, that the package also has the
     * matching certificate.
     *
     * <p>This supports packages that have certificate rotation. As long as the specified
     * certificate was once used to sign the package, the package will still be granted access. This
     * does not handle packages that have been signed by multiple certificates.
     */
    private boolean isSchemaVisibleToPackages(@NonNull VisibilityDocument visibilityDocument,
            int callerUid) {
        String[] packageNames = visibilityDocument.getPackageNames();
        byte[][] sha256Certs = visibilityDocument.getSha256Certs();
        if (packageNames.length != sha256Certs.length) {
            // We always set them in pair, So this must has something wrong.
            throw new IllegalArgumentException("Package names and sha 256 certs doesn't match!");
        }
        for (int i = 0; i < packageNames.length; i++) {
            // TODO(b/169883602): Consider caching the UIDs of packages. Looking this up in the
            // package manager could be costly. We would also need to update the cache on
            // package-removals.

            // 'callerUid' is the uid of the caller. The 'user' doesn't have to be the same one as
            // the callerUid since clients can createContextAsUser with some other user, and then
            // make calls to us. So just check if the appId portion of the uid is the same. This is
            // essentially UserHandle.isSameApp, but that's not a system API for us to use.
            int callerAppId = UserHandle.getAppId(callerUid);
            int packageUid = PackageUtil.getPackageUid(mUserContext, packageNames[i]);
            int userAppId = UserHandle.getAppId(packageUid);
            if (callerAppId != userAppId) {
                continue;
            }

            // Check that the package also has the matching certificate
            if (mUserContext
                    .getPackageManager()
                    .hasSigningCertificate(
                            packageNames[i],
                            sha256Certs[i],
                            PackageManager.CERT_INPUT_SHA256)) {
                // The caller has the right package name and right certificate!
                return true;
            }
        }
        // If we can't verify the schema is package accessible, default to no access.
        return false;
    }

    /**
     * Loads all stored latest {@link VisibilityDocument} from Icing, and put them into
     * {@link #mVisibilityDocumentMap}.
     */
    private void loadVisibilityDocumentMap() throws AppSearchException {
        // Populate visibility settings set
        List<String> cachedSchemaTypes = mAppSearchImpl.getAllPrefixedSchemaTypes();
        for (int i = 0; i < cachedSchemaTypes.size(); i++) {
            String prefixedSchemaType = cachedSchemaTypes.get(i);
            String packageName = PrefixUtil.getPackageName(prefixedSchemaType);
            if (packageName.equals(PACKAGE_NAME)) {
                continue; // Our own package. Skip.
            }

            VisibilityDocument visibilityDocument;
            try {
                // Note: We use the other clients' prefixed schema type as ids
                visibilityDocument = new VisibilityDocument(
                        mAppSearchImpl.getDocument(
                                PACKAGE_NAME,
                                DATABASE_NAME,
                                VisibilityDocument.NAMESPACE,
                                /*id=*/ prefixedSchemaType,
                                /*typePropertyPaths=*/ Collections.emptyMap()));
            } catch (AppSearchException e) {
                if (e.getResultCode() == RESULT_NOT_FOUND) {
                    // The schema has all default setting and we won't have a VisibilityDocument for
                    // it.
                    continue;
                }
                // Otherwise, this is some other error we should pass up.
                throw e;
            }
            mVisibilityDocumentMap.put(prefixedSchemaType, visibilityDocument);
        }
    }
}
