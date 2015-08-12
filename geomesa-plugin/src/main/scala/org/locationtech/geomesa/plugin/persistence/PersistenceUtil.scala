/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.plugin.persistence

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.Properties

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.geoserver.platform.{GeoServerExtensions, GeoServerResourceLoader}

/**
 * Simple persistence strategy that keeps values in memory and writes them to a prop file in the
 * geoserver data dir. Not meant for more than a few props.
 */
object PersistenceUtil extends LazyLogging {

  private val properties = new Properties

  // this method searches the classpath as well as the data directory, so don't use a package name
  // like 'geomesa'
  private val geoMesaConfigDir = GeoServerExtensions.bean(classOf[GeoServerResourceLoader]).findOrCreateDirectory("geomesa-config")

  private val configFile = new File(geoMesaConfigDir, "geomesa-config.properties")

  logger.debug(s"Using data file '$configFile'")

  if (configFile.exists) {
    val inputStream = new FileInputStream(configFile)
    try {
      properties.load(inputStream)
    } finally {
      inputStream.close
    }
  }

  /**
   * Returns the specified property
   *
   * @param key
   * @return
   */
  def read(key: String): Option[String] = Option(properties.getProperty(key))

  /**
   * Stores the specified property. If calling multiple times, prefer @see persistAll
   *
   * @param key
   * @param value
   */
  def persist(key: String, value: String): Unit = {
    putOrRemove(key, value)
    persist(properties)
  }

  /**
   * Stores multiple properties at once.
   *
   * @param entries
   */
  def persistAll(entries: Map[String, String]): Unit = {
    entries.foreach { case (k, v) => putOrRemove(k, v) }
    persist(properties)
  }

  /**
   *
   * @param key
   * @param value
   */
  private def putOrRemove(key: String, value: String): Unit =
    if (value == null || value.isEmpty)
      properties.remove(key)
    else
      properties.setProperty(key, value)

  /**
   * Persists the props to a file
   *
   * @param properties
   */
  private def persist(properties: Properties): Unit =
    this.synchronized {
      val outputStream = new FileOutputStream(configFile)
      try {
        properties.store(outputStream, "GeoMesa configuration file")
      } finally {
        outputStream.close
      }
    }
}
