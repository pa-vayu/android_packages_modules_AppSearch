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

package com.android.server.appsearch.contactsindexer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.Data;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Helper Class to handle data for different MIME types from CP2, and build {@link Person} from
 * them.
 *
 * <p>This class is not thread safe.
 *
 * @hide
 */
public final class ContactDataHandler {
    private final Map<String, DataHandler> mHandlers;
    private final Set<String> mNeededColumns;

    /** Constructor. */
    public ContactDataHandler(Resources resources) {
        // Create handlers for different MIME types
        mHandlers = new ArrayMap<>();
        mHandlers.put(Email.CONTENT_ITEM_TYPE, new EmailDataHandler(resources));
        mHandlers.put(Nickname.CONTENT_ITEM_TYPE, new NicknameDataHandler());
        mHandlers.put(Phone.CONTENT_ITEM_TYPE, new PhoneHandler(resources));
        mHandlers.put(StructuredPostal.CONTENT_ITEM_TYPE, new StructuredPostalHandler(resources));
        mHandlers.put(StructuredName.CONTENT_ITEM_TYPE, new StructuredNameHandler());
        mHandlers.put(Organization.CONTENT_ITEM_TYPE, new OrganizationDataHandler());
        mHandlers.put(Relation.CONTENT_ITEM_TYPE, new RelationDataHandler(resources));
        mHandlers.put(Note.CONTENT_ITEM_TYPE, new NoteDataHandler());

        // Retrieve all the needed columns from different data handlers.
        Set<String> neededColumns = new ArraySet<>();
        neededColumns.add(ContactsContract.Data.MIMETYPE);
        for (DataHandler handler : mHandlers.values()) {
            handler.addNeededColumns(neededColumns);
        }
        // We need to make sure this is unmodifiable since the reference is returned in
        // getNeededColumns().
        mNeededColumns = Collections.unmodifiableSet(neededColumns);
    }

    /** Returns an unmodifiable set of columns this {@link ContactDataHandler} is asking for. */
    public Set<String> getNeededColumns() {
        return mNeededColumns;
    }

    /**
     * Adds the information of the current row from {@link ContactsContract.Data} table
     * into the {@link PersonBuilderHelper}.
     *
     * <p>By reading each row in the table, we will get the detailed information about a
     * Person(contact).
     *
     * @param builderHelper a helper to build the {@link Person}.
     */
    public void convertCursorToPerson(@NonNull Cursor cursor,
            @NonNull PersonBuilderHelper builderHelper) {
        Objects.requireNonNull(cursor);
        Objects.requireNonNull(builderHelper);

        int mimetypeIndex = cursor.getColumnIndex(Data.MIMETYPE);
        String mimeType = cursor.getString(mimetypeIndex);
        DataHandler handler = mHandlers.get(mimeType);
        if (handler != null) {
            handler.addData(builderHelper, cursor);
        }
    }

    abstract static class DataHandler {
        /** Gets the column as a string. */
        @Nullable
        protected final String getColumnString(@NonNull Cursor cursor, @NonNull String column) {
            Objects.requireNonNull(cursor);
            Objects.requireNonNull(column);

            int columnIndex = cursor.getColumnIndex(column);
            if (columnIndex == -1) {
                return null;
            }
            return cursor.getString(columnIndex);
        }

        /** Gets the column as an int. */
        protected final int getColumnInt(@NonNull Cursor cursor, @NonNull String column) {
            Objects.requireNonNull(cursor);
            Objects.requireNonNull(column);

            int columnIndex = cursor.getColumnIndex(column);
            if (columnIndex == -1) {
                return 0;
            }
            return cursor.getInt(columnIndex);
        }

        /** Adds the columns needed for the {@code DataHandler}. */
        public abstract void addNeededColumns(Collection<String> columns);

        /** Adds the data into {@link PersonBuilderHelper}. */
        public abstract void addData(@NonNull PersonBuilderHelper builderHelper, Cursor cursor);
    }

    private abstract static class SingleColumnDataHandler extends DataHandler {
        private final String mColumn;

