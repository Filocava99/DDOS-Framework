package it.pps.ddos.deployment

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.{Config, ConfigFactory}
import it.pps.ddos.device.DeviceProtocol.{Message, Subscribe}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.typed.{Cluster, Join}
import it.pps.ddos.deployment.graph.Graph
import it.pps.ddos.device.Device

import scala.collection.{immutable, mutable}
import scala.language.postfixOps

object Deployer:

  private final val DEFAULT_PORT = "0"
  private final val HOSTNAME =  "127.0.0.1"
  private final val SEED_NODES = immutable.List[String]("2551","2552")
  private case class ActorSysWithActor(actorSystem: ActorSystem[InternSpawn], numberOfActorSpawned: Int)
  private case class InternSpawn(id: String, behavior: Behavior[Message])
  private val orderedActorSystemRefList = mutable.ListBuffer.empty[ActorSysWithActor]
  private var cluster: Option[Cluster] = None
  private var devicesActorRefMap = Map.empty[String, ActorRef[Message]]
  private val deviceServiceKey = ServiceKey[Message]("DeviceService")

  def initSeedNodes(): Unit =
    ActorSystem(Behaviors.empty, "ClusterSystem", setupClusterConfig(SEED_NODES.head))
    ActorSystem(Behaviors.empty, "ClusterSystem", setupClusterConfig(SEED_NODES.last))
    
  def addNodes(numberOfNode: Int): Unit =
    for (i <- 1 to numberOfNode)
        val as = createActorSystem("ClusterSystem")
        orderedActorSystemRefList += ActorSysWithActor(as, 0)

  private def createActorSystem(id: String): ActorSystem[InternSpawn] =
    println("Creating actor system " + id)
    ActorSystem(Behaviors.setup(
      context =>
        Behaviors.receiveMessage { msg =>
          msg match
            case InternSpawn(id, behavior) =>
              val ar = context.spawn(behavior, "Device-" + id)
              devicesActorRefMap = Map((id, ar)) ++ devicesActorRefMap
              context.system.receptionist ! Receptionist.Register(deviceServiceKey, ar)
              Behaviors.same
        }
    ), id, setupClusterConfig(DEFAULT_PORT))

  private def deploy[T](devices: Device[T]*): Unit =
    for(dev <- devices)
      val buffer = orderedActorSystemRefList.filter(_.actorSystem.ref == getMinSpawnActorNode).head
      val newVal = new ActorSysWithActor(buffer.actorSystem, buffer.numberOfActorSpawned + 1)
      buffer.actorSystem.ref ! InternSpawn(dev.id, dev.behavior())
      orderedActorSystemRefList.update(orderedActorSystemRefList.indexOf(buffer), newVal)

  def deploy[T](devicesGraph: Graph[Device[T]]): Unit =
    val alreadyDeployed = mutable.Set[Device[T]]()
    devicesGraph @-> ((k, edges) => {
      if (!alreadyDeployed.contains(k))
        deploy(k)
        alreadyDeployed += k
      edges.filter(!alreadyDeployed.contains(_)).foreach {
        d =>
          alreadyDeployed += d
          deploy(d)
      }
    })
    devicesGraph @-> ((k, v) => v.map(it => devicesActorRefMap.get(it.id)).filter(_.isDefined).foreach(device => devicesActorRefMap(k.id).ref ! Subscribe(device.get.ref)))

  private def setupClusterConfig(port: String): Config =
    val hostname = HOSTNAME
    ConfigFactory.parseString(String.format("akka.remote.artery.canonical.hostname = \"%s\"%n", hostname)
      + String.format("akka.remote.artery.canonical.port=" + port + "%n")
      + String.format("akka.management.http.hostname=\"%s\"%n",hostname)
      + String.format("akka.management.http.port=" + port.replace("255", "855") + "%n")
      + String.format("akka.management.http.route-providers-read-only=%s%n", "false")
      + String.format("akka.remote.artery.advanced.tcp.outbound-client-hostname=%s%n", hostname)
      + String.format("akka.cluster.jmx.multi-mbeans-in-same-jvm = on"))
      .withFallback(ConfigFactory.load("application.conf"))

  private def getMinSpawnActorNode: ActorRef[InternSpawn] =
    orderedActorSystemRefList.minBy(x => x.numberOfActorSpawned).actorSystem