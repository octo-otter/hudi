/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hudi.HoodieFileIndex.{DataSkippingFailureMode, collectReferencedColumns, convertFilterForTimestampKeyGenerator, getConfigProperties}
import org.apache.hudi.HoodieSparkConfUtils.getConfigValue
import org.apache.hudi.common.config.TimestampKeyGeneratorConfig.{TIMESTAMP_INPUT_DATE_FORMAT, TIMESTAMP_OUTPUT_DATE_FORMAT}
import org.apache.hudi.common.config.{HoodieMetadataConfig, TypedProperties}
import org.apache.hudi.common.model.{FileSlice, HoodieBaseFile, HoodieLogFile}
import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.hudi.common.util.StringUtils
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.keygen.{TimestampBasedAvroKeyGenerator, TimestampBasedKeyGenerator}
import org.apache.hudi.metadata.HoodieMetadataPayload
import org.apache.hudi.util.JFunction
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{And, Expression, Literal}
import org.apache.spark.sql.execution.datasources.{FileIndex, FileStatusCache, NoopCache, PartitionDirectory}
import org.apache.spark.sql.hudi.DataSkippingUtils.translateIntoColumnStatsIndexFilterExpr
import org.apache.spark.sql.hudi.HoodieSqlCommonUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.unsafe.types.UTF8String