        protected SingleColumnDataHandler(@NonNull String column) {
            Objects.requireNonNull(column);
            mColumn = column;
        }

        /** Adds the columns needed for the {@code DataHandler}. */
        @Override
        public final void addNeededColumns(@NonNull Collection<String> columns) {
            Objects.requireNonNull(columns);
            columns.add(mColumn);
        }

        /** Adds the data into {@link PersonBuilderHelper}. */
        @Override
        public final void addData(@NonNull PersonBuilderHelper builderHelper,
                @NonNull Cursor cursor) {
            Objects.requireNonNull(builderHelper);
            Objects.requireNonNull(cursor);

            String data = getColumnString(cursor, mColumn);
            if (!TextUtils.isEmpty(data)) {
                addSingleColumnStringData(builderHelper, data);
            }
        }

        protected abstract void addSingleColumnStringData(PersonBuilderHelper builderHelper,
                String data);
    }

    private abstract static class ContactPointDataHandler extends DataHandler {
        private final Resources mResources;
        private final String mDataColumn;
        private final String mTypeColumn;
        private final String mLabelColumn;

        public ContactPointDataHandler(
                @NonNull Resources resources, @NonNull String dataColumn,
                @NonNull String typeColumn, @NonNull String labelColumn) {
            mResources = Objects.requireNonNull(resources);
            mDataColumn = Objects.requireNonNull(dataColumn);
            mTypeColumn = Objects.requireNonNull(typeColumn);
            mLabelColumn = Objects.requireNonNull(labelColumn);
        }

        /** Adds the columns needed for the {@code DataHandler}. */
        @Override
        public final void addNeededColumns(@NonNull Collection<String> columns) {
            Objects.requireNonNull(columns);
            columns.add(Data._ID);
            columns.add(Data.IS_PRIMARY);
            columns.add(Data.IS_SUPER_PRIMARY);
            columns.add(mDataColumn);
            columns.add(mTypeColumn);
            columns.add(mLabelColumn);
        }

        /**
         * Adds the data for ContactsPoint(email, telephone, postal addresses) into
         * {@link Person.Builder}.
         */
        @Override
        public final void addData(@NonNull PersonBuilderHelper builderHelper,
                @NonNull Cursor cursor) {
            Objects.requireNonNull(builderHelper);
            Objects.requireNonNull(cursor);

            String data = getColumnString(cursor, mDataColumn);
            if (!TextUtils.isEmpty(data)) {
                // get the corresponding label to the type.
                int type = getColumnInt(cursor, mTypeColumn);
                String label = getTypeLabel(mResources, type,
                        getColumnString(cursor, mLabelColumn));
                addContactPointData(builderHelper, label, data);
            }
        }

        @NonNull
        protected abstract String getTypeLabel(Resources resources, int type, String label);

        /**
         * Adds the information in the {@link Person.Builder}.
         *
         * @param builderHelper a helper to build the {@link Person}.
         * @param label         the corresponding label to the {@code type} for the data.
         * @param data          data read from the designed column in the row.
         */
        protected abstract void addContactPointData(
                PersonBuilderHelper builderHelper, String label, String data);
    }

    private static final class EmailDataHandler extends ContactPointDataHandler {
        public EmailDataHandler(@NonNull Resources resources) {
            super(resources, Email.ADDRESS, Email.TYPE, Email.LABEL);
        }

        /**
         * Adds the Email information in the {@link Person.Builder}.
         *
         * @param builderHelper a builder to build the {@link Person}.
         * @param label         The corresponding label to the {@code type}. E.g. {@link
         *                      com.android.internal.R.string#emailTypeHome} to {@link
         *                      Email#TYPE_HOME} or custom label for the data if {@code type} is
         *                      {@link
         *                      Email#TYPE_CUSTOM}.
         * @param data          data read from the designed column {@code Email.ADDRESS} in the row.
         */
        @Override
        protected void addContactPointData(
                @NonNull PersonBuilderHelper builderHelper, @NonNull String label,
                @NonNull String data) {
            Objects.requireNonNull(builderHelper);
            Objects.requireNonNull(data);
            Objects.requireNonNull(label);
            builderHelper.addEmailToPerson(label, data);
        }

