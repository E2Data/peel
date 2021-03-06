/**
 * Copyright (C) 2014 TU Berlin (peel@dima.tu-berlin.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.peelframework.hadoop.beans.system

import com.samskivert.mustache.Mustache
import org.peelframework.core.beans.system.Lifespan.Lifespan
import org.peelframework.core.beans.system.{LogCollection, SetUpTimeoutException, System}
import org.peelframework.core.config.{Model, SystemConfig}
import org.peelframework.core.util.shell

import scala.collection.JavaConverters._
import scala.util.matching.Regex

/** Wrapper class for HDFS1.
  *
  * Implements HDFS1 as a Peel `System` and provides setup and teardown methods.
  * Additionally it offers the `FileSysem` capabilities to interact with hdfs.
  *
  * @param version Version of the system (e.g. "7.1")
  * @param configKey The system configuration resides under `system.\${configKey}`
  * @param lifespan `Lifespan` of the system
  * @param dependencies Set of dependencies that this system needs
  * @param mc The moustache compiler to compile the templates that are used to generate property files for the system
  */
class HDFS1(
  version      : String,
  configKey    : String,
  lifespan     : Lifespan,
  dependencies : Set[System] = Set(),
  mc           : Mustache.Compiler) extends System("hdfs-2", version, configKey, lifespan, dependencies, mc)
                                       with HDFSFileSystem
                                       with LogCollection {

  // ---------------------------------------------------
  // LogCollection.
  // ---------------------------------------------------

  /** The patterns of the log files to watch. */
  override protected def logFilePatterns(): Seq[Regex] = {
    List(
      "hadoop-.+-namenode-.+\\.log".r,
      "hadoop-.+-namenode-.+\\.out".r,
      "hadoop-.+-datanode-.+\\.log".r,
      "hadoop-.+-datanode-.+\\.out".r)
  }

  // ---------------------------------------------------
  // System.
  // ---------------------------------------------------

  override def configuration() = SystemConfig(config, {
    val conf = config.getString(s"system.$configKey.path.config")
    List(
      SystemConfig.Entry[Model.Hosts](s"system.$configKey.config.masters", s"$conf/masters", templatePath("conf/hosts"), mc),
      SystemConfig.Entry[Model.Hosts](s"system.$configKey.config.slaves", s"$conf/slaves", templatePath("conf/hosts"), mc),
      SystemConfig.Entry[Model.Env](s"system.$configKey.config.env", s"$conf/hadoop-env.sh", templatePath("conf/hadoop-env.sh"), mc),
      SystemConfig.Entry[Model.GenericKeyValuePairs](s"system.$configKey.config.core", s"$conf/core-site.xml", templatePath("conf/site.xml"), mc),
      SystemConfig.Entry[Model.GenericKeyValuePairs](s"system.$configKey.config.hdfs", s"$conf/hdfs-site.xml", templatePath("conf/site.xml"), mc)
    )
  })

  /** Checks if all datanodes have connected and the system is out of safemode. */
  override protected def start(): Unit = {
    if (config.getBoolean(s"system.$configKey.format")) format()

    val user = config.getString(s"system.$configKey.user")
    val logDir = config.getString(s"system.$configKey.path.log")
    val hostname = config.getString("app.hostname")

    var failedStartUpAttempts = 0
    while (!isUp) {
      try {
        val totl = config.getStringList(s"system.$configKey.config.slaves").size()
        var init = Integer.parseInt((shell !! s"""cat $logDir/hadoop-$user-namenode-$hostname.log | grep 'registerDatanode:' | wc -l""").trim())

        shell ! s"${config.getString(s"system.$configKey.path.home")}/bin/start-dfs.sh"
        logger.info(s"Waiting for nodes to connect")

        var curr = init
        var safe = !(shell !! s"${config.getString(s"system.$configKey.path.home")}/bin/hadoop dfsadmin -safemode get").toLowerCase.contains("off")
        var cntr = config.getInt(s"system.$configKey.startup.polling.counter")
        while (curr - init < totl || safe) {
          logger.info(s"Connected ${curr - init} from $totl nodes, safemode is ${if (safe) "ON" else "OFF"}")
          // wait a bit
          Thread.sleep(config.getInt(s"system.$configKey.startup.polling.interval"))
          // get new values
          curr = Integer.parseInt((shell !! s"""cat $logDir/hadoop-$user-namenode-$hostname.log | grep 'registerDatanode:' | wc -l""").trim())
          safe = !(shell !! s"${config.getString(s"system.$configKey.path.home")}/bin/hadoop dfsadmin -safemode get").toLowerCase.contains("off")
          // timeout if counter goes below zero
          cntr = cntr - 1
          if (curr - init < 0) init = 0 // protect against log reset on startup
          if (cntr < 0) throw new SetUpTimeoutException(s"Cannot start system '$toString'; node connection timeout at system ")
        }
        isUp = true
      } catch {
        case e: SetUpTimeoutException =>
          failedStartUpAttempts = failedStartUpAttempts + 1
          if (failedStartUpAttempts < config.getInt(s"system.$configKey.startup.max.attempts")) {
            shell ! s"${config.getString(s"system.$configKey.path.home")}/bin/stop-dfs.sh"
            logger.info(s"Could not bring system '$toString' up in time, trying again...")
          } else {
            throw e
          }
      }
    }
  }

  override protected def stop() = {
    shell ! s"${config.getString(s"system.$configKey.path.home")}/bin/stop-dfs.sh"
    if (config.getBoolean(s"system.$configKey.format")) format()
    isUp = false
  }

  def isRunning = {
    val pidDir = config.getString(s"system.$configKey.config.env.HADOOP_PID_DIR")
    (shell ! s""" ps -p `cat ${pidDir}/hadoop-*-namenode.pid` """) == 0 ||
      (shell ! s""" ps -p `cat ${pidDir}/hadoop-*-secondarynamenode.pid` """) == 0 ||
      (shell ! s""" ps -p `cat ${pidDir}/hadoop-*-datanode.pid` """) == 0
  }

  // ---------------------------------------------------
  // Helper methods.
  // ---------------------------------------------------

  private def format() = {
    val user = config.getString(s"system.$configKey.user")
    val nameDir = new java.net.URI(config.getString(s"system.$configKey.config.hdfs.dfs.name.dir")).getPath

    logger.info(s"Formatting namenode")
    shell ! ("%s/bin/hadoop namenode -format -nonInteractive -force".format(config.getString(s"system.$configKey.path.home")),
      "Unable to format namenode.")

    logger.info(s"Fixing data directories")
    for (dataNode <- config.getStringList(s"system.$configKey.config.slaves").asScala) {
      for (dirURI <- config.getString(s"system.$configKey.config.hdfs.dfs.data.dir").split(',')) {
        val dataDir = new java.net.URI(dirURI).getPath
        logger.info(s"Initializing data directory $dataDir at datanode $dataNode")
        shell ! (s""" ssh $user@$dataNode "rm -Rf $dataDir/current" """, "Unable to remove hdfs data directories.")
        shell ! (s""" ssh $user@$dataNode "mkdir -p $dataDir/current" """, "Unable to create hdfs data directories.")
        logger.info(s"Ensuring 0755 permissions for directory $dataDir at datanode $dataNode")
        shell ! (s""" ssh $user@$dataNode "chmod 0755 $dataDir" """, "Unable to change rights on data directories.")
        logger.info(s"Adapting VERSION file on datanode $dataNode")
        shell ! raw""" cat $nameDir/current/VERSION | sed $$'3 i\\\nstorageID=\n' | sed 's/storageType=NAME_NODE/storageType=DATA_NODE/g' > $nameDir/current/VERSION.data """
        logger.info(s"Copying namenode's VERSION file to datanode $dataNode")
        shell ! s""" scp $nameDir/current/VERSION.data $user@$dataNode:$dataDir/current/VERSION """
        shell ! s""" rm -f $nameDir/current/VERSION.data """
      }
    }
  }
}
