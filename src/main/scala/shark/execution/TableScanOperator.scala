/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
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

package shark.execution

import java.util.{ArrayList, Arrays}

import scala.collection.JavaConversions._
import scala.reflect.BeanProperty

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.metastore.api.Constants.META_TABLE_PARTITION_COLUMNS
import org.apache.hadoop.hive.ql.exec.{TableScanOperator => HiveTableScanOperator}
import org.apache.hadoop.hive.ql.exec.{MapSplitPruning, Utilities}
import org.apache.hadoop.hive.ql.metadata.{Partition, Table}
import org.apache.hadoop.hive.ql.plan.{PartitionDesc, TableDesc, TableScanDesc}
import org.apache.hadoop.hive.serde.Constants
import org.apache.hadoop.hive.serde2.objectinspector.{ObjectInspector, ObjectInspectorFactory,
  StructObjectInspector}
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory

import org.apache.spark.rdd.{PartitionPruningRDD, RDD}

import shark.{LogHelper, SharkConfVars, SharkEnv}
import shark.execution.optimization.ColumnPruner
import shark.memstore2.CacheType
import shark.memstore2.CacheType._
import shark.memstore2.{ColumnarSerDe, MemoryMetadataManager}
import shark.memstore2.{TablePartition, TablePartitionStats}
import shark.util.HiveUtils


/**
 * The TableScanOperator is used for scanning any type of Shark or Hive table.
 */
class TableScanOperator extends TopOperator[TableScanDesc] {

  // TODO(harvey): Try to use 'TableDesc' for execution and save 'Table' for analysis/planning.
  //     Decouple `Table` from TableReader and ColumnPruner.
  @transient var table: Table = _

  @transient var hiveOp: HiveTableScanOperator = _

  // Metadata for Hive-partitions (i.e if the table was created from PARTITION BY). NULL if this
  // table isn't Hive-partitioned. Set in SparkTask::initializeTableScanTableDesc().
  @transient var parts: Array[Partition] = _

  // For convenience, a local copy of the HiveConf for this task.
  @transient var localHConf: HiveConf = _

  // PartitionDescs are used during planning in Hive. This reference to a single PartitionDesc
  // is used to initialize partition ObjectInspectors.
  // If the table is not Hive-partitioned, then 'firstConfPartDesc' won't be used. The value is not
  // NULL, but rather a reference to a "dummy" PartitionDesc, in which only the PartitionDesc's
  // 'table' is not NULL.
  // Set in SparkTask::initializeTableScanTableDesc().
  @BeanProperty var firstConfPartDesc: PartitionDesc  = _

  @BeanProperty var tableDesc: TableDesc = _

  // True if table data is stored the Spark heap.
  @BeanProperty var isInMemoryTableScan: Boolean = _

  @BeanProperty var cacheMode: CacheType.CacheType = _


  override def initializeOnMaster() {
    // Create a local copy of the HiveConf that will be assigned job properties and, for disk reads,
    // broadcasted to slaves.
    localHConf = new HiveConf(super.hconf)
    cacheMode = CacheType.fromString(
      tableDesc.getProperties().get("shark.cache").asInstanceOf[String])
    isInMemoryTableScan = SharkEnv.memoryMetadataManager.containsTable(
      table.getDbName, table.getTableName)
  }

  override def outputObjectInspector() = {
    if (parts == null) {
      val serializer = if (isInMemoryTableScan || cacheMode == CacheType.TACHYON) {
        new ColumnarSerDe
      } else {
        tableDesc.getDeserializerClass().newInstance()
      }
      serializer.initialize(hconf, tableDesc.getProperties)
      serializer.getObjectInspector()
    } else {
      val partProps = firstConfPartDesc.getProperties()
      val partSerDe = if (isInMemoryTableScan || cacheMode == CacheType.TACHYON) {
        new ColumnarSerDe
      } else {
        firstConfPartDesc.getDeserializerClass().newInstance()
      }
      partSerDe.initialize(hconf, partProps)
      HiveUtils.makeUnionOIForPartitionedTable(partProps, partSerDe)
    }
  }

