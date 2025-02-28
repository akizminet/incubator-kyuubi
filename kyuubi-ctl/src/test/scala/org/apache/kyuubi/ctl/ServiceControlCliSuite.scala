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

package org.apache.kyuubi.ctl

import java.io.{OutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiFunSuite}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.{HA_ZK_NAMESPACE, HA_ZK_QUORUM}
import org.apache.kyuubi.ha.client.{ServiceDiscovery, ServiceNodeInfo, ZooKeeperClientProvider}
import org.apache.kyuubi.zookeeper.{EmbeddedZookeeper, ZookeeperConf}

trait TestPrematureExit {
  suite: KyuubiFunSuite =>

  private val noOpOutputStream = new OutputStream {
    def write(b: Int) = {}
  }

  /** Simple PrintStream that reads data into a buffer */
  private class BufferPrintStream extends PrintStream(noOpOutputStream) {
    var lineBuffer = ArrayBuffer[String]()
    // scalastyle:off println
    override def println(line: Any): Unit = {
      lineBuffer += line.toString
    }
    // scalastyle:on println
  }

  /** Returns true if the script exits and the given search string is printed. */
  private[kyuubi] def testPrematureExit(
      input: Array[String],
      searchString: String,
      mainObject: CommandLineUtils = ServiceControlCli) : Unit = {
    val printStream = new BufferPrintStream()
    mainObject.printStream = printStream

    @volatile var exitedCleanly = false
    val original = mainObject.exitFn
    mainObject.exitFn = (_) => exitedCleanly = true
    try {
      @volatile var exception: Exception = null
      val thread = new Thread {
        override def run() = try {
          mainObject.main(input)
        } catch {
          // Capture the exception to check whether the exception contains searchString or not
          case e: Exception => exception = e
        }
      }
      thread.start()
      thread.join()
      if (exitedCleanly) {
        val joined = printStream.lineBuffer.mkString("\n")
        assert(joined.contains(searchString))
      } else {
        assert(exception != null)
        if (!exception.getMessage.contains(searchString)) {
          throw exception
        }
      }
    } finally {
      mainObject.exitFn = original
    }
  }
}

class ServiceControlCliSuite extends KyuubiFunSuite with TestPrematureExit {
  import ServiceControlCli._
  import ServiceDiscovery._
  import ZooKeeperClientProvider._

  val zkServer = new EmbeddedZookeeper()
  val conf: KyuubiConf = KyuubiConf()
  var envZkNamespaceProperty: String = System.getProperty(HA_ZK_NAMESPACE.key)
  val namespace = "kyuubiserver"
  val host = "localhost"
  val port = "10000"
  val user = "kyuubi"
  val ctl = new ServiceControlCli()
  val counter = new AtomicInteger(0)

  override def beforeAll(): Unit = {
    setSystemProperty(HA_ZK_NAMESPACE.key, namespace)
    conf.set(ZookeeperConf.ZK_CLIENT_PORT, 0)
    zkServer.initialize(conf)
    zkServer.start()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    setSystemProperty(HA_ZK_NAMESPACE.key, envZkNamespaceProperty)
    conf.unset(KyuubiConf.SERVER_KEYTAB)
    conf.unset(KyuubiConf.SERVER_PRINCIPAL)
    conf.unset(HA_ZK_QUORUM)
    zkServer.stop()
    super.afterAll()
  }

  private def getUniqueNamespace(): String = {
    s"${namespace}_${"%02d".format(counter.getAndIncrement())}"
  }

  /** Get the rendered service node info without title */
  private def getRenderedNodesInfoWithoutTitle(nodesInfo: Seq[ServiceNodeInfo],
      verbose: Boolean): String = {
    val renderedInfo = renderServiceNodesInfo("", nodesInfo, verbose)
    if (verbose) {
      renderedInfo.substring(renderedInfo.indexOf("|"))
    } else {
      renderedInfo
    }
  }

  test("test expose to same namespace or not specified namespace") {
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, namespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)

    val args = Array(
      "create", "server",
      "--zk-quorum", zkServer.getConnectString,
      "--namespace", namespace,
      "--host", host,
      "--port", port
    )
    testPrematureExit(args, "Only support expose Kyuubi server instance to another domain")

