package chaossearch.utils.io

import akka.actor.{
  ActorSystem,
  ExtendedActorSystem
}
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.unixdomainsocket.scaladsl.UnixDomainSocket
import akka.stream.scaladsl.{
  Flow,
  Sink,
  Source
}
import akka.util.ByteString

import java.io.File
import java.net.InetSocketAddress

import jnr.unixsocket.UnixSocketAddress

import scala.concurrent.{
  ExecutionContextExecutor,
  Future
}
import scala.concurrent.duration.{
  Duration,
  SECONDS
}

object DomainSocket {
  case class UDSClientTransport(
    address : UnixSocketAddress
  ) extends ClientTransport {
    override def connectTo(
      host     : String,
      port     : Int
    )(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      implicit val executionContext = system.dispatcher

      UnixDomainSocket(system.asInstanceOf[ExtendedActorSystem])
        .outgoingConnection(
          remoteAddress  = address,
          localAddress   = None,
          halfClose      = false,
          connectTimeout = Duration.Inf : Duration
        ).mapMaterializedValue { case _ =>
          Future(OutgoingConnection(
            localAddress  = InetSocketAddress.createUnresolved("localhost", 12345),
            remoteAddress = InetSocketAddress.createUnresolved("localhost", 12345)
          ))
        }
    }
  }

  def createClientTransport(domainSock: String) : UDSClientTransport = {
    UDSClientTransport(new UnixSocketAddress(new File(domainSock)))
  }
}