  override def execute(): RDD[_] = {
    assert(parentOperators.size == 0)

    val tableNameSplit = tableDesc.getTableName.split('.') // Split from 'databaseName.tableName'
    val databaseName = tableNameSplit(0)
    val tableName = tableNameSplit(1)

    val _properties = tableDesc.getProperties()
    val _tableReaderClass = _properties.getProperty("shark.table.reader.class")
    logWarning(_tableReaderClass)

    // There are three places we can load the table from.
    // 1. Spark heap (block manager), accessed through the Shark MemoryMetadataManager
    // 2. Tachyon table
    // 3. Hive table on HDFS (or other Hadoop storage)
    // TODO(harvey): Pruning Hive-partitioned, cached tables isn't supported yet.
    if (isInMemoryTableScan || cacheMode == CacheType.TACHYON) {
      if (isInMemoryTableScan) {
        assert(cacheMode == CacheType.MEMORY || cacheMode == CacheType.MEMORY_ONLY,
          "Table %s.%s is in Shark metastore, but its cacheMode (%s) indicates otherwise".
            format(databaseName, tableName, cacheMode))
      }
      val tableReader = if (cacheMode == CacheType.TACHYON) {
        new TachyonTableReader(tableDesc)
      } else {
        new HeapTableReader(tableDesc)
      }
      if (table.isPartitioned) {
        tableReader.makeRDDForPartitionedTable(parts, Some(createPrunedRdd _))
      } else {
        tableReader.makeRDDForTable(table, Some(createPrunedRdd _))
      }
    } else if (_tableReaderClass != null && _tableReaderClass.length > 0) {
      try {
        val _class = Class.forName(_tableReaderClass)
        val _tableReader = _class.getConstructor(tableDesc.getClass, localHConf.getClass).newInstance(tableDesc, localHConf)

        if (!_tableReader.isInstanceOf[TableReader]) {
          throw new IllegalArgumentException("shark.table.reader.class:" + _tableReaderClass + " is not TableReader subclass")
        } else {
          val tableReader = _tableReader.asInstanceOf[TableReader]
          if (table.isPartitioned) {
            tableReader.makeRDDForPartitionedTable(parts)
          } else {
            tableReader.makeRDDForTable(table)
          }
        }
      } catch {
        case e : Exception => throw new RuntimeException(e)
      }
    } else {
      // Table is a Hive table on HDFS (or other Hadoop storage).
      makeRDDFromHadoop()
    }
  }

  private def createPrunedRdd(
      rdd: RDD[_],
      indexToStats: collection.Map[Int, TablePartitionStats]): RDD[_] = {
    // Run map pruning if the flag is set, there exists a filter predicate on
    // the input table and we have statistics on the table.
    val columnsUsed = new ColumnPruner(this, table).columnsUsed

    if (!table.isPartitioned && cacheMode == CacheType.TACHYON) {
      SharkEnv.tachyonUtil.pushDownColumnPruning(rdd, columnsUsed)
    }

    val shouldPrune = SharkConfVars.getBoolVar(localHConf, SharkConfVars.MAP_PRUNING) &&
      childOperators(0).isInstanceOf[FilterOperator] &&
      indexToStats.size == rdd.partitions.size

    val prunedRdd: RDD[_] = if (shouldPrune) {
      val startTime = System.currentTimeMillis
      val printPruneDebug = SharkConfVars.getBoolVar(
        localHConf, SharkConfVars.MAP_PRUNING_PRINT_DEBUG)

      // Must initialize the condition evaluator in FilterOperator to get the
      // udfs and object inspectors set.
      val filterOp = childOperators(0).asInstanceOf[FilterOperator]
      filterOp.initializeOnSlave()

      def prunePartitionFunc(index: Int): Boolean = {
        if (printPruneDebug) {
          logInfo("\nPartition " + index + "\n" + indexToStats(index))
        }
        // Only test for pruning if we have stats on the column.
        val partitionStats = indexToStats(index)
        if (partitionStats != null && partitionStats.stats != null) {
          MapSplitPruning.test(partitionStats, filterOp.conditionEvaluator)
        } else {
          true
        }
      }

      // Do the pruning.
      val prunedRdd = PartitionPruningRDD.create(rdd, prunePartitionFunc)
      val timeTaken = System.currentTimeMillis - startTime
      logInfo("Map pruning %d partitions into %s partitions took %d ms".format(
        rdd.partitions.size, prunedRdd.partitions.size, timeTaken))
      prunedRdd
    } else {
      rdd
    }

    prunedRdd.mapPartitions { iter =>
      if (iter.hasNext) {
        val tablePartition1 = iter.next()
        val tablePartition = tablePartition1.asInstanceOf[TablePartition]
        tablePartition.prunedIterator(columnsUsed)
      } else {
        Iterator.empty
      }
    }
  }

