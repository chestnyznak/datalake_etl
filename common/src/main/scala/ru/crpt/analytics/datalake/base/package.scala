package ru.crpt.analytics.datalake

import com.beust.jcommander.ParameterException
import org.apache.hadoop.fs._
import org.apache.spark.sql._

package object base {
  //-------------GENERAL CONSTANTS AND LISTS-------------------
  val envList = List("dev", "test", "prod")

  //-------------VALIDATION FUNCTIONS--------------------------
  def checkEnv(env: String) {
    if (!envList.contains(env)) {
      throw new ParameterException(s"Env $env not acceptable. Use one of ${envList.mkString("|")}")
    }
  }

  //-------------SPARK FUNCTIONS------------------------------
  def getSparkSession(appName: String, hiveVerifyPartitionPath:Boolean =false, broadcastTimeout:Int =300): SparkSession = {
    SparkSession
      .builder()
      .appName(appName)
      .config("master", "yarn")
      .config("hive.metastore.warehouse.dir","/hive/warehouse")
      .config("spark.sql.hive.verifyPartitionPath", hiveVerifyPartitionPath)
      .config("spark.sql.broadcastTimeout", broadcastTimeout)
      .enableHiveSupport()
      .getOrCreate()
  }

  //-------------POSTGRES FUNCTIONS--------------------------------

  def putDfToPostgres(df: DataFrame, hostName: String, dbName: String, user: String, password: String, tblName: String, port: Int = 5432) {
    df.write
      .format("jdbc")
      .option("numPartitions", "10")
      .option("url", s"jdbc:postgresql://$hostName:$port/$dbName")
      .option("dbtable", s"$tblName")
      .option("user", s"$user")
      .option("password", s"$password")
      //.option("batchsize", 1000000)
      .option("driver", "org.postgresql.Driver")
      .mode("append")
      .save()
  }
  //-------------FILES/FORMATS MANIPULATIONS-----------------------

  def dropCreateHdfsDirectory(path: String, recursively: Boolean = true)(implicit fs: FileSystem) {
    fs.delete(new Path(path),recursively)
    fs.mkdirs(new Path(path))
  }

}