import java.text.SimpleDateFormat
import java.util.stream.Collectors
import javax.annotation.concurrent.NotThreadSafe
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * A file index which support partition prune for hoodie snapshot and read-optimized query.
 *
 * Main steps to get the file list for query:
 * 1、Load all files and partition values from the table path.
 * 2、Do the partition prune by the partition filter condition.
 *
 * There are 3 cases for this:
 * 1、If the partition columns size is equal to the actually partition path level, we
 * read it as partitioned table.(e.g partition column is "dt", the partition path is "2021-03-10")
 *
 * 2、If the partition columns size is not equal to the partition path level, but the partition
 * column size is "1" (e.g. partition column is "dt", but the partition path is "2021/03/10"
 * who's directory level is 3).We can still read it as a partitioned table. We will mapping the
 * partition path (e.g. 2021/03/10) to the only partition column (e.g. "dt").
 *
 * 3、Else the the partition columns size is not equal to the partition directory level and the
 * size is great than "1" (e.g. partition column is "dt,hh", the partition path is "2021/03/10/12")
 * , we read it as a Non-Partitioned table because we cannot know how to mapping the partition
 * path with the partition columns in this case.
 *
 * TODO rename to HoodieSparkSqlFileIndex
 */
@NotThreadSafe
case class HoodieFileIndex(spark: SparkSession,
                           metaClient: HoodieTableMetaClient,
                           schemaSpec: Option[StructType],
                           options: Map[String, String],
                           @transient fileStatusCache: FileStatusCache = NoopCache,
                           includeLogFiles: Boolean = false)
  extends SparkHoodieTableFileIndex(
    spark = spark,
    metaClient = metaClient,
    schemaSpec = schemaSpec,
    configProperties = getConfigProperties(spark, options, metaClient),
    queryPaths = HoodieFileIndex.getQueryPaths(options),
    specifiedQueryInstant = options.get(DataSourceReadOptions.TIME_TRAVEL_AS_OF_INSTANT.key).map(HoodieSqlCommonUtils.formatQueryInstant),
    fileStatusCache = fileStatusCache
  )
    with FileIndex {

  @transient private var hasPushedDownPartitionPredicates: Boolean = false

  /**
   * NOTE: [[ColumnStatsIndexSupport]] is a transient state, since it's only relevant while logical plan
   *       is handled by the Spark's driver
   */
  @transient private lazy val columnStatsIndex = new ColumnStatsIndexSupport(spark, schema, metadataConfig, metaClient)

  /**
   * NOTE: [[RecordLevelIndexSupport]] is a transient state, since it's only relevant while logical plan
   * is handled by the Spark's driver
   */
  @transient private lazy val recordLevelIndex = new RecordLevelIndexSupport(spark, metadataConfig, metaClient)

  override def rootPaths: Seq[Path] = getQueryPaths.asScala

  var shouldEmbedFileSlices: Boolean = false

  /**
   * Returns the FileStatus for all the base files (excluding log files). This should be used only for
   * cases where Spark directly fetches the list of files via HoodieFileIndex or for read optimized query logic
   * implemented internally within Hudi like HoodieBootstrapRelation. This helps avoid the use of path filter
   * to filter out log files within Spark.
   *
   * @return List of FileStatus for base files
   */
  def allBaseFiles: Seq[FileStatus] = {
    getAllInputFileSlices.values.asScala.flatMap(_.asScala)
      .map(fs => fs.getBaseFile.orElse(null))
      .filter(_ != null)
      .map(_.getFileStatus)
      .toSeq
  }

  /**
   * Returns the FileStatus for all the base files and log files.
   *
   * @return List of FileStatus for base files and log files
   */
  private def allBaseFilesAndLogFiles: Seq[FileStatus] = {
    getAllInputFileSlices.values.asScala.flatMap(_.asScala)
      .flatMap(fs => {
        val baseFileStatusOpt = getBaseFileStatus(Option.apply(fs.getBaseFile.orElse(null)))
        val logFilesStatus = fs.getLogFiles.map[FileStatus](JFunction.toJavaFunction[HoodieLogFile, FileStatus](lf => lf.getFileStatus))
        val files = logFilesStatus.collect(Collectors.toList[FileStatus]).asScala
        baseFileStatusOpt.foreach(f => files.append(f))
        files
      }).toSeq
  }

  /**
   * Invoked by Spark to fetch list of latest base files per partition.
   *
   * @param partitionFilters partition column filters
   * @param dataFilters      data columns filters
   * @return list of PartitionDirectory containing partition to base files mapping
   */
  override def listFiles(partitionFilters: Seq[Expression], dataFilters: Seq[Expression]): Seq[PartitionDirectory] = {
    val prunedPartitionsAndFilteredFileSlices = filterFileSlices(dataFilters, partitionFilters).map {
      case (partitionOpt, fileSlices) =>
        if (shouldEmbedFileSlices) {
          val baseFileStatusesAndLogFileOnly: Seq[FileStatus] = fileSlices.map(slice => {
            if (slice.getBaseFile.isPresent) {
              slice.getBaseFile.get().getFileStatus
            } else if (slice.getLogFiles.findAny().isPresent) {
              slice.getLogFiles.findAny().get().getFileStatus
            } else {
              null
            }
          }).filter(slice => slice != null)
          val c = fileSlices.filter(f => f.getLogFiles.findAny().isPresent
            || (f.getBaseFile.isPresent && f.getBaseFile.get().getBootstrapBaseFile.isPresent)).
            foldLeft(Map[String, FileSlice]()) { (m, f) => m + (f.getFileId -> f) }
          if (c.nonEmpty) {
            PartitionDirectory(new PartitionFileSliceMapping(InternalRow.fromSeq(partitionOpt.get.values), c), baseFileStatusesAndLogFileOnly)
          } else {
            PartitionDirectory(InternalRow.fromSeq(partitionOpt.get.values), baseFileStatusesAndLogFileOnly)
          }

        } else {
          val allCandidateFiles: Seq[FileStatus] = fileSlices.flatMap(fs => {
            val baseFileStatusOpt = getBaseFileStatus(Option.apply(fs.getBaseFile.orElse(null)))
            val logFilesStatus = if (includeLogFiles) {
              fs.getLogFiles.map[FileStatus](JFunction.toJavaFunction[HoodieLogFile, FileStatus](lf => lf.getFileStatus))
            } else {
              java.util.stream.Stream.empty()
            }
            val files = logFilesStatus.collect(Collectors.toList[FileStatus]).asScala
            baseFileStatusOpt.foreach(f => files.append(f))
            files
          })
          PartitionDirectory(InternalRow.fromSeq(partitionOpt.get.values), allCandidateFiles)
        }
    }

    hasPushedDownPartitionPredicates = true

    if (shouldReadAsPartitionedTable()) {
      prunedPartitionsAndFilteredFileSlices
    } else if (shouldEmbedFileSlices) {
      assert(partitionSchema.isEmpty)
      prunedPartitionsAndFilteredFileSlices
    }else {
      Seq(PartitionDirectory(InternalRow.empty, prunedPartitionsAndFilteredFileSlices.flatMap(_.files)))
    }
  }

  /**
   * The functions prunes the partition paths based on the input partition filters. For every partition path, the file
   * slices are further filtered after querying metadata table based on the data filters.
   *
   * @param dataFilters data columns filters
   * @param partitionFilters partition column filters
   * @return A sequence of pruned partitions and corresponding filtered file slices
   */
  def filterFileSlices(dataFilters: Seq[Expression], partitionFilters: Seq[Expression])
  : Seq[(Option[BaseHoodieTableFileIndex.PartitionPath], Seq[FileSlice])] = {

    val prunedPartitionsAndFileSlices = getFileSlicesForPrunedPartitions(partitionFilters)

    // If there are no data filters, return all the file slices.
    // If there are no file slices, return empty list.
    if (prunedPartitionsAndFileSlices.isEmpty || dataFilters.isEmpty) {
      prunedPartitionsAndFileSlices
    } else {
      // Look up candidate files names in the col-stats or record level index, if all of the following conditions are true
      //    - Data-skipping is enabled
      //    - Col-Stats Index is present
      //    - Record-level Index is present
      //    - List of predicates (filters) is present
      val candidateFilesNamesOpt: Option[Set[String]] =
      lookupCandidateFilesInMetadataTable(dataFilters) match {
        case Success(opt) => opt
        case Failure(e) =>
          logError("Failed to lookup candidate files in File Index", e)

          spark.sqlContext.getConf(DataSkippingFailureMode.configName, DataSkippingFailureMode.Fallback.value) match {
            case DataSkippingFailureMode.Fallback.value => Option.empty
            case DataSkippingFailureMode.Strict.value => throw new HoodieException(e);
          }
      }

      logDebug(s"Overlapping candidate files from Column Stats or Record Level Index: ${candidateFilesNamesOpt.getOrElse(Set.empty)}")

      var totalFileSliceSize = 0
      var candidateFileSliceSize = 0

      val prunedPartitionsAndFilteredFileSlices = prunedPartitionsAndFileSlices.map {
        case (partitionOpt, fileSlices) =>
          // Filter in candidate files based on the col-stats or record level index lookup
          val candidateFileSlices: Seq[FileSlice] = {
            fileSlices.filter(fs => {
              val fileSliceFiles = fs.getLogFiles.map[String](JFunction.toJavaFunction[HoodieLogFile, String](lf => lf.getPath.getName))
                .collect(Collectors.toSet[String])
              val baseFileStatusOpt = getBaseFileStatus(Option.apply(fs.getBaseFile.orElse(null)))
              baseFileStatusOpt.exists(f => fileSliceFiles.add(f.getPath.getName))
              // NOTE: This predicate is true when {@code Option} is empty
              candidateFilesNamesOpt.forall(files => files.exists(elem => fileSliceFiles.contains(elem)))
            })
          }

          totalFileSliceSize += fileSlices.size
          candidateFileSliceSize += candidateFileSlices.size
          (partitionOpt, candidateFileSlices)
      }

      val skippingRatio =
        if (!areAllFileSlicesCached) -1
        else if (getAllFiles().nonEmpty && totalFileSliceSize > 0)
          (totalFileSliceSize - candidateFileSliceSize) / totalFileSliceSize.toDouble
        else 0

      logInfo(s"Total file slices: $totalFileSliceSize; " +
        s"candidate file slices after data skipping: $candidateFileSliceSize; " +
        s"skipping percentage $skippingRatio")

      hasPushedDownPartitionPredicates = true

      prunedPartitionsAndFilteredFileSlices
    }
  }

  def getFileSlicesForPrunedPartitions(partitionFilters: Seq[Expression]) : Seq[(Option[BaseHoodieTableFileIndex.PartitionPath], Seq[FileSlice])] = {
    // Prune the partition path by the partition filters
    // NOTE: Non-partitioned tables are assumed to consist from a single partition
    //       encompassing the whole table
    val prunedPartitions = if (shouldEmbedFileSlices) {
      listMatchingPartitionPaths(convertFilterForTimestampKeyGenerator(metaClient, partitionFilters))
    } else {
      listMatchingPartitionPaths(partitionFilters)
    }
    getInputFileSlices(prunedPartitions: _*).asScala.toSeq.map(
      { case (partition, fileSlices) => (Option.apply(partition), fileSlices.asScala) })
  }

  /**
   * In the fast bootstrap read code path, it gets the file status for the bootstrap base file instead of
   * skeleton file. Returns file status for the base file if available.
   */
  private def getBaseFileStatus(baseFileOpt: Option[HoodieBaseFile]): Option[FileStatus] = {
    baseFileOpt.map(baseFile => {
      if (shouldFastBootstrap) {
        if (baseFile.getBootstrapBaseFile.isPresent) {
          baseFile.getBootstrapBaseFile.get().getFileStatus
        } else {
          baseFile.getFileStatus
        }
      } else {
        baseFile.getFileStatus
      }
    })
  }

  private def lookupFileNamesMissingFromIndex(allIndexedFileNames: Set[String]) = {
    val allFileNames = getAllFiles().map(f => f.getPath.getName).toSet
    allFileNames -- allIndexedFileNames
  }

  /**
   * Computes pruned list of candidate base-files' names based on provided list of {@link dataFilters}
   * conditions, by leveraging Metadata Table's Record Level Index and Column Statistics index (hereon referred as
   * ColStats for brevity) bearing "min", "max", "num_nulls" statistics for all columns.
   *
   * NOTE: This method has to return complete set of candidate files, since only provided candidates will
   * ultimately be scanned as part of query execution. Hence, this method has to maintain the
   * invariant of conservatively including every base-file and log file's name, that is NOT referenced in its index.
   *
   * @param queryFilters list of original data filters passed down from querying engine
   * @return list of pruned (data-skipped) candidate base-files and log files' names
   */
  private def lookupCandidateFilesInMetadataTable(queryFilters: Seq[Expression]): Try[Option[Set[String]]] = Try {
    // NOTE: For column stats, Data Skipping is only effective when it references columns that are indexed w/in
    //       the Column Stats Index (CSI). Following cases could not be effectively handled by Data Skipping:
    //          - Expressions on top-level column's fields (ie, for ex filters like "struct.field > 0", since
    //          CSI only contains stats for top-level columns, in this case for "struct")
    //          - Any expression not directly referencing top-level column (for ex, sub-queries, since there's
    //          nothing CSI in particular could be applied for)
    //       For record index, Data Skipping is only effective when one of the query filter is of type EqualTo
    //       or IN query on simple record keys. In such a case the record index is used to filter the file slices
    //       and candidate files are obtained from these file slices.

    lazy val queryReferencedColumns = collectReferencedColumns(spark, queryFilters, schema)

    lazy val (_, recordKeys) = recordLevelIndex.filterQueriesWithRecordKey(queryFilters)
    if (!isMetadataTableEnabled || !isDataSkippingEnabled) {
      validateConfig()
      Option.empty
    } else if (recordKeys.nonEmpty) {
      Option.apply(recordLevelIndex.getCandidateFiles(getAllFiles(), recordKeys))
    } else if (!columnStatsIndex.isIndexAvailable || queryFilters.isEmpty || queryReferencedColumns.isEmpty) {
      validateConfig()
      Option.empty
    } else {
      // NOTE: Since executing on-cluster via Spark API has its own non-trivial amount of overhead,
      //       it's most often preferential to fetch Column Stats Index w/in the same process (usually driver),
      //       w/o resorting to on-cluster execution.
      //       For that we use a simple-heuristic to determine whether we should read and process CSI in-memory or
      //       on-cluster: total number of rows of the expected projected portion of the index has to be below the
      //       threshold (of 100k records)
      val shouldReadInMemory = columnStatsIndex.shouldReadInMemory(this, queryReferencedColumns)

      columnStatsIndex.loadTransposed(queryReferencedColumns, shouldReadInMemory) { transposedColStatsDF =>
        val indexSchema = transposedColStatsDF.schema
        val indexFilter =
          queryFilters.map(translateIntoColumnStatsIndexFilterExpr(_, indexSchema))
            .reduce(And)

        val allIndexedFileNames =
          transposedColStatsDF.select(HoodieMetadataPayload.COLUMN_STATS_FIELD_FILE_NAME)
            .collect()
            .map(_.getString(0))
            .toSet

        val prunedCandidateFileNames =
          transposedColStatsDF.where(new Column(indexFilter))
            .select(HoodieMetadataPayload.COLUMN_STATS_FIELD_FILE_NAME)
            .collect()
            .map(_.getString(0))
            .toSet

        // NOTE: Col-Stats Index isn't guaranteed to have complete set of statistics for every
        //       base-file or log file: since it's bound to clustering, which could occur asynchronously
        //       at arbitrary point in time, and is not likely to be touching all of the base files.
        //
        //       To close that gap, we manually compute the difference b/w all indexed (by col-stats-index)
        //       files and all outstanding base-files or log files, and make sure that all base files and
        //       log file not represented w/in the index are included in the output of this method
        val notIndexedFileNames = lookupFileNamesMissingFromIndex(allIndexedFileNames)

        Some(prunedCandidateFileNames ++ notIndexedFileNames)
      }
    }
  }

  override def refresh(): Unit = {
    super.refresh()
    columnStatsIndex.invalidateCaches()
    hasPushedDownPartitionPredicates = false
  }

  private def getAllFiles(): Seq[FileStatus] = {
    if (includeLogFiles) allBaseFilesAndLogFiles else allBaseFiles
  }

  override def inputFiles: Array[String] =
    getAllFiles().map(_.getPath.toString).toArray

  override def sizeInBytes: Long = getTotalCachedFilesSize

  def hasPredicatesPushedDown: Boolean =
    hasPushedDownPartitionPredicates

  private def isDataSkippingEnabled: Boolean = getConfigValue(options, spark.sessionState.conf,
    DataSourceReadOptions.ENABLE_DATA_SKIPPING.key, DataSourceReadOptions.ENABLE_DATA_SKIPPING.defaultValue.toString).toBoolean

  private def isMetadataTableEnabled: Boolean = metadataConfig.enabled()

  private def isColumnStatsIndexEnabled: Boolean = metadataConfig.isColumnStatsIndexEnabled

  private def isRecordIndexEnabled: Boolean = recordLevelIndex.isIndexAvailable

  private def isIndexEnabled: Boolean = isColumnStatsIndexEnabled || isRecordIndexEnabled

  private def validateConfig(): Unit = {
    if (isDataSkippingEnabled && (!isMetadataTableEnabled || !isIndexEnabled)) {
      logWarning("Data skipping requires both Metadata Table and at least one of Column Stats Index or Record Level Index" +
        " to be enabled as well! " + s"(isMetadataTableEnabled = $isMetadataTableEnabled, isColumnStatsIndexEnabled = $isColumnStatsIndexEnabled"
        + s", isRecordIndexApplicable = $isRecordIndexEnabled)")
    }
  }

}

object HoodieFileIndex extends Logging {

  object DataSkippingFailureMode extends Enumeration {
    val configName = "hoodie.fileIndex.dataSkippingFailureMode"

    type DataSkippingFailureMode = Value

    case class Val(value: String) extends super.Val {
      override def toString(): String = value
    }

    import scala.language.implicitConversions
    implicit def valueToVal(x: Value): DataSkippingFailureMode = x.asInstanceOf[Val]

    val Fallback: Val = Val("fallback")
    val Strict: Val   = Val("strict")
  }

  private def collectReferencedColumns(spark: SparkSession, queryFilters: Seq[Expression], schema: StructType): Seq[String] = {
    val resolver = spark.sessionState.analyzer.resolver
    val refs = queryFilters.flatMap(_.references)
    schema.fieldNames.filter { colName => refs.exists(r => resolver.apply(colName, r.name)) }
  }

  def getConfigProperties(spark: SparkSession, options: Map[String, String], metaClient: HoodieTableMetaClient) = {
    val sqlConf: SQLConf = spark.sessionState.conf
    val properties = TypedProperties.fromMap(options.filter(p => p._2 != null).asJava)

    // TODO(HUDI-5361) clean up properties carry-over

    // To support metadata listing via Spark SQL we allow users to pass the config via SQL Conf in spark session. Users
    // would be able to run SET hoodie.metadata.enable=true in the spark sql session to enable metadata listing.
    val isMetadataTableEnabled = getConfigValue(options, sqlConf, HoodieMetadataConfig.ENABLE.key, null)
    if (isMetadataTableEnabled != null) {
      properties.setProperty(HoodieMetadataConfig.ENABLE.key(), String.valueOf(isMetadataTableEnabled))
    }

    val listingModeOverride = getConfigValue(options, sqlConf,
      DataSourceReadOptions.FILE_INDEX_LISTING_MODE_OVERRIDE.key, null)
    if (listingModeOverride != null) {
      properties.setProperty(DataSourceReadOptions.FILE_INDEX_LISTING_MODE_OVERRIDE.key, listingModeOverride)
    }
    val partitionColumns = metaClient.getTableConfig.getPartitionFields
    if (partitionColumns.isPresent) {
      // NOTE: Multiple partition fields could have non-encoded slashes in the partition value.
      //       We might not be able to properly parse partition-values from the listed partition-paths.
      //       Fallback to eager listing in this case.
      if (partitionColumns.get().length > 1
        && (listingModeOverride == null || DataSourceReadOptions.FILE_INDEX_LISTING_MODE_LAZY.equals(listingModeOverride))) {
        properties.setProperty(DataSourceReadOptions.FILE_INDEX_LISTING_MODE_OVERRIDE.key, DataSourceReadOptions.FILE_INDEX_LISTING_MODE_EAGER)
      }
    }

    properties
  }

  def convertFilterForTimestampKeyGenerator(metaClient: HoodieTableMetaClient,
      partitionFilters: Seq[Expression]): Seq[Expression] = {

    val tableConfig = metaClient.getTableConfig
    val keyGenerator = tableConfig.getKeyGeneratorClassName

    if (keyGenerator != null && (keyGenerator.equals(classOf[TimestampBasedKeyGenerator].getCanonicalName) ||
        keyGenerator.equals(classOf[TimestampBasedAvroKeyGenerator].getCanonicalName))) {
      val inputFormat = tableConfig.getString(TIMESTAMP_INPUT_DATE_FORMAT)
      val outputFormat = tableConfig.getString(TIMESTAMP_OUTPUT_DATE_FORMAT)
      if (StringUtils.isNullOrEmpty(inputFormat) || StringUtils.isNullOrEmpty(outputFormat) ||
        inputFormat.equals(outputFormat)) {
        partitionFilters
      } else {
        try {
          val inDateFormat = new SimpleDateFormat(inputFormat)
          val outDateFormat = new SimpleDateFormat(outputFormat)
          partitionFilters.toArray.map {
            _.transformDown {
              case Literal(value, dataType) if dataType.isInstanceOf[StringType] =>
                val converted = outDateFormat.format(inDateFormat.parse(value.toString))
                Literal(UTF8String.fromString(converted), StringType)
            }
          }
        } catch {
          case NonFatal(e) =>
            logWarning("Fail to convert filters for TimestampBaseAvroKeyGenerator", e)
            partitionFilters
        }
      }
    } else {
      partitionFilters
    }
  }

  private def getQueryPaths(options: Map[String, String]): Seq[Path] = {
    // NOTE: To make sure that globbing is appropriately handled w/in the
    //       `path`, we need to:
    //          - First, probe whether requested globbed paths has been resolved (and `glob.paths` was provided
    //          in options); otherwise
    //          - Treat `path` as fully-qualified (ie non-globbed) path
    val paths = options.get("glob.paths") match {
      case Some(globbed) =>
        globbed.split(",").toSeq
      case None =>
        val path = options.getOrElse("path",
          throw new IllegalArgumentException("'path' or 'glob paths' option required"))
        Seq(path)
    }

    paths.map(new Path(_))
  }
}
