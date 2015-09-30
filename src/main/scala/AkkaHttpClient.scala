import scala.concurrent.Future
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.ByteString
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.unmarshalling._

object AkkaHttpClient extends App with Protocols2 with SprayJsonSupport {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
    Http().outgoingConnection("localhost", 9000)

  Source.single(HttpRequest(uri = "/streamTest"))
    .via(connectionFlow)
    .mapAsync(1)(response => Unmarshal(response.entity).to[CapmasCapitalRequest]) //???
    .runWith(Sink.head)

}