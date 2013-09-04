/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import scala.concurrent.duration._
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.remote.testconductor.RoleName
import akka.actor.Props
import akka.actor.Actor
import scala.util.control.NoStackTrace
import akka.remote.QuarantinedEvent
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import akka.actor.ActorRef
import akka.dispatch.sysmsg.Failed

object SurviveNetworkInstabilityMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")
  val seventh = role("seventh")
  val eighth = role("eighth")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("akka.remote.system-message-buffer-size=20")).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)

  deployOn(second, """"/parent/*" {
      remote = "@third@"
    }""")

  class Parent extends Actor {
    def receive = {
      case p: Props ⇒ sender ! context.actorOf(p)
    }
  }

  class RemoteChild extends Actor {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(500.millis, self, "boom")
    def receive = {
      case "boom" ⇒ throw new SimulatedException
      case x      ⇒ sender ! x
    }
  }

  class SimulatedException extends RuntimeException("Simulated") with NoStackTrace
}

class SurviveNetworkInstabilityMultiJvmNode1 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode2 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode3 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode4 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode5 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode6 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode7 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode8 extends SurviveNetworkInstabilitySpec

abstract class SurviveNetworkInstabilitySpec
  extends MultiNodeSpec(SurviveNetworkInstabilityMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender {

  import SurviveNetworkInstabilityMultiJvmSpec._

  //  muteMarkingAsUnreachable()
  //  muteMarkingAsReachable()

  override def expectedTestDuration = 3.minutes

  def assertUnreachable(subjects: RoleName*): Unit = {
    val expected = subjects.toSet map address
    awaitAssert(clusterView.unreachableMembers.map(_.address) must be(expected))
  }

  "A network partition tolerant cluster" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth, fifth)

      enterBarrier("after-1")
    }

    "heal after a broken pair" taggedAs LongRunningTest in within(30.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }
      enterBarrier("blackhole-2")

      runOn(first) { assertUnreachable(second) }
      runOn(second) { assertUnreachable(first) }
      runOn(third, fourth, fifth) {
        assertUnreachable(first, second)
      }

      enterBarrier("unreachable-2")

      runOn(first) {
        testConductor.passThrough(first, second, Direction.Both).await
      }
      enterBarrier("repair-2")

      // This test illustrates why we can't ignore gossip from unreachable aggregated
      // status. If all third, fourth, and fifth has been infected by first and second
      // unreachable they must accept gossip from first and second when their
      // broken connection has healed, otherwise they will be isolated forever.

      awaitAllReachable()
      enterBarrier("after-2")
    }

    "heal after one isolated node" taggedAs LongRunningTest in within(30.seconds) {
      val others = Vector(second, third, fourth, fifth)
      runOn(first) {
        for (other ← others) {
          testConductor.blackhole(first, other, Direction.Both).await
        }
      }
      enterBarrier("blackhole-3")

      runOn(first) { assertUnreachable(others: _*) }
      runOn(others: _*) {
        assertUnreachable(first)
      }

      enterBarrier("unreachable-3")

      runOn(first) {
        for (other ← others) {
          testConductor.passThrough(first, other, Direction.Both).await
        }
      }
      enterBarrier("repair-3")
      awaitAllReachable()
      enterBarrier("after-3")
    }

    "heal two isolated islands" taggedAs LongRunningTest in within(30.seconds) {
      val island1 = Vector(first, second)
      val island2 = Vector(third, fourth, fifth)
      runOn(first) {
        // split the cluster in two parts (first, second) / (third, fourth, fifth)
        for (role1 ← island1; role2 ← island2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole-4")

      runOn(island1: _*) {
        assertUnreachable(island2: _*)
      }
      runOn(island2: _*) {
        assertUnreachable(island1: _*)
      }

      enterBarrier("unreachable-4")

      runOn(first) {
        for (role1 ← island1; role2 ← island2) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("repair-4")
      awaitAllReachable()
      enterBarrier("after-4")
    }

    "heal after unreachable when ring is changed" taggedAs LongRunningTest in within(45.seconds) {
      val joining = Vector(sixth, seventh, eighth)
      val others = Vector(second, third, fourth, fifth)
      runOn(first) {
        for (role1 ← (joining :+ first); role2 ← others) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole-5")

      runOn(first) { assertUnreachable(others: _*) }
      runOn(others: _*) { assertUnreachable(first) }

      enterBarrier("unreachable-5")

      runOn(joining: _*) {
        cluster.join(first)

        // let them join and stabilize heartbeating
        Thread.sleep(5000)
      }

      enterBarrier("joined-5")

      runOn((joining :+ first): _*) { assertUnreachable(others: _*) }
      // others doesn't know about the joining nodes yet, no gossip passed through
      runOn(others: _*) { assertUnreachable(first) }

      enterBarrier("more-unreachable-5")

      runOn(first) {
        for (role1 ← (joining :+ first); role2 ← others) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }

      enterBarrier("repair-5")
      awaitAllReachable()
      awaitMembersUp(roles.size)
      enterBarrier("after-5")
    }

    "down and remove quarantined node" taggedAs LongRunningTest in within(45.seconds) {
      val others = Vector(first, third, fourth, fifth, sixth, seventh, eighth)

      runOn(second) {
        val sysMsgBufferSize = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].
          remoteSettings.SysMsgBufferSize
        val parent = system.actorOf(Props[Parent], "parent")
        // fill up the system message redeliver buffer with many failing actors
        for (_ ← 1 to sysMsgBufferSize + 1) {
          // remote deployment to third
          parent ! Props[RemoteChild]
          val child = expectMsgType[ActorRef]
          child ! "hello"
          expectMsg("hello")
          lastSender.path.address must be(address(third))
        }
      }
      runOn(third) {
        // after quarantined it will drop the Failed messages to deadLetters
        muteDeadLetters(classOf[Failed])(system)
      }
      enterBarrier("children-deployed")

      runOn(first) {
        for (role ← others)
          testConductor.blackhole(role, second, Direction.Both).await
      }
      enterBarrier("blackhole-6")

      runOn(third) {
        val p = TestProbe()
        // undelivered system messages in RemoteChild on third should trigger QuarantinedEvent
        system.eventStream.subscribe(p.ref, classOf[QuarantinedEvent])
        within(10.seconds) {
          p.expectMsgType[QuarantinedEvent].address must be(address(second))
        }
      }
      enterBarrier("quarantined")

      runOn(others: _*) {
        // second should be removed because of quarantine
        awaitAssert(clusterView.members.map(_.address) must not contain (address(second)))
      }

      enterBarrier("after-6")
    }

  }

}
