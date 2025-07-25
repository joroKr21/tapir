// {cat=Hello, World!; effects=Future; server=Pekko HTTP}: Exposing an endpoint using the Pekko HTTP server

//> using dep com.softwaremill.sttp.tapir::tapir-core:1.11.39
//> using dep com.softwaremill.sttp.tapir::tapir-pekko-http-server:1.11.39
//> using dep com.softwaremill.sttp.client4::core:4.0.0-RC3

package sttp.tapir.examples

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import sttp.client4.*
import sttp.tapir.*
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import sttp.client4.httpclient.HttpClientSyncBackend

@main def helloWorldPekkoServer(): Unit =
  implicit val actorSystem: ActorSystem = ActorSystem()
  import actorSystem.dispatcher

  // the endpoint: single fixed path input ("hello"), single query parameter
  // corresponds to: GET /hello?name=...
  val helloWorld: PublicEndpoint[String, Unit, String, Any] =
    endpoint.get.in("hello").in(query[String]("name")).out(stringBody)

  // converting an endpoint to a route (providing server-side logic); extension method comes from imported packages
  val helloWorldRoute: Route =
    PekkoHttpServerInterpreter().toRoute(helloWorld.serverLogicSuccess(name => Future.successful(s"Hello, $name!")))

  // starting the server
  val bindAndCheck = Http().newServerAt("localhost", 8080).bindFlow(helloWorldRoute).map { binding =>
    // testing
    val backend: SyncBackend = HttpClientSyncBackend()
    val result: String = basicRequest.response(asStringAlways).get(uri"http://localhost:8080/hello?name=Frodo").send(backend).body
    println("Got result: " + result)

    assert(result == "Hello, Frodo!")

    binding
  }

  val _ = Await.result(bindAndCheck.flatMap(_.terminate(1.minute)), 1.minute)