    val args2 = Array(
      "create", "server",
      "--zk-quorum", zkServer.getConnectString,
      "--host", host,
      "--port", port
    )
    testPrematureExit(args2, "Zookeeper namespace is not specified")
  }

  test("test render zookeeper service node info") {
    val title = "test render"
    val nodes = Seq(
      ServiceNodeInfo("/kyuubi", "serviceNode", "localhost", 10000, Some("version"), None))
    val renderedInfo = renderServiceNodesInfo(title, nodes, true)
    val expected = {
      s"\n               $title               " +
      """
        |+----------+----------+----------+----------+
        ||Namespace |   Host   |   Port   | Version  |
        |+----------+----------+----------+----------+
        || /kyuubi  |localhost |  10000   | version  |
        |+----------+----------+----------+----------+
        |1 row(s)
        |""".stripMargin
    }
    assert(renderedInfo == expected)
    assert(renderedInfo.contains(getRenderedNodesInfoWithoutTitle(nodes, true)))
  }

  test("test expose zk service node to another namespace") {
    val uniqueNamespace = getUniqueNamespace()
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, uniqueNamespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)
    System.setProperty(HA_ZK_NAMESPACE.key, uniqueNamespace)

    withZkClient(conf) { framework =>
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10000")
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10001")

      val newNamespace = getUniqueNamespace()
      val args = Array(
        "create", "server",
        "--zk-quorum", zkServer.getConnectString,
        "--namespace", newNamespace
      )

      val expectedCreatedNodes = Seq(
        ServiceNodeInfo(s"/$newNamespace", "", "localhost", 10000, Some(KYUUBI_VERSION), None),
        ServiceNodeInfo(s"/$newNamespace", "", "localhost", 10001, Some(KYUUBI_VERSION), None)
      )

      testPrematureExit(args, getRenderedNodesInfoWithoutTitle(expectedCreatedNodes, false))
      val znodeRoot = s"/$newNamespace"
      val children = framework.getChildren.forPath(znodeRoot).asScala.sorted
      assert(children.size == 2)

      assert(children.head.startsWith(
        s"serviceUri=localhost:10000;version=$KYUUBI_VERSION;sequence="))
      assert(children.last.startsWith(
        s"serviceUri=localhost:10001;version=$KYUUBI_VERSION;sequence="))
      children.foreach { child =>
        framework.delete().forPath(s"""$znodeRoot/$child""")
      }
    }
  }

  test("test get zk namespace for different service type") {
    val arg1 = Array(
      "list", "server",
      "--zk-quorum", zkServer.getConnectString,
      "--namespace", namespace
    )
    assert(getZkNamespace(new ServiceControlCliArguments(arg1)) == s"/$namespace")

    val arg2 = Array(
      "list", "engine",
      "--zk-quorum", zkServer.getConnectString,
      "--namespace", namespace,
      "--user", user
    )
    assert(getZkNamespace(new ServiceControlCliArguments(arg2)) == s"/${namespace}_USER/$user")
  }

  test("test list zk service nodes info") {
    val uniqueNamespace = getUniqueNamespace()
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, uniqueNamespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)

    withZkClient(conf) { framework =>
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10000")
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10001")

      val args = Array(
        "list", "server",
        "--zk-quorum", zkServer.getConnectString,
        "--namespace", uniqueNamespace
      )

      val expectedNodes = Seq(
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10000, Some(KYUUBI_VERSION), None),
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10001, Some(KYUUBI_VERSION), None)
      )

      testPrematureExit(args, getRenderedNodesInfoWithoutTitle(expectedNodes, false))
    }
  }

  test("test get zk service nodes info") {
    val uniqueNamespace = getUniqueNamespace()
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, uniqueNamespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)

    withZkClient(conf) { framework =>
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10000")
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10001")

      val args = Array(
        "get", "server",
        "--zk-quorum", zkServer.getConnectString,
        "--namespace", uniqueNamespace,
        "--host", "localhost",
        "--port", "10000"
      )

      val expectedNodes = Seq(
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10000, Some(KYUUBI_VERSION), None)
      )

      testPrematureExit(args, getRenderedNodesInfoWithoutTitle(expectedNodes, false))
    }
  }

  test("test delete zk service nodes info") {
    val uniqueNamespace = getUniqueNamespace()
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, uniqueNamespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)

    withZkClient(conf) { framework =>
      withZkClient(conf) { zc =>
        createServiceNode(conf, zc, uniqueNamespace, "localhost:10000", external = true)
        createServiceNode(conf, zc, uniqueNamespace, "localhost:10001", external = true)
      }

      val args = Array(
        "delete", "server",
        "--zk-quorum", zkServer.getConnectString,
        "--namespace", uniqueNamespace,
        "--host", "localhost",
        "--port", "10000"
      )

      val expectedDeletedNodes = Seq(
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10000, Some(KYUUBI_VERSION), None)
      )

      testPrematureExit(args, getRenderedNodesInfoWithoutTitle(expectedDeletedNodes, false))
    }
  }

  test("test verbose output") {
    val uniqueNamespace = getUniqueNamespace()
    conf
      .unset(KyuubiConf.SERVER_KEYTAB)
      .unset(KyuubiConf.SERVER_PRINCIPAL)
      .set(HA_ZK_QUORUM, zkServer.getConnectString)
      .set(HA_ZK_NAMESPACE, uniqueNamespace)
      .set(KyuubiConf.FRONTEND_BIND_PORT, 0)

    withZkClient(conf) { framework =>
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10000")
      createServiceNode(conf, framework, uniqueNamespace, "localhost:10001")

      val args = Array(
        "list", "server",
        "--zk-quorum", zkServer.getConnectString,
        "--namespace", uniqueNamespace,
        "--verbose"
      )

      val expectedNodes = Seq(
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10000, Some(KYUUBI_VERSION), None),
        ServiceNodeInfo(s"/$uniqueNamespace", "", "localhost", 10001, Some(KYUUBI_VERSION), None)
      )

      testPrematureExit(args, getRenderedNodesInfoWithoutTitle(expectedNodes, true))
    }
  }
}