        @NonNull
        @Override
        protected String getTypeLabel(@NonNull Resources resources, int type,
                @Nullable String label) {
            Objects.requireNonNull(resources);
            return Email.getTypeLabel(resources, type, label).toString();
        }
    }

    private static final class PhoneHandler extends ContactPointDataHandler {
        public PhoneHandler(@NonNull Resources resources) {
            super(resources, Phone.NUMBER, Phone.TYPE, Phone.LABEL);
        }

        /**
         * Adds the phone number information in the {@link Person.Builder}.
         *
         * @param builderHelper helper to build the {@link Person}.
         * @param label         corresponding label to {@code type}. E.g. {@link
         *                      com.android.internal.R.string#phoneTypeHome} to {@link
         *                      Phone#TYPE_HOME}, or custom label for the data if {@code type} is
         *                      {@link Phone#TYPE_CUSTOM}.
         * @param data          data read from the designed column {@link Phone#NUMBER} in the row.
         */
        @Override
        protected void addContactPointData(
                @NonNull PersonBuilderHelper builderHelper, @NonNull String label,
                @NonNull String data) {
            Objects.requireNonNull(builderHelper);
            Objects.requireNonNull(data);
            Objects.requireNonNull(label);
            builderHelper.addPhoneToPerson(label, data);
        }

        @NonNull
        @Override
        protected String getTypeLabel(@NonNull Resources resources, int type,
                @Nullable String label) {
            Objects.requireNonNull(resources);
            return Phone.getTypeLabel(resources, type, label).toString();
        }
    }

    private static final class StructuredPostalHandler extends ContactPointDataHandler {
        public StructuredPostalHandler(@NonNull Resources resources) {
            super(
                    resources,
                    StructuredPostal.FORMATTED_ADDRESS,
                    StructuredPostal.TYPE,
                    StructuredPostal.LABEL);
        }

        /**
         * Adds the postal address information in the {@link Person.Builder}.
         *
         * @param builderHelper helper to build the {@link Person}.
         * @param label         corresponding label to {@code type}. E.g. {@link
         *                      com.android.internal.R.string#postalTypeHome} to {@link
         *                      StructuredPostal#TYPE_HOME}, or custom label for the data if {@code
         *                      type} is {@link StructuredPostal#TYPE_CUSTOM}.
         * @param data          data read from the designed column
         *                      {@link StructuredPostal#FORMATTED_ADDRESS} in the row.
         */
        @Override
        protected void addContactPointData(
                @NonNull PersonBuilderHelper builderHelper, @NonNull String label,
                @NonNull String data) {
            Objects.requireNonNull(builderHelper);
            Objects.requireNonNull(data);
            Objects.requireNonNull(label);
            builderHelper.addAddressToPerson(label, data);
        }

        @NonNull
        @Override
        protected String getTypeLabel(@NonNull Resources resources, int type,
                @Nullable String label) {
            Objects.requireNonNull(resources);
            return StructuredPostal.getTypeLabel(resources, type, label).toString();
        }
    }

    private static final class NicknameDataHandler extends SingleColumnDataHandler {
        public NicknameDataHandler() {
            super(Nickname.NAME);
        }

        @Override
        protected void addSingleColumnStringData(@NonNull PersonBuilderHelper builder,
                @NonNull String data) {
            Objects.requireNonNull(builder);
            Objects.requireNonNull(data);
            builder.getPersonBuilder().addAdditionalName(Person.TYPE_NICKNAME, data);
        }
    }

    private static final class StructuredNameHandler extends DataHandler {
        private static final String[] COLUMNS = {
                Data.RAW_CONTACT_ID,
                Data.NAME_RAW_CONTACT_ID,
                // Only those three fields we need to set in the builder.
                StructuredName.GIVEN_NAME,
                StructuredName.MIDDLE_NAME,
                StructuredName.FAMILY_NAME,
        };

