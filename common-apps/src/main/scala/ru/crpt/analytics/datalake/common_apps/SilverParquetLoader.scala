package ru.crpt.analytics.datalake.common_apps

import com.beust.jcommander.{JCommander, Parameter, Parameters}
import com.typesafe.scalalogging.StrictLogging
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DateType, TimestampType}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession, functions}
import ru.crpt.analytics.datalake.base.utils.DatesHelper.validateDate
import ru.crpt.analytics.datalake.base.{checkEnv, dropCreateHdfsDirectory, getSparkSession, putDfToPostgres}

import java.io.IOException
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}


@Parameters(separators = "=")
class SilverParquetLoaderAppParams {
  @Parameter(names = Array("--help"), help = true)
  var help = false
  @Parameter(names = Array("--env"),description = "Environment code, generally [dev|test|prod]",required = true)
  var env: String = _
  @Parameter(names = Array("--pg-host"),description = "PG db host name or IP",required = true)
  var pgHost: String = _
  @Parameter(names = Array("--pg-user"),description = "PG db user",required = true)
  var pgUser: String = _
  @Parameter(names = Array("--pg-pass"),description = "PG db password",required = true)
  var pgPass: String = _
  @Parameter(names = Array("--load_date"), description = "Bronze layer date to load",required = true)
  var dateToLoad: String = _
  @Parameter(names = Array("--repart_cnt"), description = "Output partitions count to get ~128Mb files. " +
    "Should be calculated depending on data and current file size",required = true)
  var outPartCount: Int = 1200
  @Parameter(names = Array("--ignore-data-in-target"), description = "Ignore or not lots of rows in loaded partition", required = false)
  var ignoreDataInTarget: Int = 0
  @Parameter(names = Array("--thread-pool-capacity"), description = "Parallel deduplication threads count", required = false)
  var dedupParallelThreads = 5
  @Parameter(names = Array("--target-silver-path"), description = "Optional parameter for target silver path", required = false)
  var targetPath: String = ""
  @Parameter(names = Array("--deduplicate-all-target-parts"), description = "Optional parameter for target silver path", required = false)
  var dedupAllTargetParts: Int = 0
}

object SilverParquetLoader extends App with StrictLogging {

  def getParams(args: Array[String]): SilverParquetLoaderAppParams =
  {
    val params = new SilverParquetLoaderAppParams
    val jCommander = new JCommander(params, null, args.toArray: _*)
    if (params.help) { jCommander.usage(); System.exit(0)}
    checkEnv(params.env)
    validateDate(params.dateToLoad)
    params
  }

  def deduplicatePath(path: String, keyColumns: Seq[String], businessDateColName: String)
                     (implicit spark: SparkSession): DataFrame = {
    logger.debug(s">>>>>> deduplicatePath start for path $path")
    val df1Folder = spark.read.option("mergeSchema", value = true).parquet(path)
    deduplicateDataframeByKey(df1Folder, keyColumns, "ts_change", businessDateColName)
  }

  def deduplicateDataframeByKey(df: DataFrame, keyColumns: Seq[String], tsChangeColName: String = "ts_change",
                                businessDateColName: String): DataFrame = {
    logger.debug(">>>>>> deduplicateDataframeByKey start")
    import spark.implicits._
    val window = Window.partitionBy(keyColumns.map(col):_*).orderBy(col(tsChangeColName).desc_nulls_last)
    df.withColumn("_row_number",row_number().over(window))
      .filter($"_row_number" === 1)
      .drop("_row_number")
  }

  def deduplicatePartListParallel(datesSeq: Seq[String], parTreads: Int, keySeq: Seq[String], targetSilverPath: String,
                                  partColName: String, outFilesCnt: Int, businessDateColName: String)
                                 (implicit sparkSession: SparkSession, fs: FileSystem): Seq[String] = {
    implicit val ec: ExecutionContextExecutorService =
      ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(parTreads))

