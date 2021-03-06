/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs

import java.io.{File, FileFilter, FilenameFilter}
import java.net.{URLClassLoader, URLDecoder}

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.client.Connector
import org.apache.hadoop.conf.Configuration
import org.geotools.data.Query
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType
import org.locationtech.geomesa.accumulo.index.{JoinPlan, QueryPlan}
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.jobs.mapreduce.GeoMesaInputFormat._

import scala.io.Source
import scala.util.Try

object JobUtils extends Logging {

  // default jars that will be included with m/r jobs
  lazy val defaultLibJars = {
    val defaultLibJarsFile = "org/locationtech/geomesa/jobs/default-libjars.list"
    val url = Try(getClass.getClassLoader.getResource(defaultLibJarsFile))
    val source = url.map(Source.fromURL)
    val lines = source.map(_.getLines().toList)
    source.foreach(_.close())
    lines.get
  }

  // paths are in order of preference for finding a jar
  def defaultSearchPath: Iterator[() => Seq[File]] =
    Iterator(() => getJarsFromEnvironment("GEOMESA_HOME"),
             () => getJarsFromEnvironment("ACCUMULO_HOME"),
             () => getJarsFromClasspath(classOf[AccumuloDataStore]),
             () => getJarsFromClasspath(classOf[Connector]))

  /**
   * Sets the libjars into a Hadoop configuration. Will search the environment first, then the
   * classpath, until all required jars have been found.
   *
   * @param conf
   * @param libJars
   */
  def setLibJars(conf: Configuration,
                 libJars: Seq[String] = defaultLibJars,
                 searchPath: Iterator[() => Seq[File]] = defaultSearchPath): Unit = {
    val foundJars = scala.collection.mutable.Set.empty[String]
    var remaining = libJars
    // search each path in order until we've found all our jars
    while (!remaining.isEmpty && searchPath.hasNext) {
      val urls = searchPath.next()()
      remaining = remaining.filter { jarPrefix =>
        val matched = urls.filter(url => url.getName.startsWith(jarPrefix))
        foundJars ++= matched.map(url => "file:///" + url.getAbsolutePath)
        matched.isEmpty
      }
    }

    if (!remaining.isEmpty) {
      logger.warn(s"Could not find required jars: $remaining")
    }

    // tmpjars is the hadoop config that corresponds to libjars
    conf.setStrings("tmpjars", foundJars.toSeq: _*)

    logger.debug(s"Job will use the following libjars=${foundJars.mkString("\n", "\n", "")}")
  }

  /**
   * Finds URLs of jar files based on an environment variable
   *
   * @param home
   * @return
   */
  def getJarsFromEnvironment(home: String): Seq[File] =
    sys.env.get(home)
      .map(f => new File(new File(f), "lib"))
      .filter(_.isDirectory)
      .toSeq
      .flatMap(loadJarsFromFolder)

  /**
   * Finds URLs of jar files based on the current classpath
   *
   * @param clas
   * @return
   */
  def getJarsFromClasspath(clas: Class[_]): Seq[File] = {
    val urls = clas.getClassLoader.asInstanceOf[URLClassLoader].getURLs
    urls.map(u => new File(cleanClassPathURL(u.getFile)))
  }

  /**
   * Recursively searches folders for jar files
   *
   * @param dir
   * @return
   */
  def loadJarsFromFolder(dir: File): Seq[File] = {
    val files = Option(dir.listFiles(new FilenameFilter() {
      override def accept(dir: File, name: String) =
        name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
    })).toSeq.flatten
    files ++ Option(dir.listFiles(new FileFilter() {
      override def accept(pathname: File) = pathname.isDirectory
    })).toSeq.flatten.flatMap(loadJarsFromFolder)
  }

  def cleanClassPathURL(url: String): String =
    URLDecoder.decode(url, "UTF-8").replace("file:", "").replace("!", "")

  /**
   * Gets a query plan that can be satisfied via AccumuloInputFormat - e.g. only 1 table and configuration.
   */
  def getSingleQueryPlan(ds: AccumuloDataStore, query: Query): QueryPlan = {
    val queryPlans = ds.getQueryPlan(query)

    // see if the plan is something we can execute from a single table
    val tryPlan = if (queryPlans.length > 1) None else queryPlans.headOption.filter {
      case qp: JoinPlan => false
      case _ => true
    }

    tryPlan.getOrElse {
      // this query has a join or requires multiple scans - instead, fall back to the record index
      logger.warn("Desired query plan requires multiple scans - falling back to full table scan")
      val qps = ds.getQueryPlan(query, Some(StrategyType.RECORD))
      if (qps.length > 1) {
        logger.error("The query being executed requires multiple scans, which is not currently " +
            "supported by geomesa. Your result set will be partially incomplete. " +
            s"Query: ${filterToString(query.getFilter)}")
      }
      qps.head
    }
  }
}
