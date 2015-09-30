import java.io.IOException
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import java.time.Duration
import scala.math._
import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import spray.json.DefaultJsonProtocol
import slick.backend.DatabasePublisher
import akka.http.scaladsl.model.HttpEntity
import akka.util.ByteString
import akka.http.scaladsl.model.ContentTypes._
import spray.json._
import DefaultJsonProtocol._

trait Protocols2 extends DefaultJsonProtocol {
  implicit object TimestampFormat extends JsonFormat[Timestamp] {
    def write(obj: Timestamp) = JsNumber(obj.getTime)

    def read(json: JsValue) = json match {
      case JsNumber(time) => new Timestamp(time.toLong)

      case _ => throw new DeserializationException("Date expected")
    }
  }

  implicit object CapmasCapitalRequestReader extends RootJsonReader[CapmasCapitalRequest] {
    override def read(json: JsValue): CapmasCapitalRequest =
      json.asJsObject.getFields("id", "capmasId", "requestDate", "requestStatus", "strategyCode", "localCurr", "localAmt") match {
      case Seq(JsNumber(id), JsString(capmasId), JsNumber(requestDate), JsString(requestStatus), JsString(strategyCode),
        JsString(localCurr), JsNumber(localAmt)) =>
          CapmasCapitalRequest(id.toIntExact, capmasId, new Timestamp(requestDate.toLong), requestStatus, strategyCode, localCurr, localAmt)
      case _ => throw deserializationError(s"CapmasCapitalRequest expected")
    }
  }

  implicit val requestFormat = jsonFormat7(CapmasCapitalRequest.apply)
}

trait Service2 extends Protocols2 {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config: Config
  val logger: LoggingAdapter

  def retrieveData = {
    Source(AkkaService2.repo.getRequestsStream(AkkaService2.testDt))
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      path("streamTest") {
        (get) {
          val jsonRows = retrieveData.map { row => row.toJson.compactPrint }
          val jsonArray = Source.single("[\n") ++ jsonRows.map(_ + ",\n") ++ Source.single("]\n")

          complete(HttpEntity.Chunked.fromData(`application/json`, jsonArray.map(ByteString(_))))
        }
      }
    }
  }
}

object AkkaService2 extends App with Service2 {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  val sdf = new SimpleDateFormat("yyyy.MM.dd");
  val testDt = sdf.parse("2015.01.02")

  val db_type = System.getenv("unit_test_db_type")

  val repo = (db_type match {
    case "oracle" => new OracleCapmasCapitalRequestRepository()
    case _ => new HsqlCapmasCapitalRequestRepository()
  }).capmasCapitalRequestRepository

  Await.ready(repo.create, 10.seconds)

  val start = Instant.now()
  Await.ready(
    repo.insert(Seq.fill(20)(CapmasCapitalRequest(0, "1", new Timestamp(testDt.getTime()), "1", "C-FI", "001", 100))),
    100.seconds)
  val end = Instant.now()
  println("Records inserted in " + Duration.between(start, end).toMillis())

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
