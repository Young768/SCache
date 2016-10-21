package org.scache.deploy

/**
 * Created by frankfzw on 16-8-4.
 */

import org.scache.deploy.DeployMessages.{Heartbeat, RegisterClient}
import org.scache.io.ChunkedByteBuffer
import org.scache.network.netty.NettyBlockTransferService
import org.scache.scheduler.LiveListenerBus
import org.scache.storage.memory.{MemoryManager, StaticMemoryManager, UnifiedMemoryManager}
import org.scache.{MapOutputTracker, MapOutputTrackerMaster, MapOutputTrackerMasterEndpoint}
import org.scache.rpc._
import org.scache.serializer.{JavaSerializer, SerializerManager}
import org.scache.storage._
import org.scache.util.{IdGenerator, Logging, RpcUtils, ScacheConf}

import scala.collection.mutable

private class Master(
    val rpcEnv: RpcEnv,
    val hostname: String,
    conf: ScacheConf,
    isDriver: Boolean = true,
    isLocal: Boolean) extends ThreadSafeRpcEndpoint with Logging {
  val numUsableCores = conf.getInt("scache.cores", 1)

  val clientIdToInfo: mutable.HashMap[Int, ClientInfo] = new mutable.HashMap[Int, ClientInfo]()
  val hostnameToClientId: mutable.HashMap[String, Int] = new mutable.HashMap[String, Int]()


  conf.set("scache.master.port", rpcEnv.address.port.toString)

  val serializer = new JavaSerializer(conf)
  val serializerManager = new SerializerManager(serializer, conf)

  val mapOutputTracker = new MapOutputTrackerMaster(conf, isLocal)
  mapOutputTracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
    new MapOutputTrackerMasterEndpoint(rpcEnv, mapOutputTracker.asInstanceOf[MapOutputTrackerMaster], conf))
  logInfo("Registering " + MapOutputTracker.ENDPOINT_NAME)

  val useLegacyMemoryManager = conf.getBoolean("scache.memory.useLegacyMode", false)
  val memoryManager: MemoryManager =
      if (useLegacyMemoryManager) {
        new StaticMemoryManager(conf, numUsableCores)
      } else {
        UnifiedMemoryManager(conf, numUsableCores)
      }

  val blockTransferService = new NettyBlockTransferService(conf, hostname, numUsableCores)


  val blockManagerMasterEndpoint = rpcEnv.setupEndpoint(BlockManagerMaster.DRIVER_ENDPOINT_NAME,
    new BlockManagerMasterEndpoint(rpcEnv, isLocal, conf))
  val blockManagerMaster = new BlockManagerMaster(blockManagerMasterEndpoint, conf, isDriver)

  val blockManager = new BlockManager(ScacheConf.DRIVER_IDENTIFIER, rpcEnv, blockManagerMaster,
    serializerManager, conf, memoryManager, mapOutputTracker, blockTransferService, numUsableCores)

  blockManager.initialize()

  override def receive: PartialFunction[Any, Unit] = {
    case Heartbeat(id, rpcRef) =>
      logInfo(s"Receive heartbeat from ${id}: ${rpcRef}")
    case _ =>
      logError("Empty message received !")
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RegisterClient(hostname, port, ref) =>
      if (hostnameToClientId.contains(hostname)) {
        logWarning(s"The client ${hostname}:${hostnameToClientId(hostname)} has registered again")
        clientIdToInfo.remove(hostnameToClientId(hostname))
      }
      val clientId = Master.CLIENT_ID_GENERATOR.next
      val info = new ClientInfo(clientId, hostname, port, ref)
      if (hostnameToClientId.contains(hostname)) {
        clientIdToInfo -= hostnameToClientId(hostname)
      }
      hostnameToClientId.update(hostname, clientId)
      clientIdToInfo.update(clientId, info)
      logInfo(s"Register client ${hostname} with id ${clientId}")
      context.reply(clientId)
    case _ =>
      logError("Empty message received !")
  }


  def runTest: Unit = {
    val blockIda1 = new ScacheBlockId("test", 1, 1, 1, 1)
    val blockIda2 = new ScacheBlockId("test", 1, 1, 1, 2)
    val blockIda3 = new ScacheBlockId("test", 1, 1, 2, 1)
    val a1 = new Array[Byte](4000)
    val a2 = new Array[Byte](4000)
    val a3 = new Array[Byte](4000)

    // Putting a1, a2  and a3 in memory and telling master only about a1 and a2
    blockManager.putSingle(blockIda1, a1, StorageLevel.MEMORY_ONLY)
    blockManager.putSingle(blockIda2, a2, StorageLevel.MEMORY_ONLY)
    blockManager.putSingle(blockIda3, a3, StorageLevel.MEMORY_ONLY, tellMaster = false)

    // Checking whether blocks are in memory
    assert(blockManager.getSingle(blockIda1).isDefined, "a1 was not in blockManager")
    assert(blockManager.getSingle(blockIda2).isDefined, "a2 was not in blockManager")
    assert(blockManager.getSingle(blockIda3).isDefined, "a3 was not in blockManager")

    // Checking whether master knows about the blocks or not
    assert(blockManagerMaster.getLocations(blockIda1).size > 0, "master was not told about a1")
    assert(blockManagerMaster.getLocations(blockIda2).size > 0, "master was not told about a2")
    assert(blockManagerMaster.getLocations(blockIda3).size == 0, "master was told about a3")

    // Drop a1 and a2 from memory; this should be reported back to the master
    blockManager.dropFromMemoryTest(blockIda1)
    blockManager.dropFromMemoryTest(blockIda2)
    assert(blockManager.getSingle(blockIda1) == None, "a1 not removed from blockManager")
    assert(blockManager.getSingle(blockIda2) == None, "a2 not removed from blockManager")
    assert(blockManagerMaster.getLocations(blockIda1).size == 0, "master did not remove a1")
    assert(blockManagerMaster.getLocations(blockIda2).size == 0, "master did not remove a2")
  }


}

object Master extends Logging {
  private val CLIENT_ID_GENERATOR = new IdGenerator

  def main(args: Array[String]): Unit = {
    logInfo("Start Master")
    val conf = new ScacheConf()
    val SYSTEM_NAME = "scache.master"
    val arguments = new MasterArguments(args, conf)
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, arguments.host, arguments.port, conf)
    val masterEndpoint = rpcEnv.setupEndpoint("Master",
      new Master(rpcEnv, arguments.host, conf, true, arguments.isLocal))
    rpcEnv.awaitTermination()
  //   logInfo(conf.getInt("scache.memory", 1).toString)
  //   logInfo(conf.getString("scache.master", "localhost").toString)
  //   logInfo(conf.getBoolean("scache.boolean", false).toString)
  }
}

private[deploy] class ClientInfo(val id: Int, val host: String, val port: Int, val ref: RpcEndpointRef) extends Serializable
