// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.JUnitBackports.expectThrows;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainResource;
import google.registry.model.registry.Registry;
import org.joda.time.DateTime;
import org.junit.Test;

/** Unit tests for {@link Cursor}. */
public class CursorTest extends EntityTestCase {
  @Test
  public void testSuccess_persistScopedCursor() {
    createTld("tld");
    clock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    ofy().transact(() -> ofy().save().entity(Cursor.create(RDE_UPLOAD, time, Registry.get("tld"))));
    assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get("tld"))).now()).isNull();
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_UPLOAD, Registry.get("tld")))
                .now()
                .getCursorTime())
        .isEqualTo(time);
  }

  @Test
  public void testSuccess_persistGlobalCursor() {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    ofy().transact(() -> ofy().save().entity(Cursor.createGlobal(RECURRING_BILLING, time)));
    assertThat(ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now().getCursorTime())
        .isEqualTo(time);
  }

  @Test
  public void testIndexing() throws Exception {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    ofy().transact(() -> ofy().save().entity(Cursor.createGlobal(RECURRING_BILLING, time)));
    Cursor cursor = ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now();
    verifyIndexing(cursor);
  }

  @Test
  public void testFailure_invalidScopeOnCreate() throws Exception {
    createTld("tld");
    clock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    final DomainResource domain = persistActiveDomain("notaregistry.tld");
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                ofy().transact(() -> ofy().save().entity(Cursor.create(RDE_UPLOAD, time, domain))));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  public void testFailure_invalidScopeOnKeyCreate() throws Exception {
    createTld("tld");
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () -> Cursor.createKey(RDE_UPLOAD, persistActiveDomain("notaregistry.tld")));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  public void testFailure_createGlobalKeyForScopedCursorType() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(IllegalArgumentException.class, () -> Cursor.createGlobalKey(RDE_UPLOAD));
    assertThat(thrown).hasMessageThat().contains("Cursor type is not a global cursor");
  }

  @Test
  public void testFailure_invalidScopeOnGlobalKeyCreate() throws Exception {
    createTld("tld");
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () -> Cursor.createKey(RECURRING_BILLING, persistActiveDomain("notaregistry.tld")));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  public void testFailure_nullScope() throws Exception {
    NullPointerException thrown =
        expectThrows(
            NullPointerException.class,
            () -> Cursor.create(RECURRING_BILLING, START_OF_TIME, null));
    assertThat(thrown).hasMessageThat().contains("Cursor scope cannot be null");
  }

  @Test
  public void testFailure_nullCursorType() throws Exception {
    createTld("tld");
    NullPointerException thrown =
        expectThrows(
            NullPointerException.class,
            () -> Cursor.create(null, START_OF_TIME, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor type cannot be null");
  }

  @Test
  public void testFailure_nullTime() throws Exception {
    createTld("tld");
    NullPointerException thrown =
        expectThrows(
            NullPointerException.class, () -> Cursor.create(RDE_UPLOAD, null, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor time cannot be null");
  }
}
