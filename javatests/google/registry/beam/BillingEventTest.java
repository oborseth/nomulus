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

package google.registry.beam;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.beam.BillingEvent.InvoiceGroupingKey;
import google.registry.beam.BillingEvent.InvoiceGroupingKey.InvoiceGroupingKeyCoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BillingEvent} */
@RunWith(JUnit4.class)
public class BillingEventTest {

  private static final String BILLING_EVENT_SCHEMA =
      "{\"name\": \"BillingEvent\", "
          + "\"type\": \"record\", "
          + "\"fields\": ["
          + "{\"name\": \"id\", \"type\": \"long\"},"
          + "{\"name\": \"billingTime\", \"type\": \"string\"},"
          + "{\"name\": \"eventTime\", \"type\": \"string\"},"
          + "{\"name\": \"registrarId\", \"type\": \"string\"},"
          + "{\"name\": \"billingId\", \"type\": \"long\"},"
          + "{\"name\": \"tld\", \"type\": \"string\"},"
          + "{\"name\": \"action\", \"type\": \"string\"},"
          + "{\"name\": \"domain\", \"type\": \"string\"},"
          + "{\"name\": \"repositoryId\", \"type\": \"string\"},"
          + "{\"name\": \"years\", \"type\": \"int\"},"
          + "{\"name\": \"currency\", \"type\": \"string\"},"
          + "{\"name\": \"amount\", \"type\": \"float\"},"
          + "{\"name\": \"flags\", \"type\": \"string\"}"
          + "]}";

  private SchemaAndRecord schemaAndRecord;

  @Before
  public void initializeRecord() {
    // Create a record with a given JSON schema.
    GenericRecord record = new GenericData.Record(new Schema.Parser().parse(BILLING_EVENT_SCHEMA));
    record.put("id", "1");
    record.put("billingTime", 1508835963000000L);
    record.put("eventTime", 1484870383000000L);
    record.put("registrarId", "myRegistrar");
    record.put("billingId", "12345-CRRHELLO");
    record.put("tld", "test");
    record.put("action", "RENEW");
    record.put("domain", "example.test");
    record.put("repositoryId", "123456");
    record.put("years", 5);
    record.put("currency", "USD");
    record.put("amount", 20.5);
    record.put("flags", "AUTO_RENEW SYNTHETIC");
    schemaAndRecord = new SchemaAndRecord(record, null);
  }

  @Test
  public void testParseBillingEventFromRecord_success() {
    BillingEvent event = BillingEvent.parseFromRecord(schemaAndRecord);
    assertThat(event.id()).isEqualTo(1);
    assertThat(event.billingTime())
        .isEqualTo(ZonedDateTime.of(2017, 10, 24, 9, 6, 3, 0, ZoneId.of("UTC")));
    assertThat(event.eventTime())
        .isEqualTo(ZonedDateTime.of(2017, 1, 19, 23, 59, 43, 0, ZoneId.of("UTC")));
    assertThat(event.registrarId()).isEqualTo("myRegistrar");
    assertThat(event.billingId()).isEqualTo("12345-CRRHELLO");
    assertThat(event.tld()).isEqualTo("test");
    assertThat(event.action()).isEqualTo("RENEW");
    assertThat(event.domain()).isEqualTo("example.test");
    assertThat(event.repositoryId()).isEqualTo("123456");
    assertThat(event.years()).isEqualTo(5);
    assertThat(event.currency()).isEqualTo("USD");
    assertThat(event.amount()).isEqualTo(20.5);
    assertThat(event.flags()).isEqualTo("AUTO_RENEW SYNTHETIC");
  }

  @Test
  public void testParseBillingEventFromRecord_nullValue_throwsException() {
    schemaAndRecord.getRecord().put("tld", null);
    assertThrows(IllegalStateException.class, () -> BillingEvent.parseFromRecord(schemaAndRecord));
  }

  @Test
  public void testConvertBillingEvent_toCsv() {
    BillingEvent event = BillingEvent.parseFromRecord(schemaAndRecord);
    assertThat(event.toCsv())
        .isEqualTo("1,2017-10-24 09:06:03 UTC,2017-01-19 23:59:43 UTC,myRegistrar,"
            + "12345-CRRHELLO,test,RENEW,example.test,123456,5,USD,20.50,AUTO_RENEW");
  }

  @Test
  public void testGenerateBillingEventFilename() {
    BillingEvent event = BillingEvent.parseFromRecord(schemaAndRecord);
    assertThat(event.toFilename("2017-10")).isEqualTo("invoice_details_2017-10_myRegistrar_test");
  }

  @Test
  public void testGetInvoiceGroupingKey_fromBillingEvent() {
    BillingEvent event = BillingEvent.parseFromRecord(schemaAndRecord);
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.startDate()).isEqualTo("2017-10-01");
    assertThat(invoiceKey.endDate()).isEqualTo("2022-09-30");
    assertThat(invoiceKey.productAccountKey()).isEqualTo("12345-CRRHELLO");
    assertThat(invoiceKey.usageGroupingKey()).isEqualTo("myRegistrar - test");
    assertThat(invoiceKey.description()).isEqualTo("RENEW | TLD: test | TERM: 5-year");
    assertThat(invoiceKey.unitPrice()).isEqualTo(20.5);
    assertThat(invoiceKey.unitPriceCurrency()).isEqualTo("USD");
    assertThat(invoiceKey.poNumber()).isEmpty();
  }

  @Test
  public void testConvertInvoiceGroupingKey_toCsv() {
    BillingEvent event = BillingEvent.parseFromRecord(schemaAndRecord);
    InvoiceGroupingKey invoiceKey = event.getInvoiceGroupingKey();
    assertThat(invoiceKey.toCsv(3L))
        .isEqualTo(
            "2017-10-01,2022-09-30,12345-CRRHELLO,61.50,USD,10125,1,PURCHASE,"
                + "myRegistrar - test,3,RENEW | TLD: test | TERM: 5-year,20.50,USD,");
  }

  @Test
  public void testInvoiceGroupingKeyCoder_deterministicSerialization() throws IOException {
    InvoiceGroupingKey invoiceKey =
        BillingEvent.parseFromRecord(schemaAndRecord).getInvoiceGroupingKey();
    InvoiceGroupingKeyCoder coder = new InvoiceGroupingKeyCoder();
    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    coder.encode(invoiceKey, outStream);
    InputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
    assertThat(coder.decode(inStream)).isEqualTo(invoiceKey);
  }

  @Test
  public void testGetDetailReportHeader() {
    assertThat(BillingEvent.getHeader())
        .isEqualTo(
            "id,billingTime,eventTime,registrarId,billingId,tld,action,"
                + "domain,repositoryId,years,currency,amount,flags");
  }

  @Test
  public void testGetOverallInvoiceHeader() {
    assertThat(InvoiceGroupingKey.invoiceHeader())
        .isEqualTo("StartDate,EndDate,ProductAccountKey,Amount,AmountCurrency,BillingProductCode,"
            + "SalesChannel,LineItemType,UsageGroupingKey,Quantity,Description,UnitPrice,"
            + "UnitPriceCurrency,PONumber");
  }
}
