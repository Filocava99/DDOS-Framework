package it.pps.ddos.grouping

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import it.pps.ddos.device.DeviceBehavior
import it.pps.ddos.device.DeviceProtocol.{Message, Subscribe, SubscribeAck, Timeout}

import scala.collection.immutable.List
import scala.concurrent.duration.FiniteDuration


trait GroupActor:
  // initial state
  def apply(g: Group[_,_]): Behavior[Message] =
    Behaviors.setup[Message] {
      context =>
        g.getSources().foreach(_ ! Subscribe(context.self))
        connecting(g.getSources(), g.copy())
    }

  def connecting(sources: ActorList, g: Group[_,_]): Behavior[Message] =
    Behaviors.withTimers[Message] { timer =>
      timer.startTimerAtFixedRate("connectingStateTimer", Timeout(), FiniteDuration(1, "second"))
      Behaviors.receivePartial { (context, message) =>
        (message, sources) match
          case (Timeout(), _) =>
            sources.foreach(_ ! Subscribe(context.self))
            Behaviors.same
          case (SubscribeAck(author), sources) if sources.length > 1 =>
            connecting(sources.filter(_ != author), g)
          case (SubscribeAck(author), sources) if sources.contains(author) =>
            timer.cancel("connectingStateTimer")
            active(g.getSources(), g, context)
          case _ =>
            Behaviors.same
      }
    }

  def active(sources: ActorList, g: Group[_,_], context: ActorContext[Message]): Behavior[Message] =
    Behaviors.receiveMessagePartial(getTriggerBehavior(context, g, sources).orElse(DeviceBehavior.getBasicBehavior(g, context)))

  def getTriggerBehavior[I,O](context: ActorContext[Message],
                              g: Group[I,O],
                              sources: ActorList): PartialFunction[Message, Behavior[Message]]