    val tasks = datesSeq.map(date => Future {
      logger.info(s">>>>>> deduplicate $date start")
      val datePath = s"$targetSilverPath/$partColName=$date"
      val tmpPath = s"/tmp/$datePath"
      val oldPath = s"/tmp/old/$datePath"

      deduplicatePath(datePath, keySeq, businessDateColName)(sparkSession).
        repartition(outFilesCnt).
        write.
        mode(SaveMode.Overwrite).
        parquet(tmpPath)
      logger.info(s">>>>>> deduplicated $datePath was written to $tmpPath")

      dropCreateHdfsDirectory(oldPath)(fs)
      val resCurrToOld  = fs.rename(new Path(datePath), new Path(oldPath))
      val resTempToCurr = if (resCurrToOld) fs.rename(new Path(tmpPath), new Path(datePath)) else false
      val resDelOld = if (resTempToCurr) fs.delete(new Path(oldPath), true) else false
      if (!resCurrToOld || !resTempToCurr || !resDelOld)
        throw new IOException(s"Unable to rename or delete files for $datePath resCurrToOld=[$resCurrToOld] " +
          s"resTempToCurr=[$resTempToCurr] resDelOld=[$resDelOld]")

      logger.info(s">>>>>> deduplicate $date end")
      date
    })
    val results = Await.result(Future.sequence(tasks), Duration.Inf)
    ec.shutdown()
    results
  }

  //--------------MAIN------------------------
  logger.info(">>>>>> Start")
  val pars = getParams(args)
  val spark: SparkSession = getSparkSession(s"SilverLoader")
  import spark.implicits._
  //Define suitable properties here or get them from parameters/configs
  val props  = Map("bronze_path" -> "/path/to/bronze",
                   "silver_path" -> "/path/to/silver",
                   "silver_ext_table_name" -> "silver_db.my_table_ext",
                   "pkFieldsList" -> "pk_col_1,pk_col_2",
                   "partColName" -> "part_col",
                   "partColExpr" -> "to_date(part_col)",
                   "businessDateColName" -> "dt_col")

  val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)

  val bronzeDf = spark.read.parquet(s"${props("bronze_path")}/_update_dt=${pars.dateToLoad}")
  bronzeDf.cache
  logger.info(s">>>>>> Bronze read ${props("bronze_path")}/_update_dt=${pars.dateToLoad}")

  val keySeq = props("pkFieldsList").split(",").toSeq
  val targetSilverPath = if(pars.targetPath.isEmpty) props("silver_path") else pars.targetPath
  val silverDatePath = s"$targetSilverPath/${props("partColName")}=${pars.dateToLoad}"
  val silverExistingCount = if (fs.exists(new Path(silverDatePath)))
                              spark.read.parquet(silverDatePath).count
                            else 0
  val bronzeNewCount = bronzeDf.count
  val silvBronzePercent = silverExistingCount / bronzeNewCount
  logger.info(s">>>>>> Bronze count = [$bronzeNewCount] silvBronzePercent=[$silvBronzePercent]")

  val mainBronzeAlreadyInserted = if (silvBronzePercent > 0.9) true else false
  val allBusinessDates = bronzeDf.select( expr(props("partColExpr")).as(props("partColName"))).distinct()

  //Bronze upsert required. It was never made or insert requirement was explicitly set
  if(!mainBronzeAlreadyInserted || pars.ignoreDataInTarget == 1) {
    spark.conf.set("spark.sql.session.timeZone", "Europe/Moscow") //to get correct dates from timestamps

    //TimestampTypes because spark 3 throws errors while writing DateType or String df-columns to date columns in Postgres
    val allBusinessDatesPg = allBusinessDates.withColumnRenamed(props("partColName"),"business_datetime").
                                    select($"business_datetime".cast(TimestampType)).
                                    filter("business_datetime is not null").
                                    withColumn("update_datetime", lit(pars.dateToLoad).cast(TimestampType))
    //Saving to Postgres many-to-many relations between business dates and system-update dates
    putDfToPostgres(allBusinessDatesPg,pars.pgHost,"my_db",pars.pgUser,
                    pars.pgPass,"bronze_update_business_date_mapping")
    logger.info(">>>>>> Dates mapping written to PG")


    logger.info(">>>>>> Start inserting")
    //Remove data-skew
    val dfToWrite = bronzeDf.withColumn("repart",
                                          when(col(props("partColName")) === pars.dateToLoad,
                                               functions.concat(lit(pars.dateToLoad), monotonically_increasing_id() % pars.outPartCount)).
                                          otherwise(col(props("partColName")) ) ).
                                        repartition($"repart").drop($"repart")

    dfToWrite.write.
              mode(SaveMode.Append).
              partitionBy(props("partColName")).
              parquet(targetSilverPath)
    logger.info(">>>>>> End inserting")
  }
  else {
    logger.warn(s">>>>>> Bronze seems to be already loaded and will not be inserted another time for date ${pars.dateToLoad} because " +
      s"silvBronzePercent = [$silvBronzePercent] ignoreExistingData = [${pars.ignoreDataInTarget}]")
  }
  //Deduplication
  //We need to deduplicate partitions:
  // - If main date (pars.loadDate which is equal to bronze date being processed) was
  //   inserted twice or more times.
  //   A bit of duplicates in other business dates will reside. They can be caused by
  //    --updates in source db
  //    --situation when one bronze date was upserted multiple times
  //   They will be grouped during read from special view and eventually deduplicated once per week (or another period)
  // - If it was explicitly set in parameters for all partitions.

  logger.info(">>>>>> Start deduplicate")

  val allBusinessDatesFilt = allBusinessDates.filter(s"${props("partColName")} is not null")
  val datesToDedupl = if (mainBronzeAlreadyInserted && pars.ignoreDataInTarget == 1) // highly likely data were just now inserted another time
                        Seq(pars.dateToLoad).toDF(props("partColName")).select(col(props("partColName")).cast(DateType) )
                      else
                        if (pars.dedupAllTargetParts == 1) allBusinessDatesFilt
                        else spark.emptyDataFrame

  val datesList = datesToDedupl.collect().map(row => row.getDate(0).toString).toSeq
  logger.info(s">>>>>> dates to deduplicate [${datesList.mkString(" , ")}]")

  deduplicatePartListParallel(datesList,pars.dedupParallelThreads, keySeq, targetSilverPath, props("partColName"),
                               pars.outPartCount, props("businessDateColName"))(spark,fs)

  spark.sql(s"msck repair table ${props("silver_ext_table_name")} ")
  logger.info(s">>>>>> end")
}
