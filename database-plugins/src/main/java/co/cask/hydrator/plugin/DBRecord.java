/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin;

import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import com.google.common.base.Preconditions;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import java.io.BufferedReader;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import javax.annotation.Nullable;
import javax.sql.rowset.serial.SerialBlob;

/**
 * Writable class for DB Source/Sink
 *
 * @see org.apache.hadoop.mapreduce.lib.db.DBInputFormat DBInputFormat
 * @see org.apache.hadoop.mapreduce.lib.db.DBOutputFormat DBOutputFormat
 * @see DBWritable DBWritable
 */
public class DBRecord implements Writable, DBWritable {

  private StructuredRecord record;
  /**
   * Need to cache {@link ResultSetMetaData} of the record for use during writing to a table.
   * This is because we cannot rely on JDBC drivers to properly set metadata in the {@link PreparedStatement}
   * passed to the #write method in this class.
   */
  private int [] columnTypes;

  /**
   * Used to construct a DBRecord from a StructuredRecord in the ETL Pipeline
   *
   * @param record the {@link StructuredRecord} to construct the {@link DBRecord} from
   */
  public DBRecord(StructuredRecord record, int [] columnTypes) {
    this.record = record;
    this.columnTypes = columnTypes;
  }

  /**
   * Used in map-reduce. Do not remove.
   */
  @SuppressWarnings("unused")
  public DBRecord() {
  }

  public void readFields(DataInput in) throws IOException {
    // no-op, since we may never need to support a scenario where you read a DBRecord from a non-RDBMS source
  }

  /**
   * @return the {@link StructuredRecord} contained in this object
   */
  public StructuredRecord getRecord() {
    return record;
  }

  /**
   * Builds the {@link #record} using the specified {@link ResultSet}
   *
   * @param resultSet the {@link ResultSet} to build the {@link StructuredRecord} from
   */
  public void readFields(ResultSet resultSet) throws SQLException {
    ResultSetMetaData metadata = resultSet.getMetaData();
    List<Schema.Field> schemaFields = DBUtils.getSchemaFields(resultSet);
    Schema schema = Schema.recordOf("dbRecord", schemaFields);
    StructuredRecord.Builder recordBuilder = StructuredRecord.builder(schema);
    for (int i = 0; i < schemaFields.size(); i++) {
      Schema.Field field = schemaFields.get(i);
      int sqlColumnType = metadata.getColumnType(i + 1);
      recordBuilder.set(field.getName(), transformValue(sqlColumnType, resultSet.getObject(field.getName())));
    }
    record = recordBuilder.build();
  }

  public void write(DataOutput out) throws IOException {
    Schema recordSchema = record.getSchema();
    List<Schema.Field> schemaFields = recordSchema.getFields();
    for (Schema.Field field : schemaFields) {
      String fieldName = field.getName();
      Schema.Type fieldType = getNonNullableType(field);
      Object fieldValue = record.get(fieldName);
      writeToDataOut(out, fieldType, fieldValue);
    }
  }

  /**
   * Writes the {@link #record} to the specified {@link PreparedStatement}
   *
   * @param stmt the {@link PreparedStatement} to write the {@link StructuredRecord} to
   */
  public void write(PreparedStatement stmt) throws SQLException {
    Schema recordSchema = record.getSchema();
    List<Schema.Field> schemaFields = recordSchema.getFields();
    for (int i = 0; i < schemaFields.size(); i++) {
      Schema.Field field = schemaFields.get(i);
      String fieldName = field.getName();
      Schema.Type fieldType = getNonNullableType(field);
      Object fieldValue = record.get(fieldName);
      writeToDB(stmt, fieldType, fieldValue, i);
    }
  }

  private Schema.Type getNonNullableType(Schema.Field field) {
    Schema.Type type;
    if (field.getSchema().isNullable()) {
      type = field.getSchema().getNonNullable().getType();
    } else {
      type = field.getSchema().getType();
    }
    Preconditions.checkArgument(type.isSimpleType(),
                                "Only simple types are supported (boolean, int, long, float, double, string, bytes) " +
                                  "for writing a DBRecord, but found '%s' as the type for column '%s'. Please " +
                                  "remove this column or transform it to a simple type.", type, field.getName());
    return type;
  }

