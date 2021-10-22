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

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SetSchemaRequest;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.appsearch.external.localstorage.util.PrefixUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Holds the visibility settings that apply to a schema type.
 * @hide
 */
public class VisibilityDocument extends GenericDocument {
    /**
     * Prefixed Schema type for documents that hold AppSearch's metadata, e.g. visibility settings
     */
    public static final String SCHEMA_TYPE = "VisibilityType";
    /** Namespace of documents that contain visibility settings */
    public static final String NAMESPACE = "";

    /**
     * Property that holds the list of platform-hidden schemas, as part of the visibility settings.
     */
    private static final String NOT_DISPLAYED_BY_SYSTEM_PROPERTY = "notPlatformSurfaceable";

    /** Property that holds the package name that can access a schema. */
    private static final String PACKAGE_NAME_PROPERTY = "packageName";

    /** Property that holds the SHA 256 certificate of the app that can access a schema. */
    private static final String SHA_256_CERT_PROPERTY = "sha256Cert";

    /**
     * Schema for the VisibilityStore's documents.
     *
     * <p>NOTE: If you update this, also update
     * {@link com.android.server.appsearch.visibilitystore.VisibilityStoreImpl#SCHEMA_VERSION}
     */
    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new AppSearchSchema.BooleanPropertyConfig.Builder(
                    NOT_DISPLAYED_BY_SYSTEM_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(PACKAGE_NAME_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .addProperty(new AppSearchSchema.BytesPropertyConfig.Builder(SHA_256_CERT_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .build();

    public VisibilityDocument(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    public VisibilityDocument(@NonNull Bundle bundle) {
        super(bundle);
    }

    /** Returns whether this schema is visible to the system. */
    public boolean isNotDisplayedBySystem() {
        return getPropertyBoolean(NOT_DISPLAYED_BY_SYSTEM_PROPERTY);
    }

    /**
     * Returns a package name array which could access this schema. Use {@link #getSha256Certs()}
     * to get package's sha 256 certs. The same index of package names array and sha256Certs array
     * represents same package.
     */
    @NonNull
    public String[] getPackageNames() {
        return getPropertyStringArray(PACKAGE_NAME_PROPERTY);
    }

    /**
     * Returns a package sha256Certs array which could access this schema. Use
     * {@link #getPackageNames()} to get package's name. The same index of package names array
     * and sha256Certs array represents same package.
     */
    @NonNull
    public byte[][] getSha256Certs() {
        return getPropertyBytesArray(SHA_256_CERT_PROPERTY);
    }

    /** Builder for {@link VisibilityDocument}. */
    public static class Builder extends GenericDocument.Builder<VisibilityDocument.Builder> {
        private final Set<PackageIdentifier> mPackageIdentifiers = new ArraySet<>();

        /**
         * Creates a {@link Builder} for a {@link VisibilityDocument}.
         *
         * @param id The SchemaType of the {@link AppSearchSchema} that this
         *           {@link VisibilityDocument} represents. The package and database prefix will be
         *           added in server side. We are using prefixed schema type to be the final id of
         *           this {@link VisibilityDocument}.
         */
        public Builder(@NonNull String id) {
            super(NAMESPACE, id, SCHEMA_TYPE);
        }

        /** Sets whether this schema has opted out of platform surfacing. */
        @NonNull
        public Builder setNotDisplayedBySystem(boolean notDisplayedBySystem) {
            return setPropertyBoolean(NOT_DISPLAYED_BY_SYSTEM_PROPERTY,
                    notDisplayedBySystem);
        }

        /** Add {@link PackageIdentifier} of packages which has access to this schema. */
        @NonNull
        public Builder addVisibleToPackages(@NonNull Collection<PackageIdentifier>
                packageIdentifiers) {
            Objects.requireNonNull(packageIdentifiers);
            mPackageIdentifiers.addAll(packageIdentifiers);
            return this;
        }

        /** Add {@link PackageIdentifier} of packages which has access to this schema. */
        @NonNull
        public Builder addVisibleToPackage(@NonNull PackageIdentifier packageIdentifier) {
            Objects.requireNonNull(packageIdentifier);
            mPackageIdentifiers.add(packageIdentifier);
            return this;
        }

        @NonNull
        public VisibilityDocument build() {
            String[] packageNames = new String[mPackageIdentifiers.size()];
            byte[][] sha256Certs = new byte[mPackageIdentifiers.size()][32];
            int i = 0;
            for (PackageIdentifier packageIdentifier : mPackageIdentifiers) {
                packageNames[i] = packageIdentifier.getPackageName();
                sha256Certs[i] = packageIdentifier.getSha256Certificate();
                ++i;
            }
            setPropertyString(PACKAGE_NAME_PROPERTY, packageNames);
            setPropertyBytes(SHA_256_CERT_PROPERTY, sha256Certs);
            return new VisibilityDocument(super.build());
        }
    }

    /**
     * Build the List of {@link VisibilityDocument} from visibility settings.
     *
     * @param schemas                     List of {@link AppSearchSchema}.
     * @param schemasNotDisplayedBySystem Non-prefixed Schema types that should not be surfaced on
     *                                    platform surfaces.
     * @param schemasVisibleToPackages    Non-prefixed Schema types that are visible to the
     *                                    specified packages. The value List contains
     *                                    PackageIdentifier.
     */
    //TODO(b/202194495) move this class to the sdk side and convert from SetSchemaRequest.
    @NonNull
    public static List<VisibilityDocument> toVisibilityDocuments(
            @NonNull List<AppSearchSchema> schemas,
            @NonNull List<String> schemasNotDisplayedBySystem,
            @NonNull Map<String, List<PackageIdentifier>> schemasVisibleToPackages) {
        Map<String, VisibilityDocument.Builder> documentBuilderMap = new ArrayMap<>(schemas.size());

        // Set all visibility information into documentBuilderMap. All invoked schema types must
        // present in schemas. This is checked in SetSchemaRequest.Builder.Build();

        // Save schemas not displayed by system into documentBuilderMap
        for (int i = 0; i < schemasNotDisplayedBySystem.size(); i++) {
            VisibilityDocument.Builder visibilityBuilder = getOrCreateBuilder(
                    documentBuilderMap, schemasNotDisplayedBySystem.get(i));
            visibilityBuilder.setNotDisplayedBySystem(true);
        }

        // Save schemas visible package identifier into documentBuilderMap
        for (Map.Entry<String, List<PackageIdentifier>> entry :
                schemasVisibleToPackages.entrySet()) {
            VisibilityDocument.Builder visibilityBuilder = getOrCreateBuilder(
                    documentBuilderMap, entry.getKey());
            visibilityBuilder.addVisibleToPackages(entry.getValue());
        }

        // Convert Map<Schema, document.builder> into list of document.
        List<VisibilityDocument> visibilityDocuments = new ArrayList<>(documentBuilderMap.size());
        for (VisibilityDocument.Builder builder : documentBuilderMap.values()) {
            visibilityDocuments.add(builder.build());
        }
        return visibilityDocuments;
    }

    @NonNull
    private static Builder getOrCreateBuilder(
            @NonNull Map<String, VisibilityDocument.Builder> documentBuilderMap,
            @NonNull String schemaType) {
        Builder builder = documentBuilderMap.get(schemaType);
        if (builder == null) {
            builder = new VisibilityDocument.Builder(/*id=*/ schemaType);
            documentBuilderMap.put(schemaType, builder);
        }
        return builder;
    }
}
