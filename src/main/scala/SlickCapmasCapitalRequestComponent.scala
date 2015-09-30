import java.util.Date
import scala.reflect.runtime.universe
import scaldi.Injectable.{ identified, inject }
import slick.lifted.Tag
import java.sql.Timestamp
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scaldi.Injector
import slick.driver.JdbcProfile
import slick.backend.DatabasePublisher
import scaldi.Module
import com.typesafe.slick.driver.oracle.OracleDriver.api.{ Database => OracleDatabase }
import slick.driver.HsqldbDriver.api.{ Database => HsqlDatabase }

case class CapmasCapitalRequest(
  id: Int,
  capmasId: String,
  requestDate: java.sql.Timestamp,
  requestStatus: String,
  strategyCode: String,
  localCurr: String,
  localAmt: BigDecimal)

trait ConfigInjectorComponent {
  implicit val inj: Injector
}

trait CapmasCapitalRequestRepoComponent {
  val capmasCapitalRequestRepository: CapmasCapitalRequestRepo
  trait CapmasCapitalRequestRepo {
    def getRequests(date: Date): Future[Seq[CapmasCapitalRequest]]
    def getRequestsStream(date: Date): DatabasePublisher[CapmasCapitalRequest]
    def deleteRequests(date: Date): Future[Int]
    def insert(request: CapmasCapitalRequest): Future[Int]
    def insert(requests: Seq[CapmasCapitalRequest]): Future[Option[Int]]
    def create: Future[Unit]
    def drop: Future[Unit]
  }
}

trait DriverComponent {
  val driver: JdbcProfile

  import driver.api._

  implicit lazy val JavaUtilDateMapper = MappedColumnType.base[java.util.Date, java.sql.Timestamp](
    { u => new java.sql.Timestamp(u.getTime) },
    { s => new java.util.Date(s.getTime) })

}

trait SlickCapmasCapitalRequestRepoComponent extends CapmasCapitalRequestRepoComponent { this: ConfigInjectorComponent with DriverComponent =>

  import driver.api._

  lazy val db = inject[Database](identified by 'db)

  // Require lazy modifier here in order to get the file path value from JsonFilePathComponent trait after initialisation
  override lazy val capmasCapitalRequestRepository: CapmasCapitalRequestRepo = new SlickCapmasCapitalRequestRepo

  private class SlickCapmasCapitalRequestRepo extends CapmasCapitalRequestRepo {

    class CapmasCapitalRequests(tag: Tag) extends Table[CapmasCapitalRequest](tag, "CAPMAS_CAPITAL_REQUEST") {
      def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
      def capmasId = column[String]("CAPMAS_REQUEST_ID")
      def requestDate = column[Timestamp]("REQUEST_DATE")
      def requestStatus = column[String]("REQUEST_STATUS")
      def strategyCode = column[String]("STRATEGY_CODE")
      def localCurr = column[String]("LCL_CURR")
      def localAmt = column[BigDecimal]("LCL_AMT")
      def * = (id, capmasId, requestDate, requestStatus, strategyCode, localCurr, localAmt) <> (CapmasCapitalRequest.tupled, CapmasCapitalRequest.unapply)
    }

    val capmasRequests = TableQuery[CapmasCapitalRequests]

    private val capmasRequestsAutoInc = capmasRequests returning capmasRequests.map(_.id) into {
      case (p, id) => p.copy(id = id)
    }

    override def create = db.run(capmasRequests.schema.create)

    override def drop = db.run(capmasRequests.schema.drop)

    override def getRequests(date: Date): Future[Seq[CapmasCapitalRequest]] = {
      // Not sure why the implicit conversion is not working with === here
      val dt = new Timestamp(date.getTime())
      db.run(capmasRequests.filter(_.requestDate === dt).result)
    }

    override def getRequestsStream(date: Date): DatabasePublisher[CapmasCapitalRequest] = {
      val dt = new Timestamp(date.getTime())
      db.stream(capmasRequests.filter(_.requestDate === dt).result)
    }

    override def deleteRequests(date: Date): Future[Int] = {
      val deleteQuery: Query[CapmasCapitalRequests, CapmasCapitalRequest, Seq] =
        capmasRequests.filter(_.requestDate === new Timestamp(date.getTime()))
      db.run(deleteQuery.delete)
    }

    override def insert(request: CapmasCapitalRequest): Future[Int] = {
      db.run((capmasRequests returning capmasRequests.map(_.id)) += request)
    }

    override def insert(requests: Seq[CapmasCapitalRequest]): Future[Option[Int]] = {
      db.run(capmasRequests ++= requests)
    }

  }
}

abstract class DataAccessLayer(val driver: JdbcProfile) extends ConfigInjectorComponent
  with DriverComponent
  with CapmasCapitalRequestRepoComponent

trait OracleDriverComponent extends DriverComponent {
  override val driver: JdbcProfile = com.typesafe.slick.driver.oracle.OracleDriver
}

trait HsqlDriverComponent extends DriverComponent {
  override val driver: JdbcProfile = slick.driver.HsqldbDriver
}

  trait OracleUnitTestConfigComponent extends ConfigInjectorComponent with OracleDriverComponent {
    import driver.api._
    override val inj = new Module {
      bind[Database] identifiedBy 'db to OracleDatabase.forConfig("RBS2")
    }
  }
  trait HsqlUnitTestConfigComponent extends ConfigInjectorComponent with HsqlDriverComponent {
    import driver.api._
    override val inj = new Module {
      bind[Database] identifiedBy 'db to HsqlDatabase.forConfig("UnitTest")
    }
  }

  class OracleCapmasCapitalRequestRepository extends SlickCapmasCapitalRequestRepoComponent with OracleUnitTestConfigComponent
  class HsqlCapmasCapitalRequestRepository extends SlickCapmasCapitalRequestRepoComponent with HsqlUnitTestConfigComponent
