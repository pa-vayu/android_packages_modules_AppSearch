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

package com.android.server.appsearch.contactsindexer.appsearchtypes;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a Person in AppSearch.
 *
 * @hide
 */
public class Person extends GenericDocument {
    static final String SCHEMA_TYPE = "builtin:Person";

    // Properties
    static final String PERSON_PROPERTY_NAME = "name";
    static final String PERSON_PROPERTY_GIVEN_NAME = "givenName";
    static final String PERSON_PROPERTY_MIDDLE_NAME = "middleName";
    static final String PERSON_PROPERTY_FAMILY_NAME = "familyName";
    static final String PERSON_PROPERTY_EXTERNAL_URI = "externalUri";
    static final String PERSON_PROPERTY_ADDITIONAL_NAME = "additionalName";
    static final String PERSON_PROPERTY_IS_IMPORTANT = "isImportant";
    static final String PERSON_PROPERTY_IS_BOT = "isBot";
    static final String PERSON_PROPERTY_IMAGE_URI = "imageUri";
    static final String PERSON_PROPERTY_CONTACT_POINT = "contactPoint";

    static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            // full display name
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(PERSON_PROPERTY_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(
                            AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .build())
            // given name from CP2
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_GIVEN_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // middle name from CP2
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_MIDDLE_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // family name from CP2
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_FAMILY_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // lookup uri from CP2
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_EXTERNAL_URI)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // additional names e.g. nick names and phonetic names.
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_ADDITIONAL_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setIndexingType(
                            AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .build())
            // isImportant. It could be used to store isStarred from CP2.
            .addProperty(new AppSearchSchema.BooleanPropertyConfig.Builder(
                    PERSON_PROPERTY_IS_IMPORTANT)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // isBot
            .addProperty(new AppSearchSchema.BooleanPropertyConfig.Builder(
                    PERSON_PROPERTY_IS_BOT)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // imageUri
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    PERSON_PROPERTY_IMAGE_URI)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            // ContactPoint
            .addProperty(new AppSearchSchema.DocumentPropertyConfig.Builder(
                    PERSON_PROPERTY_CONTACT_POINT,
                    ContactPoint.SCHEMA.getSchemaType())
                    .setShouldIndexNestedProperties(true)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .build();

    /** Constructs a {@link Person}. */
    Person(@NonNull GenericDocument document) {
        super(document);
    }

    @NonNull
    public String getName() {
        return getPropertyString(PERSON_PROPERTY_NAME);
    }

    @Nullable
    public String getGivenName() {
        return getPropertyString(PERSON_PROPERTY_GIVEN_NAME);
    }

    @Nullable
    public String getMiddleName() {
        return getPropertyString(PERSON_PROPERTY_MIDDLE_NAME);
    }

    @Nullable
    public String getFamilyName() {
        return getPropertyString(PERSON_PROPERTY_FAMILY_NAME);
    }

    @Nullable
    public Uri getExternalUri() {
        String uriStr = getPropertyString(PERSON_PROPERTY_EXTERNAL_URI);
        if (uriStr == null) {
            return null;
        }
        return Uri.parse(uriStr);
    }

    @Nullable
    public Uri getImageUri() {
        String uriStr = getPropertyString(PERSON_PROPERTY_IMAGE_URI);
        if (uriStr == null) {
            return null;
        }
        return Uri.parse(uriStr);
    }

    public boolean isImportant() {
        return getPropertyBoolean(PERSON_PROPERTY_IS_IMPORTANT);
    }

    public boolean isBot() {
        return getPropertyBoolean(PERSON_PROPERTY_IS_BOT);
    }

    @NonNull
    public String[] getAdditionalNames() {
        return getPropertyStringArray(PERSON_PROPERTY_ADDITIONAL_NAME);
    }

    // This method is expensive, and is intended to be used in tests only.
    @NonNull
    public ContactPoint[] getContactPoints() {
        GenericDocument[] docs = getPropertyDocumentArray(PERSON_PROPERTY_CONTACT_POINT);
        ContactPoint[] contactPoints = new ContactPoint[docs.length];
        for (int i = 0; i < contactPoints.length; ++i) {
            contactPoints[i] = new ContactPoint(docs[i]);
        }
        return contactPoints;
    }

    /** Builder for {@link Person}. */
    public static final class Builder extends GenericDocument.Builder<Builder> {
        private final List<String> mAdditionalNames = new ArrayList<>();
        private final List<ContactPoint> mContactPoints = new ArrayList<>();

        /**
         * Creates a new {@link ContactPoint.Builder}
         *
         * @param namespace The namespace of the Email.
         * @param id        The ID of the Email.
         * @param name      The name of the {@link Person}.
         */
        public Builder(@NonNull String namespace, @NonNull String id, @NonNull String name) {
            super(namespace, id, SCHEMA_TYPE);
            setName(name);
        }

        /** Sets the full display name. */
        @NonNull
        private Builder setName(@NonNull String name) {
            setPropertyString(PERSON_PROPERTY_NAME, name);
            return this;
        }

        @NonNull
        public Builder setGivenName(@NonNull String givenName) {
            setPropertyString(PERSON_PROPERTY_GIVEN_NAME, givenName);
            return this;
        }

        @NonNull
        public Builder setMiddleName(@NonNull String middleName) {
            setPropertyString(PERSON_PROPERTY_MIDDLE_NAME, middleName);
            return this;
        }

        @NonNull
        public Builder setFamilyName(@NonNull String familyName) {
            setPropertyString(PERSON_PROPERTY_FAMILY_NAME, familyName);
            return this;
        }

        @NonNull
        public Builder setExternalUri(@NonNull Uri externalUri) {
            setPropertyString(PERSON_PROPERTY_EXTERNAL_URI,
                    Objects.requireNonNull(externalUri).toString());
            return this;
        }

        @NonNull
        public Builder setImageUri(@NonNull Uri imageUri) {
            setPropertyString(PERSON_PROPERTY_IMAGE_URI,
                    Objects.requireNonNull(imageUri).toString());
            return this;
        }

        @NonNull
        public Builder setIsImportant(boolean isImportant) {
            setPropertyBoolean(PERSON_PROPERTY_IS_IMPORTANT, isImportant);
            return this;
        }

        @NonNull
        public Builder setIsBot(boolean isBot) {
            setPropertyBoolean(PERSON_PROPERTY_IS_BOT, isBot);
            return this;
        }

        @NonNull
        public Builder addAdditionalName(@NonNull String additionalName) {
            Objects.requireNonNull(additionalName);
            mAdditionalNames.add(additionalName);
            return this;
        }

        @NonNull
        public Builder addContactPoint(@NonNull ContactPoint contactPoint) {
            Objects.requireNonNull(contactPoint);
            mContactPoints.add(contactPoint);
            return this;
        }

        @NonNull
        public Person build() {
            setPropertyString(PERSON_PROPERTY_ADDITIONAL_NAME,
                    mAdditionalNames.toArray(new String[0]));
            setPropertyDocument(PERSON_PROPERTY_CONTACT_POINT,
                    mContactPoints.toArray(new ContactPoint[0]));
            return new Person(super.build());
        }
    }
}