  /**
   * Create an RDD for a table stored in Hadoop.
   */
  def makeRDDFromHadoop(): RDD[_] = {
    // Try to have the InputFormats filter predicates.
    TableScanOperator.addFilterExprToConf(localHConf, hiveOp)

    val hadoopReader = new HadoopTableReader(tableDesc, localHConf)
    if (table.isPartitioned) {
      logDebug("Making %d Hive partitions".format(parts.size))
      // The returned RDD contains arrays of size two with the elements as
      // (deserialized row, column partition value).
      return hadoopReader.makeRDDForPartitionedTable(parts)
    } else {
      // The returned RDD contains deserialized row Objects.
      return hadoopReader.makeRDDForTable(table)
    }
  }

  // All RDD processing is done in execute().
  override def processPartition(split: Int, iter: Iterator[_]): Iterator[_] =
    throw new UnsupportedOperationException("TableScanOperator.processPartition()")

}


object TableScanOperator extends LogHelper {

  /**
   * Add filter expressions and column metadata to the HiveConf. This is meant to be called on the
   * master - it's impractical to add filters during slave-local JobConf creation in HadoopRDD,
   * since we would have to serialize the HiveTableScanOperator.
   */
  private def addFilterExprToConf(hiveConf: HiveConf, hiveTableScanOp: HiveTableScanOperator) {
    val tableScanDesc = hiveTableScanOp.getConf()
    if (tableScanDesc == null) return

    val rowSchema = hiveTableScanOp.getSchema
    if (rowSchema != null) {
      // Add column names to the HiveConf.
      val columnNames = new StringBuilder
      for (columnInfo <- rowSchema.getSignature()) {
        if (columnNames.length > 0) {
          columnNames.append(",")
        }
        columnNames.append(columnInfo.getInternalName())
      }
      val columnNamesString = columnNames.toString()
      hiveConf.set(Constants.LIST_COLUMNS, columnNamesString)

      // Add column types to the HiveConf.
      val columnTypes = new StringBuilder
      for (columnInfo <- rowSchema.getSignature()) {
        if (columnTypes.length > 0) {
          columnTypes.append(",")
        }
        columnTypes.append(columnInfo.getType().getTypeName())
      }
      val columnTypesString = columnTypes.toString()
      hiveConf.set(Constants.LIST_COLUMN_TYPES, columnTypesString)
    }

    // Push down predicate filters.
    val filterExprNode = tableScanDesc.getFilterExpr()
    if (filterExprNode != null) {
      val filterText = filterExprNode.getExprString()
      hiveConf.set(TableScanDesc.FILTER_TEXT_CONF_STR, filterText)
      logDebug("Filter text: " + filterText)

      val filterExprNodeSerialized = Utilities.serializeExpression(filterExprNode)
      hiveConf.set(TableScanDesc.FILTER_EXPR_CONF_STR, filterExprNodeSerialized)
      logDebug("Filter expression: " + filterExprNodeSerialized)
    }
  }

}
