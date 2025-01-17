/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.calcite.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.druid.query.DataSource;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.external.ExternalDataSource;
import org.apache.druid.sql.calcite.external.ExternalTableScan;

import javax.annotation.Nullable;
import java.util.Objects;

public class DruidTable implements TranslatableTable
{
  private final DataSource dataSource;
  private final RowSignature rowSignature;

  @Nullable
  private final ObjectMapper objectMapper;
  private final boolean joinable;
  private final boolean broadcast;

  public DruidTable(
      final DataSource dataSource,
      final RowSignature rowSignature,
      @Nullable final ObjectMapper objectMapper,
      final boolean isJoinable,
      final boolean isBroadcast
  )
  {
    this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource");
    this.rowSignature = Preconditions.checkNotNull(rowSignature, "rowSignature");
    this.objectMapper = objectMapper;
    this.joinable = isJoinable;
    this.broadcast = isBroadcast;

    if (dataSource instanceof ExternalDataSource && objectMapper == null) {
      // objectMapper is used by ExternalTableScan to generate its digest.
      throw new NullPointerException("ObjectMapper is required for external datasources");
    }
  }

  public DataSource getDataSource()
  {
    return dataSource;
  }

  public RowSignature getRowSignature()
  {
    return rowSignature;
  }

  public boolean isJoinable()
  {
    return joinable;
  }

  public boolean isBroadcast()
  {
    return broadcast;
  }

  @Override
  public Schema.TableType getJdbcTableType()
  {
    return Schema.TableType.TABLE;
  }

  @Override
  public Statistic getStatistic()
  {
    return Statistics.UNKNOWN;
  }

  @Override
  public RelDataType getRowType(final RelDataTypeFactory typeFactory)
  {
    // For external datasources, the row type should be determined by whatever the row signature has been explicitly
    // passed in. Typecasting directly to SqlTypeName.TIMESTAMP will lead to inconsistencies with the Calcite functions
    // For example, TIME_PARSE(__time) where __time is specified to be a string field in the external datasource
    // would lead to an exception because __time would be interpreted as timestamp if we typecast it.
    boolean typecastTimeColumn = !(dataSource instanceof ExternalDataSource);
    return RowSignatures.toRelDataType(rowSignature, typeFactory, typecastTimeColumn);
  }

  @Override
  public boolean isRolledUp(final String column)
  {
    return false;
  }

  @Override
  public boolean rolledUpColumnValidInsideAgg(
      final String column,
      final SqlCall call,
      final SqlNode parent,
      final CalciteConnectionConfig config
  )
  {
    return true;
  }

  @Override
  public RelNode toRel(final RelOptTable.ToRelContext context, final RelOptTable table)
  {
    if (dataSource instanceof ExternalDataSource) {
      // Cannot use LogicalTableScan here, because its digest is solely based on the name of the table macro.
      // Must use our own class that computes its own digest.
      return new ExternalTableScan(context.getCluster(), objectMapper, this);
    } else {
      return LogicalTableScan.create(context.getCluster(), table);
    }
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    DruidTable that = (DruidTable) o;

    if (!Objects.equals(dataSource, that.dataSource)) {
      return false;
    }
    return Objects.equals(rowSignature, that.rowSignature);
  }

  @Override
  public int hashCode()
  {
    int result = dataSource != null ? dataSource.hashCode() : 0;
    result = 31 * result + (rowSignature != null ? rowSignature.hashCode() : 0);
    return result;
  }

  @Override
  public String toString()
  {
    return "DruidTable{" +
           "dataSource=" + dataSource +
           ", rowSignature=" + rowSignature +
           '}';
  }
}
