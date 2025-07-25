// {cat=Error handling; effects=Future; server=Pekko HTTP; json=circe}: Error and successful outputs

//> using dep com.softwaremill.sttp.tapir::tapir-core:1.11.39
//> using dep com.softwaremill.sttp.tapir::tapir-pekko-http-server:1.11.39
//> using dep com.softwaremill.sttp.tapir::tapir-json-circe:1.11.39
//> using dep org.apache.pekko::pekko-http:1.0.1
//> using dep org.apache.pekko::pekko-stream:1.0.3
//> using dep com.softwaremill.sttp.client4::core:4.0.0-RC3

package sttp.tapir.examples.errors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.tapir.generic.auto.*
import sttp.tapir.*
import sttp.tapir.server.pekkohttp.*
import sttp.tapir.json.circe.*
import io.circe.generic.auto.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

@main def errorOutputsPekkoServer(): Unit =
  implicit val actorSystem: ActorSystem = ActorSystem()
  import actorSystem.dispatcher

  // the endpoint description
  case class Result(result: Int)

  val errorOrJson: PublicEndpoint[Int, String, Result, Any] =
    endpoint.get
      .in(query[Int]("amount"))
      .out(jsonBody[Result])
      .errorOut(stringBody)

  // converting an endpoint to a route
  val errorOrJsonRoute: Route = PekkoHttpServerInterpreter().toRoute(errorOrJson.serverLogic {
    case x if x < 0 => Future.successful(Left("Invalid parameter, smaller than 0!"))
    case x          => Future.successful(Right(Result(x * 2)))
  })

  // starting the server
  val bindAndCheck = Http().newServerAt("localhost", 8080).bindFlow(errorOrJsonRoute).map { binding =>
    // testing
    val backend: SyncBackend = HttpClientSyncBackend()

    val result1: Either[String, String] = basicRequest.get(uri"http://localhost:8080?amount=-5").send(backend).body
    println("Got result (1): " + result1)
    assert(result1 == Left("Invalid parameter, smaller than 0!"))

    val result2: Either[String, String] = basicRequest.get(uri"http://localhost:8080?amount=21").send(backend).body
    println("Got result (2): " + result2)
    assert(result2 == Right("""{"result":42}"""))

    binding
  }

  try
    val _ = Await.result(bindAndCheck.flatMap(_.terminate(1.minute)), 1.minute)
  finally
    val _ = actorSystem.terminate()
