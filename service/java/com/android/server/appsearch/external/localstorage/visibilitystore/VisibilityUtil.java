/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.visibilitystore;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * Utilities for working with {@link VisibilityChecker} and {@link VisibilityStore}.
 *
 * @hide
 */
public class VisibilityUtil {
    private VisibilityUtil() {}

    /**
     * Determines whether the calling package has access to the given prefixed schema type.
     *
     * <p>Correctly handles access to own data and the situation that visibilityStore and
     * visibilityChecker are not configured.
     *
     * @param callerPackageName The package name of the app that wants to access the data.
     * @param callerUid The uid of app that wants to access the data.
     * @param callerHasSystemAccess Whether the app that wants to access the data has access to
     *     schema types marked visible to the system.
     * @param targetPackageName The package name of the app that owns the data.
     * @param prefixedSchema The prefixed schema type the caller wants to access.
     * @param visibilityStore Store for visibility information. If not provided, only access to own
     *     data will be allowed.
     * @param visibilityChecker Checker for visibility access. If not provided, only access to own
     *     data will be allowed.
     * @return Whether access by the caller to this prefixed schema should be allowed.
     */
    public static boolean isSchemaSearchableByCaller(
            @NonNull String callerPackageName,
            int callerUid,
            boolean callerHasSystemAccess,
            @NonNull String targetPackageName,
            @NonNull String prefixedSchema,
            @Nullable VisibilityStore visibilityStore,
            @Nullable VisibilityChecker visibilityChecker) {
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(targetPackageName);
        Objects.requireNonNull(prefixedSchema);
        if (callerPackageName.equals(targetPackageName)) {
            return true; // Everyone is always allowed to retrieve their own data.
        }
        if (visibilityStore == null || visibilityChecker == null) {
            return false; // No visibility is configured at this time; no other access possible.
        }
        return visibilityChecker.isSchemaSearchableByCaller(
                targetPackageName,
                prefixedSchema,
                callerUid,
                callerHasSystemAccess,
                visibilityStore);
    }
}
