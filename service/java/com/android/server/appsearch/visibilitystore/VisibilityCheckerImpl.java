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

import android.annotation.NonNull;
import android.app.appsearch.VisibilityDocument;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.android.server.appsearch.external.localstorage.visibilitystore.CallerAccess;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;
import com.android.server.appsearch.util.PackageUtil;

import java.util.Objects;

/**
 * A platform implementation of {@link VisibilityChecker}.
 *
 * @hide
 */
public class VisibilityCheckerImpl implements VisibilityChecker {
    // Context of the user that the call is being made as.
    private final Context mUserContext;

    public VisibilityCheckerImpl(@NonNull Context userContext) {
        mUserContext = Objects.requireNonNull(userContext);
    }

    @Override
    public boolean isSchemaSearchableByCaller(
            @NonNull CallerAccess callerAccess,
            @NonNull String packageName,
            @NonNull String prefixedSchema,
            @NonNull VisibilityStore visibilityStore) {
        Objects.requireNonNull(callerAccess);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(prefixedSchema);

        if (packageName.equals(VisibilityStore.VISIBILITY_PACKAGE_NAME)) {
            return false; // VisibilityStore schemas are for internal bookkeeping.
        }

        FrameworkCallerAccess frameworkCallerAccess = (FrameworkCallerAccess) callerAccess;
        VisibilityDocument visibilityDocument = visibilityStore.getVisibility(prefixedSchema);
        if (visibilityDocument == null) {
            // The target schema doesn't exist yet. We will treat it as default setting and the only
            // accessible case is that the caller has system access.
            return frameworkCallerAccess.doesCallerHaveSystemAccess();
        }

        if (frameworkCallerAccess.doesCallerHaveSystemAccess() &&
                !visibilityDocument.isNotDisplayedBySystem()) {
            return true;
        }

        // May not be platform surfaceable, but might still be accessible through 3p access.
        return isSchemaVisibleToPackages(visibilityDocument, frameworkCallerAccess.getCallingUid());
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
}
