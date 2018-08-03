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

package com.intel.hibench.common.streaming.metrics

import java.io.{File, FileWriter}
import java.util.Date
import java.util.concurrent.{Executors, Future, TimeUnit}

import com.codahale.metrics.{Histogram, UniformReservoir}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer


class KafkaCollector(zkConnect: String, metricsTopic: String,
    outputDir: String, startingOffsetForEachPartition: Int, sampleNumber: Int, desiredThreadNum: Int) extends LatencyCollector {


  private val reservoir = if (sampleNumber > 0) new UniformReservoir(sampleNumber) else new UniformReservoir()
  private val histogram = new Histogram(reservoir)
  private val threadPool = Executors.newFixedThreadPool(desiredThreadNum)
  private val fetchResults = ArrayBuffer.empty[Future[FetchJobResult]]

  def start(): Unit = {

    val partitions = getPartitions(metricsTopic)

    println("Starting MetricsReader for kafka topic: " + metricsTopic)

    val remainder = if(sampleNumber < 0) 0 else sampleNumber % partitions.size
    for((partition, index) <- partitions.zipWithIndex){

      val fetchRecs = if(sampleNumber < 0) -1 else if(index < partitions.size - 1) sampleNumber / partitions.size else sampleNumber / partitions.size + remainder

      val job = new FetchJob(zkConnect, metricsTopic, partition, startingOffsetForEachPartition, fetchRecs, histogram)
      val fetchFeature = threadPool.submit(job)
      fetchResults += fetchFeature
    }

    threadPool.shutdown()
    threadPool.awaitTermination(30, TimeUnit.MINUTES)

    val finalResults = fetchResults.map(_.get()).reduce((a, b) => {
      val minTime = Math.min(a.minTime, b.minTime)
      val maxTime = Math.max(a.maxTime, b.maxTime)
      val count = a.count + b.count
      new FetchJobResult(minTime, maxTime, count)
    })

    report(finalResults.minTime, finalResults.maxTime, finalResults.count)
  }

  private def getPartitions(topic: String): Seq[Int] = {
    val props = new java.util.HashMap[String, Object]()
    props.put("acks", "all")
    props.put("batch.size", "16384")
    props.put("max.block.ms", "1000")
    props.put("linger.ms", "1000");
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")

    val kafkaProducer = new KafkaProducer[String, String](props)

    kafkaProducer
      .partitionsFor(topic)
      .asScala
      .map(_.partition())
  }


  private def report(minTime: Long, maxTime: Long, count: Long): Unit = {
    val outputFile = new File(outputDir, metricsTopic + ".csv")
    println(s"written out metrics to ${outputFile.getCanonicalPath}")
    val header = "time,count,throughput(msgs/s),max_latency(ms),mean_latency(ms),min_latency(ms)," +
        "stddev_latency(ms),p50_latency(ms),p75_latency(ms),p95_latency(ms),p98_latency(ms)," +
        "p99_latency(ms),p999_latency(ms)\n"
    val fileExists = outputFile.exists()
    if (!fileExists) {
      val parent = outputFile.getParentFile
      if (!parent.exists()) {
        parent.mkdirs()
      }
      outputFile.createNewFile()
    }
    val outputFileWriter = new FileWriter(outputFile, true)
    if (!fileExists) {
      outputFileWriter.append(header)
    }
    val time = new Date(System.currentTimeMillis()).toString
    val count = histogram.getCount
    val snapshot = histogram.getSnapshot
    val throughput = count * 1000 / (maxTime - minTime)
    outputFileWriter.append(s"$time,$count,$throughput," +
        s"${formatDouble(snapshot.getMax)}," +
        s"${formatDouble(snapshot.getMean)}," +
        s"${formatDouble(snapshot.getMin)}," +
        s"${formatDouble(snapshot.getStdDev)}," +
        s"${formatDouble(snapshot.getMedian)}," +
        s"${formatDouble(snapshot.get75thPercentile())}," +
        s"${formatDouble(snapshot.get95thPercentile())}," +
        s"${formatDouble(snapshot.get98thPercentile())}," +
        s"${formatDouble(snapshot.get99thPercentile())}," +
        s"${formatDouble(snapshot.get999thPercentile())}\n")
    outputFileWriter.close()
  }

  private def formatDouble(d: Double): String = {
    "%.3f".format(d)
  }

}