        /** Adds the columns needed for the {@code DataHandler}. */
        @Override
        public final void addNeededColumns(Collection<String> columns) {
            Collections.addAll(columns, COLUMNS);
        }

        /** Adds the data into {@link Person.Builder}. */
        @Override
        public final void addData(@NonNull PersonBuilderHelper builderHelper, Cursor cursor) {
            Objects.requireNonNull(builderHelper);
            String rawContactId = getColumnString(cursor, Data.RAW_CONTACT_ID);
            String nameRawContactId = getColumnString(cursor, Data.NAME_RAW_CONTACT_ID);
            String givenName = getColumnString(cursor, StructuredName.GIVEN_NAME);
            String familyName = getColumnString(cursor, StructuredName.FAMILY_NAME);
            String middleName = getColumnString(cursor, StructuredName.MIDDLE_NAME);

            Person.Builder builder = builderHelper.getPersonBuilder();
            // only set given, middle and family name iff rawContactId is same as
            // nameRawContactId. In this case those three match the value for displayName in CP2.
            if (!TextUtils.isEmpty(rawContactId)
                    && !TextUtils.isEmpty(nameRawContactId)
                    && rawContactId.equals(nameRawContactId)) {
                if (givenName != null) {
                    builder.setGivenName(givenName);
                }
                if (familyName != null) {
                    builder.setFamilyName(familyName);
                }
                if (middleName != null) {
                    builder.setMiddleName(middleName);
                }
            }
        }
    }

    private static final class OrganizationDataHandler extends DataHandler {
        private static final String[] COLUMNS = {
                Organization.TITLE,
                Organization.DEPARTMENT,
                Organization.COMPANY,
        };

        private final StringBuilder mStringBuilder = new StringBuilder();

        @Override
        public void addNeededColumns(Collection<String> columns) {
            for (String column : COLUMNS) {
                columns.add(column);
            }
        }

        @Override
        public void addData(@NonNull PersonBuilderHelper builder, Cursor cursor) {
            mStringBuilder.setLength(0);
            for (String column : COLUMNS) {
                String value = getColumnString(cursor, column);
                if (!TextUtils.isEmpty(value)) {
                    if (mStringBuilder.length() != 0) {
                        mStringBuilder.append(", ");
                    }
                    mStringBuilder.append(value);
                }
            }
            if (mStringBuilder.length() > 0) {
                builder.getPersonBuilder().addAffiliation(mStringBuilder.toString());
            }
        }
    }

    private static final class RelationDataHandler extends DataHandler {
        private static final String[] COLUMNS = {
                Relation.NAME,
                Relation.TYPE,
                Relation.LABEL,
        };

        private final Resources mResources;

        public RelationDataHandler(@NonNull Resources resources) {
            mResources = resources;
        }

        @Override
        public void addNeededColumns(Collection<String> columns) {
            for (String column : COLUMNS) {
                columns.add(column);
            }
        }

        @Override
        public void addData(@NonNull PersonBuilderHelper builder, Cursor cursor) {
            String relationName = getColumnString(cursor, Relation.NAME);
            if (TextUtils.isEmpty(relationName)) {
                // Get the relation name from type. If it is a custom type, get it from
                // label.
                int type = getColumnInt(cursor, Relation.TYPE);
                String label = getColumnString(cursor, Relation.LABEL);
                relationName = Relation.getTypeLabel(mResources, type, label).toString();
                if (TextUtils.isEmpty(relationName)) {
                    return;
                }
            }
            builder.getPersonBuilder().addRelation(relationName);
        }
    }

    private static final class NoteDataHandler extends SingleColumnDataHandler {
        public NoteDataHandler() {
            super(Note.NOTE);
        }

        @Override
        protected void addSingleColumnStringData(@NonNull PersonBuilderHelper builder,
                @NonNull String data) {
            Objects.requireNonNull(builder);
            Objects.requireNonNull(data);
            builder.getPersonBuilder().setNote(data);
        }
    }
}