  @Nullable
  private Object transformValue(int sqlColumnType, Object original) throws SQLException {
    if (original != null) {
      switch (sqlColumnType) {
        case Types.NUMERIC:
        case Types.DECIMAL:
          return ((BigDecimal) original).doubleValue();
        case Types.DATE:
          return ((Date) original).getTime();
        case Types.TIME:
          return ((Time) original).getTime();
        case Types.TIMESTAMP:
          return ((Timestamp) original).getTime();
        case Types.BLOB:
          Object toReturn;
          Blob blob = (Blob) original;
          try {
            toReturn = blob.getBytes(1, (int) blob.length());
          } finally {
            blob.free();
          }
          return toReturn;
        case Types.CLOB:
          String s;
          StringBuilder sbf = new StringBuilder();
          Clob clob = (Clob) original;
          try {
            try (BufferedReader br = new BufferedReader(clob.getCharacterStream(1, (int) clob.length()))) {
              if ((s = br.readLine()) != null) {
                sbf.append(s);
              }
              while ((s = br.readLine()) != null) {
                sbf.append(System.getProperty("line.separator"));
                sbf.append(s);
              }
            }
          } catch (IOException e) {
            throw new SQLException(e);
          } finally {
            clob.free();
          }
          return sbf.toString();
      }
    }
    return original;
  }

  private void writeToDataOut(DataOutput out, Schema.Type fieldType, @Nullable Object fieldValue) throws IOException {
    if (fieldValue == null) {
      return;
    }
    switch (fieldType) {
      case NULL:
        break;
      case STRING:
        // write string appropriately
        out.writeUTF((String) fieldValue);
        break;
      case BOOLEAN:
        out.writeBoolean((Boolean) fieldValue);
        break;
      case INT:
        // write short or int appropriately
        out.writeInt((Integer) fieldValue);
        break;
      case LONG:
        // write date, timestamp or long appropriately
        out.writeLong((Long) fieldValue);
        break;
      case FLOAT:
        // both real and float are set with the same method on prepared statement
        out.writeFloat((Float) fieldValue);
        break;
      case DOUBLE:
        out.writeDouble((Double) fieldValue);
        break;
      case BYTES:
        out.write((byte[]) fieldValue);
        break;
      default:
        throw new IOException(String.format("Unsupported datatype: %s with value: %s.", fieldType, fieldValue));
    }
  }

  private void writeToDB(PreparedStatement stmt, Schema.Type fieldType, @Nullable Object fieldValue,
                         int fieldIndex) throws SQLException {
    int sqlIndex = fieldIndex + 1;
    if (fieldValue == null) {
      stmt.setNull(sqlIndex, columnTypes[fieldIndex]);
      return;
    }
    switch (fieldType) {
      case NULL:
        stmt.setNull(sqlIndex, columnTypes[fieldIndex]);
        break;
      case STRING:
        // clob can also be written to as setString
        stmt.setString(sqlIndex, (String) fieldValue);
        break;
      case BOOLEAN:
        stmt.setBoolean(sqlIndex, (Boolean) fieldValue);
        break;
      case INT:
        // write short or int appropriately
        writeInt(stmt, fieldIndex, sqlIndex, fieldValue);
        break;
      case LONG:
        // write date, timestamp or long appropriately
        writeLong(stmt, fieldIndex, sqlIndex, fieldValue);
        break;
      case FLOAT:
        // both real and float are set with the same method on prepared statement
        stmt.setFloat(sqlIndex, (Float) fieldValue);
        break;
      case DOUBLE:
        stmt.setDouble(sqlIndex, (Double) fieldValue);
        break;
      case BYTES:
        writeBytes(stmt, fieldIndex, sqlIndex, fieldValue);
        break;
      default:
        throw new SQLException(String.format("Unsupported datatype: %s with value: %s.", fieldType, fieldValue));
    }
  }

  private void writeBytes(PreparedStatement stmt, int fieldIndex, int sqlIndex, Object fieldValue) throws SQLException {
    byte [] byteValue = (byte []) fieldValue;
    int parameterType = columnTypes[fieldIndex];
    if (Types.BLOB == parameterType) {
      stmt.setBlob(sqlIndex, new SerialBlob(byteValue));
      return;
    }
    // handles BINARY, VARBINARY and LOGVARBINARY
    stmt.setBytes(sqlIndex, (byte []) fieldValue);
  }

  private void writeInt(PreparedStatement stmt, int fieldIndex, int sqlIndex, Object fieldValue) throws SQLException {
    Integer intValue = (Integer) fieldValue;
    int parameterType = columnTypes[fieldIndex];
    if (Types.TINYINT == parameterType || Types.SMALLINT == parameterType) {
      stmt.setShort(sqlIndex, intValue.shortValue());
      return;
    }
    stmt.setInt(sqlIndex, intValue);
  }

  private void writeLong(PreparedStatement stmt, int fieldIndex, int sqlIndex, Object fieldValue) throws SQLException {
    Long longValue = (Long) fieldValue;
    switch (columnTypes[fieldIndex]) {
      case Types.DATE:
        stmt.setDate(sqlIndex, new Date(longValue));
        break;
      case Types.TIME:
        stmt.setTime(sqlIndex, new Time(longValue));
        break;
      case Types.TIMESTAMP:
        stmt.setTimestamp(sqlIndex, new Timestamp(longValue));
        break;
      default:
        stmt.setLong(sqlIndex, longValue);
        break;
    }
  }
}
