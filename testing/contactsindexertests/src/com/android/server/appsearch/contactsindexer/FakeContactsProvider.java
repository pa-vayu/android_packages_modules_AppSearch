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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.DeletedContacts;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fake Contacts Provider. It provides mContactsExisted existed contacts with ids
 * [1..mContactsExisted]. And the deleted ones are [mContactsEixsted + 1..mContancatsTotal]. Each
 * existed contacts' information is "hard-coded". If id is even it has no other data. Otherwise if
 * id is odd it has 2 emails, 2 nicknames, ...etc.
 */
public class FakeContactsProvider extends ContentProvider {
    static final String TAG = "ContactsIndexerFakeContactsProvider";
    static final int NUM_EXISTED_CONTACTS = 100;
    static final int NUM_TOTAL_CONTACTS = 200;

    public static final String AUTHORITY = "com.android.contacts";

    private static final String CONTACTS_DATA_ORDER_BY =
            Data.CONTACT_ID
                    + ","
                    + Data.IS_SUPER_PRIMARY
                    + " DESC"
                    + ","
                    + Data.IS_PRIMARY
                    + " DESC"
                    + ","
                    + Data.RAW_CONTACT_ID;

    public static final int[] EMAIL_TYPES = {
            Email.TYPE_CUSTOM, Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_OTHER,
            Email.TYPE_MOBILE,
    };
    public static final int[] PHONE_TYPES = {
            Phone.TYPE_CUSTOM,
            Phone.TYPE_HOME,
            Phone.TYPE_MOBILE,
            Phone.TYPE_WORK,
            Phone.TYPE_FAX_WORK,
            Phone.TYPE_FAX_HOME,
            Phone.TYPE_PAGER,
            Phone.TYPE_OTHER,
            Phone.TYPE_CALLBACK,
            Phone.TYPE_CAR,
            Phone.TYPE_COMPANY_MAIN,
            Phone.TYPE_OTHER_FAX,
            Phone.TYPE_RADIO,
            Phone.TYPE_TELEX,
            Phone.TYPE_TTY_TDD,
            Phone.TYPE_WORK_MOBILE,
            Phone.TYPE_WORK_PAGER,
            Phone.TYPE_ASSISTANT,
            Phone.TYPE_MMS
    };
    public static final int[] STRUCTURED_POSTAL_TYPES = {
            StructuredPostal.TYPE_CUSTOM,
            StructuredPostal.TYPE_HOME,
            StructuredPostal.TYPE_WORK,
            StructuredPostal.TYPE_OTHER
    };
    private static final int CONTACTS = 1;
    private static final int DELETED_CONTACTS = 2;
    private static final int DATA = 3;
    private static final int PHONE = 4;
    private static final int EMAIL = 5;
    private static final int POSTAL = 6;
    private static final String[] CONTACTS_COLUMNS = {
            Contacts._ID, Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
    };
    private static final String[] CONTACTS_DELETED_COLUMNS = {
            DeletedContacts.CONTACT_ID, DeletedContacts.CONTACT_DELETED_TIMESTAMP
    };
    private static final int VERY_IMPORTANT_SCORE = 3;
    private static final int IMPORTANT_SCORE = 2;
    private static final int ORDINARY_SCORE = 1;

    // Contacts with id in [1..mContactsExisted] are treated as existed.
    private final int mContactsExisted;
    // Contacts with id in [mContactsExisted + 1..mContactsTotal] are treated as deleted.
    private final int mContactsTotal;
    private final Person[] mAllContactData;
    private final Resources mResources;
    private final UriMatcher mURIMatcher;

    // Only odd contactIds should have additional data.
    private static boolean shouldhaveAdditionalData(long contactId) {
        return (contactId & 1) > 0;
    }

    // Use id's second least significant bit to make a fake isSuperPrimary field.
    private static int calculateIsSuperPrimary(long contactId) {
        return ((contactId & 1) > 0) ? 1 : 0;
    }

    // Use id's second least significant bit to make a fake isPrimary field.
    private static int calculateIsPrimary(long contactId) {
        return (((contactId >> 1) & 1) > 0) ? 1 : 0;
    }

