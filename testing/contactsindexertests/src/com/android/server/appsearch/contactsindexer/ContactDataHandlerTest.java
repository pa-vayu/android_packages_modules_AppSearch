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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.ContentValues;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Data;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import org.junit.Before;
import org.junit.Test;

public class ContactDataHandlerTest {
    private static final String TEST_NAMESPACE = "TESTNAMESPACE";
    private static final String TEST_ID = "TESTID";

    private ContactDataHandler mContactDataHandler;
    private Resources mResources;

    // Make a MatrixCursor based on ContentValues and return it.
    private static Cursor makeCursorFromContentValues(ContentValues values) {
        MatrixCursor cursor = new MatrixCursor(values.keySet().toArray(new String[0]));
        MatrixCursor.RowBuilder builder = cursor.newRow();
        for (String key : values.keySet()) {
            builder.add(key, values.get(key));
        }
        return cursor;
    }

    // Read from a single-line cursor and populate the data into the builderHelper.
    private void convertRowToPerson(Cursor cursor, PersonBuilderHelper builderHelper) {
        if (cursor != null) {
            try {
                assertThat(cursor.getCount()).isEqualTo(1);
                assertThat(cursor.moveToFirst()).isTrue();
                mContactDataHandler.convertCursorToPerson(cursor, builderHelper);
            } finally {
                cursor.close();
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mResources = ApplicationProvider.getApplicationContext().getResources();
        mContactDataHandler =
                new ContactDataHandler(mResources);
    }

    @Test
    public void testConvertCurrentRowToPersonWhenCursorNotSet_expectException() {
        PersonBuilderHelper builderHelper = new PersonBuilderHelper(
                new Person.Builder("namespace", "id", "name"));
        assertThrows(NullPointerException.class, () ->
                mContactDataHandler.convertCursorToPerson(/*cursor=*/ null, builderHelper));
    }

    @Test
    public void testConvertCurrentRowToPerson_labelCustom_typeCustom() {
        int type = 0; // Custom
        String name = "name";
        String address = "emailAddress@google.com";
        String label = "CustomLabel";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Email.ADDRESS, address);
        values.put(CommonDataKinds.Email.TYPE, type);
        values.put(CommonDataKinds.Email.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);
        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addEmailToPerson(label, address).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        // Since the type is 1(Homes), we won't use user provided label. So it is fine to be null.
        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(label);
        assertThat(contactPoints[0].getEmails()).asList().containsExactly(address);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_labelIsNull_typeCustom() {
        int type = 0; // Custom
        String name = "name";
        String address = "emailAddress@google.com";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Email.ADDRESS, address);
        values.put(CommonDataKinds.Email.TYPE, type);
        // label is not set in the values
        Cursor cursor = makeCursorFromContentValues(values);
        // default value for custom label if null is provided by user.
        String expectedLabel = "Custom";
        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addEmailToPerson(expectedLabel, address).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        // Since the type is 1(Homes), we won't use user provided label. So it is fine to be null.
        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(expectedLabel);
        assertThat(contactPoints[0].getEmails()).asList().containsExactly(address);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_labelIsNull_typeHome() {
        int type = 1; // Home
        String name = "name";
        String address = "emailAddress@google.com";
        String label = "Home";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Email.ADDRESS, address);
        values.put(CommonDataKinds.Email.TYPE, type);
        // label is not set in the values
        Cursor cursor = makeCursorFromContentValues(values);
        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addEmailToPerson(label, address).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        // Since the type is 1(Homes), we won't use user provided label. So it is fine to be null.
        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(label);
        assertThat(contactPoints[0].getEmails()).asList().containsExactly(address);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_email() {
        int type = 1; // Home
        String name = "name";
        String address = "emailAddress@google.com";
        String label = "Home";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Email.ADDRESS, address);
        values.put(CommonDataKinds.Email.TYPE, type);
        values.put(CommonDataKinds.Email.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);
        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addEmailToPerson(label, address).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(label);
        assertThat(contactPoints[0].getEmails()).asList().containsExactly(address);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_nickName() {
        String name = "name";
        String nick = "nickName";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Nickname.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Nickname.NAME, nick);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new Person.Builder(TEST_NAMESPACE, TEST_ID, name)
                .setCreationTimestampMillis(0)
                .addAdditionalName(nick)
                .build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        assertThat(personTested.getAdditionalNames()).asList().containsExactly(nick);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_note() {
        String name = "name";
        String note = "note";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Note.NOTE, note);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new Person.Builder(TEST_NAMESPACE, TEST_ID, name)
                .setCreationTimestampMillis(0)
                .setNote(note)
                .build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        assertThat(personTested.getNote()).isEqualTo(note);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_phone() {
        String name = "name";
        String number = "phoneNumber";
        int type = 1; // Home
        String label = "Home";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Phone.NUMBER, number);
        values.put(CommonDataKinds.Phone.TYPE, type);
        values.put(CommonDataKinds.Phone.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addPhoneToPerson(
                CommonDataKinds.Phone.getTypeLabel(mResources, type, label).toString(),
                number).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(label);
        assertThat(contactPoints[0].getPhones()).asList().containsExactly(number);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_postal() {
        String name = "name";
        int type = 1; // Home
        String postal = "structuredPostalFormattedAddress";
        String label = "Home";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, postal);
        values.put(CommonDataKinds.StructuredPostal.TYPE, type);
        values.put(CommonDataKinds.StructuredPostal.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).addAddressToPerson(
                CommonDataKinds.StructuredPostal.getTypeLabel(mResources, type, label)
                        .toString(), postal).buildPerson();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        ContactPoint[] contactPoints = personTested.getContactPoints();
        assertThat(contactPoints.length).isEqualTo(1);
        assertThat(contactPoints[0].getLabel()).isEqualTo(label);
        assertThat(contactPoints[0].getAddresses()).asList().containsExactly(postal);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_name() {
        String name = "name";
        String rawContactId = "raw" + TEST_ID;
        String givenName = "structuredNameGivenName" + TEST_ID;
        String middleName = "structuredNameMiddleName" + TEST_ID;
        String familyName = "structuredNameFamilyName" + TEST_ID;
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
        values.put(Data.RAW_CONTACT_ID, rawContactId);
        values.put(Data.NAME_RAW_CONTACT_ID, rawContactId);
        values.put(CommonDataKinds.StructuredName.GIVEN_NAME, givenName);
        values.put(CommonDataKinds.StructuredName.MIDDLE_NAME, middleName);
        values.put(CommonDataKinds.StructuredName.FAMILY_NAME, familyName);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new Person.Builder(TEST_NAMESPACE, TEST_ID, name)
                .setCreationTimestampMillis(0)
                .setGivenName(givenName)
                .setMiddleName(middleName)
                .setFamilyName(familyName)
                .build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        assertThat(personTested.getGivenName()).isEqualTo(givenName);
        assertThat(personTested.getMiddleName()).isEqualTo(middleName);
        assertThat(personTested.getFamilyName()).isEqualTo(familyName);
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_organization() {
        String name = "name";
        String company = "company";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Organization.COMPANY, company);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addAffiliation(company).build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_organizationWithJobTitle() {
        String name = "name";
        String title = "Software Engineer";
        String company = "Google Inc";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Organization.TITLE, title);
        values.put(CommonDataKinds.Organization.COMPANY, company);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addAffiliation(
                "Software Engineer, Google Inc").build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_relation_nameNotNull() {
        String name = "name";
        String relationName = "relationName";
        int type = CommonDataKinds.Relation.TYPE_BROTHER;
        String label = null;
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Relation.NAME, relationName);
        values.put(CommonDataKinds.Relation.TYPE, type);
        values.put(CommonDataKinds.Relation.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addRelation(relationName).build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_relation_nameNull_typeNotCustom() {
        String name = "name";
        String relationName = null;
        int type = CommonDataKinds.Relation.TYPE_BROTHER;
        String label = null;
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Relation.NAME, relationName);
        values.put(CommonDataKinds.Relation.TYPE, type);
        values.put(CommonDataKinds.Relation.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addRelation(
                CommonDataKinds.Relation.getTypeLabel(mResources, type, label).toString()).build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_relation_nameNull_typeCustom_labelNull() {
        String name = "name";
        String relationName = null;
        int type = CommonDataKinds.Relation.TYPE_CUSTOM;
        String label = null;
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Relation.NAME, relationName);
        values.put(CommonDataKinds.Relation.TYPE, type);
        values.put(CommonDataKinds.Relation.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addRelation(
                CommonDataKinds.Relation.getTypeLabel(mResources, type, label).toString()).build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_relation_nameNull_typeCustom_labelNotNull() {
        String name = "name";
        String relationName = null;
        int type = CommonDataKinds.Relation.TYPE_CUSTOM;
        String label = "CustomLabel";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Relation.NAME, relationName);
        values.put(CommonDataKinds.Relation.TYPE, type);
        values.put(CommonDataKinds.Relation.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        Person personExpected = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID, name).setCreationTimestampMillis(
                        0)).getPersonBuilder().addRelation(label).build();

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testHandleCurrentRowWithUnknownMimeType() {
        // Change the Mimetype of StructuredName.
        String name = "name";
        MatrixCursor cursor = new MatrixCursor(
                new String[]{Data.MIMETYPE, CommonDataKinds.StructuredName.GIVEN_NAME});
        cursor.newRow().add("testUnknownMimeType", "testGivenName");

        Person personExpected = new Person.Builder(TEST_NAMESPACE, TEST_ID,
                name).setCreationTimestampMillis(0).build();
        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);
        Person personTested = helperTested.buildPerson();

        // Couldn't read values correctly from an unknown mime type.
        assertThat(personTested.getGivenName()).isNull();
        TestUtils.assertEquals(personTested, personExpected);
    }

    @Test
    public void testConvertCurrentRowToPerson_nullEmailAddress_noExceptionThrown() {
        int type = 0; // Custom
        String name = "name";
        String label = "CustomLabel";
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.Email.ADDRESS, /*address=*/ (String) null);
        values.put(CommonDataKinds.Email.TYPE, type);
        values.put(CommonDataKinds.Email.LABEL, label);
        Cursor cursor = makeCursorFromContentValues(values);

        PersonBuilderHelper helperTested = new PersonBuilderHelper(
                new Person.Builder(TEST_NAMESPACE, TEST_ID,
                        name).setCreationTimestampMillis(0));
        convertRowToPerson(cursor, helperTested);

        // no NPE thrown.
    }
}