    // Add fake email information into the ContentValues.
    private static void addEmail(long contactId, ContentValues values) {
        values.put(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
        values.put(Data._ID, contactId);
        values.put(Data.IS_PRIMARY, calculateIsPrimary(contactId));
        values.put(Data.IS_SUPER_PRIMARY, calculateIsSuperPrimary(contactId));
        values.put(Email.ADDRESS, String.format("emailAddress%d@google.com", contactId));
        values.put(Email.TYPE, EMAIL_TYPES[(int) (contactId % EMAIL_TYPES.length)]);
        values.put(Email.LABEL, String.format("emailLabel%d", contactId));
    }

    // Add fake nickname into the ContentValues
    private static void addNickname(long contactId, ContentValues values) {
        values.put(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE);
        values.put(Nickname.NAME, String.format("nicknameName%d", contactId));
        values.put(Nickname.NAME, String.format("nicknameName%d", contactId));
    }

    // Add fake phone information into the ContentValues.
    private static void addPhone(long contactId, ContentValues values) {
        values.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
        values.put(Data._ID, contactId);
        values.put(Data.IS_PRIMARY, calculateIsPrimary(contactId));
        values.put(Data.IS_SUPER_PRIMARY, calculateIsSuperPrimary(contactId));
        values.put(Phone.NUMBER, String.format("phoneNumber%d", contactId));
        values.put(Phone.TYPE, PHONE_TYPES[(int) (contactId % PHONE_TYPES.length)]);
        values.put(Phone.LABEL, String.format("phoneLabel%d", contactId));
    }

    // Add fake postal information into ContentValues.
    private static void addStructuredPostal(long contactId, ContentValues values) {
        values.put(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE);
        values.put(Data._ID, contactId);
        values.put(Data.IS_PRIMARY, calculateIsPrimary(contactId));
        values.put(Data.IS_SUPER_PRIMARY, calculateIsSuperPrimary(contactId));
        values.put(
                StructuredPostal.FORMATTED_ADDRESS,
                String.format("structuredPostalFormattedAddress%d", contactId));
        values.put(
                StructuredPostal.TYPE,
                STRUCTURED_POSTAL_TYPES[(int) (contactId % STRUCTURED_POSTAL_TYPES.length)]);
        values.put(StructuredPostal.LABEL, String.format("structuredPostalLabel%d", contactId));
    }

    // Add fake raw contact information for the data
    private void addRawContactInfo(long rawContactsId, long nameRawContactsId,
            ContentValues values) {
        values.put(Data.RAW_CONTACT_ID, String.valueOf(rawContactsId));
        values.put(Data.NAME_RAW_CONTACT_ID, String.valueOf(nameRawContactsId));
    }

    // Add fake given and family name into ContentValues.
    private void addStructuredName(long contactId, ContentValues values) {
        values.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        values.put(StructuredName.GIVEN_NAME,
                String.format("structuredNameGivenName%d", contactId));
        values.put(
                StructuredName.MIDDLE_NAME,
                String.format("structuredNameMiddleName%d", contactId));
        values.put(
                StructuredName.FAMILY_NAME,
                String.format("structuredNameFamilyName%d", contactId));
    }

    // Add fake contact's basic information into ContentValues.
    private void addContactBasic(long i, ContentValues values) {
        values.put(Data.CONTACT_ID, i);
        values.put(Data.LOOKUP_KEY, String.format("lookupUri%d", i));
        values.put(Data.PHOTO_THUMBNAIL_URI, String.format("http://photoThumbNailUri%d.com", i));
        values.put(Data.DISPLAY_NAME_PRIMARY, String.format("displayName%d", i));
        values.put(Data.PHONETIC_NAME, String.format("phoneticName%d", i));
        values.put(Data.RAW_CONTACT_ID, i);
        // Set last updated timestamp as i so we could handle selection easily.
        values.put(Data.CONTACT_LAST_UPDATED_TIMESTAMP, i);
        values.put(Data.STARRED, i & 1);
    }

    private void addRowToCursorFromContentValues(
            ContentValues values, MatrixCursor cursor, String[] projection) {
        MatrixCursor.RowBuilder builder = cursor.newRow();
        for (int i = 0; i < projection.length; ++i) {
            builder.add(values.getAsString(projection[i]));
        }
    }

    private void addEmailToBuilder(PersonBuilderHelper builderHelper, long contactId) {
        int type = EMAIL_TYPES[(int) (contactId % EMAIL_TYPES.length)];
        builderHelper.addEmailToPerson(
                Email.getTypeLabel(mResources, type,
                        String.format("emailLabel%d", contactId)).toString(),
                String.format("emailAddress%d@google.com", contactId));
    }

    private void addNicknameToBuilder(PersonBuilderHelper builderHelper, long contactId) {
        builderHelper.getPersonBuilder().addAdditionalName(
                String.format("nicknameName%d", contactId));
    }

    private void addPhoneToBuilder(PersonBuilderHelper builderHelper, long contactId) {
        int type = PHONE_TYPES[(int) (contactId % PHONE_TYPES.length)];
        builderHelper.addPhoneToPerson(
                Phone.getTypeLabel(mResources, type,
                        String.format("phoneLabel%d", contactId)).toString(),
                String.format("phoneNumber%d", contactId));
    }

    private void addStructuredPostalToBuilder(PersonBuilderHelper builderHelper,
            long contactId) {
        int type = STRUCTURED_POSTAL_TYPES[(int) (contactId % STRUCTURED_POSTAL_TYPES.length)];
        builderHelper.addAddressToPerson(
                StructuredPostal.getTypeLabel(
                        mResources, type, String.format("structuredPostalLabel%d", contactId))
                        .toString(),
                String.format("structuredPostalFormattedAddress%d", contactId));
    }

    private static void addStructuredNameToBuilder(PersonBuilderHelper builderHelper,
            long contactId) {
        if (shouldhaveAdditionalData(contactId)) {
            builderHelper.getPersonBuilder()
                    .setGivenName(String.format("structuredNameGivenName%d", contactId));
            builderHelper.getPersonBuilder()
                    .setMiddleName(String.format("structuredNameMiddleName%d", contactId));
            builderHelper.getPersonBuilder()
                    .setFamilyName(String.format("structuredNameFamilyName%d", contactId));
        }
    }

    public FakeContactsProvider() {
        this(ApplicationProvider.getApplicationContext().getResources(), NUM_EXISTED_CONTACTS,
                NUM_TOTAL_CONTACTS);
    }

    FakeContactsProvider(Resources resources, int contactsExisted, int contactsTotal) {
        mContactsExisted = contactsExisted;
        mContactsTotal = contactsTotal;
        mResources = resources;
        mAllContactData = new Person[mContactsExisted];
        mURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mURIMatcher.addURI(AUTHORITY, "contacts", CONTACTS);
        mURIMatcher.addURI(AUTHORITY, "deleted_contacts", DELETED_CONTACTS);
        mURIMatcher.addURI(AUTHORITY, "data", DATA);
        mURIMatcher.addURI(AUTHORITY, "data/phones", PHONE);
        mURIMatcher.addURI(AUTHORITY, "data/emails", EMAIL);
        mURIMatcher.addURI(AUTHORITY, "data/postals", PHONE);
        for (long i = 1; i <= mContactsExisted; ++i) {
            Person.Builder builder = new Person.Builder(AppSearchHelper.NAMESPACE_NAME,
                    /*contactId=*/ String.valueOf(i), /*name=*/ String.format("displayName%d", i));
            String lookUpKey = String.format("lookupUri%d", i);
            builder.setExternalUri(ContactsContract.Contacts.getLookupUri(i, lookUpKey));
            builder.setImageUri(Uri.parse(String.format("http://photoThumbNailUri%d.com", i)));
            builder.setScore((int) i + 1);
            builder.setCreationTimestampMillis(i);
            builder.setIsImportant((i & 1) != 0);
            PersonBuilderHelper builderHelper = new PersonBuilderHelper(builder);
            if (shouldhaveAdditionalData(i)) {
                // Same contact with Nickname.
                addNicknameToBuilder(builderHelper, i);
                addNicknameToBuilder(builderHelper, i + 1);
                // Same contact with email.
                addEmailToBuilder(builderHelper, i);
                addEmailToBuilder(builderHelper, i + 1);
                // Same contact with Phone.
                addPhoneToBuilder(builderHelper, i);
                addPhoneToBuilder(builderHelper, i + 1);
                // Same contact with StructuredPostal.
                addStructuredPostalToBuilder(builderHelper, i);
                addStructuredPostalToBuilder(builderHelper, i + 1);
                // Same contact with StructuredName.
                addStructuredNameToBuilder(builderHelper, i);
                addStructuredNameToBuilder(builderHelper, i + 1);
            }
            mAllContactData[(int) (i - 1)] = builderHelper.buildPerson();
        }
    }

    // Parse the selection String which may be "IN (idlist)" or null for all ids.
    private List<Long> parseList(String selection) {
        int left = -1;
        int right = -1;
        List<Long> selectionIds = new ArrayList<>();
        if ((selection != null) && (selection.contains("IN"))) {
            left = selection.indexOf('(');
            right = selection.indexOf(')');
        }
        if ((left >= 0) && (right > left)) {
            // Read ids in the list. Ignore exceptions. Note that the list may be empty.
            String[] ids = selection.substring(left + 1, right).split(",");
            for (String i : ids) {
                try {
                    Long id = Long.valueOf(i);
                    if ((id >= 1) && (id <= mContactsExisted)) {
                        selectionIds.add(id);
                    }
                } catch (NumberFormatException e) {
                    // Do nothing.
                }
            }
        } else { // all ids
            for (long i = 1L; i <= mContactsExisted; ++i) {
                selectionIds.add(i);
            }
        }
        return selectionIds;
    }

    // Add all ids (and LAST_UPDATED_TIMESTAMP) into cursor.
    private void insertRowsIntoIdCursor(MatrixCursor cursor, List<Long> selectionIds) {
        for (long i : selectionIds) {
            // Add id, and last_updated_timestamp, which has same value as id, into the cursor.
            cursor.newRow().add(i).add(i);
        }
    }

    // Query contact ids and their CONTACT_LAST_UPDATED_TIMESTAMP.
    // Projection is id and update time_stamp.
    // Selection can be a list of ids or null for all ids.
    protected Cursor manageContactsQuery(
            String[] projection, String selection, String[] selectionArgs, String orderBy) {
        MatrixCursor cursor = null;
        List<Long> selectionIds;
        if (Arrays.equals(CONTACTS_COLUMNS, projection)) {
            cursor = new MatrixCursor(projection);
            if ((selection != null)
                    && selection.startsWith(Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + ">")) {
                long since;
                try {
                    if (selectionArgs != null && selectionArgs.length == 1) {
                        since = Long.parseLong(selectionArgs[0]);
                    } else {
                        since = 0;
                    }
                } catch (NumberFormatException e) {
                    since = 0;
                }
                selectionIds = new ArrayList<>();
                for (long i = Math.max(0, since) + 1; i <= mContactsExisted; ++i) {
                    selectionIds.add(i);
                }
            } else {
                selectionIds = parseList(selection);
            }
            // Query contact ids and update timestamp.
            insertRowsIntoIdCursor(cursor, selectionIds);
        }
        return cursor;
    }

    // Query contacts whose delete_timestamp is larger than given parameter.
    // Only support selection = "delete_timestamp >?" here. selectionArgs should have one element
    // - integer that replaces the '?' place holder in given selection.
    // replaces the placeholder.
    protected Cursor manageDeleteQuery(
            String[] projection, String selection, String[] selectionArgs, String orderBy) {
        MatrixCursor cursor = null;
        if (Arrays.equals(CONTACTS_DELETED_COLUMNS, projection)
                && (DeletedContacts.CONTACT_DELETED_TIMESTAMP + ">?").equals(selection)
                && (selectionArgs != null)
                && (selectionArgs.length == 1)) {
            // Query delete_id and timestamp with timestamp larger than "since".
            cursor = new MatrixCursor(projection);
            long since = Long.parseLong(selectionArgs[0]);
            for (long i = Math.max(since, mContactsExisted) + 1; i <= mContactsTotal; ++i) {
                cursor.newRow().add(i).add(i);
            }
        }
        return cursor;
    }

    // Query all details for given contact ids.  Selection will be a list of ids or null. Since we
    // Save the phone, email, postal etc. information separately, a single contact id may have
    // multiply records. So the orderBy should group the information of the same contact id
    // together. We only process CONTACTS_DATA_ORDER_BY.
    protected Cursor manageDataQuery(
            String[] projection, String selection, String[] selectionArgs, String orderBy) {
        MatrixCursor cursor = null;
        // Details in id list.
        if (CONTACTS_DATA_ORDER_BY.equals(orderBy) && (projection != null)) {
            cursor = new MatrixCursor(projection);
            List<Long> selectionIds = parseList(selection);
            Collections.sort(selectionIds);
            ContentValues values = new ContentValues();
            for (long i : selectionIds) {
                if ((i & 1) != 0) {
                    // Single contact.
                    values.clear();
                    addContactBasic(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with email.
                    values.clear();
                    addContactBasic(i, values);
                    addEmail(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with email.
                    values.clear();
                    addContactBasic(i, values);
                    addEmail(i + 1, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with Nickname.
                    values.clear();
                    addContactBasic(i, values);
                    addNickname(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with Nickname.
                    values.clear();
                    addContactBasic(i, values);
                    addNickname(i + 1, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with Phone.
                    values.clear();
                    addContactBasic(i, values);
                    addPhone(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with Phone.
                    values.clear();
                    addContactBasic(i, values);
                    addPhone(i + 1, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with StructuredPostal.
                    values.clear();
                    addContactBasic(i, values);
                    addStructuredPostal(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with StructuredPostal.
                    values.clear();
                    addContactBasic(i, values);
                    addStructuredPostal(i + 1, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with StructuredName.
                    values.clear();
                    addContactBasic(i, values);
                    addRawContactInfo(/*rawContactsId=*/ i, /*nameRawContactsId=*/ i, values);
                    addStructuredName(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                    // Same contact with StructuredName
                    values.clear();
                    addContactBasic(i, values);
                    addRawContactInfo(/*rawContactsId=*/ i + 1, /*nameRawContactsId=*/ i, values);
                    // given and family name will be picked up since rawContactsId is same as
                    // nameRawContactsId
                    // for the current row.
                    addStructuredName(i + 1, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                } else {
                    // Single contact.
                    values.clear();
                    addContactBasic(i, values);
                    addRowToCursorFromContentValues(values, cursor, projection);
                }
            }
        }
        return cursor;
    }

    private static Cursor makeCountCursor(int count) {
        Cursor cursor = mock(Cursor.class);
        doReturn(count).when(cursor).getCount();
        return cursor;
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs,
            String orderBy) {
        Log.i(TAG, "uri = " + uri);
        Cursor cursor = null;
        switch (mURIMatcher.match(uri)) {
            case CONTACTS:
                Log.i(TAG, "contacts uri");
                cursor = manageContactsQuery(projection, selection, selectionArgs, orderBy);
                break;
            case DELETED_CONTACTS:
                Log.i(TAG, "delete_contacts uri");
                cursor = manageDeleteQuery(projection, selection, selectionArgs, orderBy);
                break;
            case DATA:
                Log.i(TAG, "data uri");
                cursor = manageDataQuery(projection, selection, selectionArgs, orderBy);
                break;
            case PHONE:
                Log.i(TAG, "phone uri");
                if (projection != null
                        && projection.length == 1
                        && "_id".equals(projection[0])
                        && selection == null
                        && selectionArgs == null
                        && orderBy == null) {
                    cursor = makeCountCursor(((mContactsExisted + 1) >> 1) << 1);
                }
                break;
            case EMAIL:
                Log.i(TAG, "email uri");
                if (projection != null
                        && projection.length == 1
                        && "_id".equals(projection[0])
                        && selection == null
                        && selectionArgs == null
                        && orderBy == null) {
                    cursor = makeCountCursor(((mContactsExisted + 1) >> 1) << 1);
                }
                break;
            case POSTAL:
                Log.i(TAG, "postal uri");
                if (projection != null
                        && projection.length == 1
                        && "_id".equals(projection[0])
                        && selection == null
                        && selectionArgs == null
                        && orderBy == null) {
                    cursor = makeCountCursor(((mContactsExisted + 1) >> 1) << 1);
                }
                break;

            default:
                Log.e(TAG, "unknown uri");
                break;
        }
        return cursor;
    }

    Person getContactData(long id) {
        return ((id <= 0) || (id > mAllContactData.length)) ? null
                : mAllContactData[(int) (id - 1)];
    }

    Person[] getAllContactData() {
        return mAllContactData;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete not supported");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("delete not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update not supported");
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("update not supported");
    }
}