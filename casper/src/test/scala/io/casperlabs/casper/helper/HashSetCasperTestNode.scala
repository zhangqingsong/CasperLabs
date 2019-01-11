package io.casperlabs.casper.helper

import java.nio.file.{Files, Paths}

import cats.{Applicative, ApplicativeError, Id, Monad, Traverse}
import cats.data.EitherT
import cats.effect.concurrent.Ref
import cats.effect.{Effect, Sync}
import cats.implicits._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.blockstorage.{BlockMetadata, LMDBBlockStore}
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.comm.CasperPacketHandler.{
  ApprovedBlockReceivedHandler,
  CasperPacketHandlerImpl,
  CasperPacketHandlerInternal
}
import io.casperlabs.casper.util.comm.TransportLayerTestImpl
import io.casperlabs.casper.util.rholang.{InterpreterUtil, RuntimeManager}
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm._
import io.casperlabs.comm.CommError.{CommErrT, ErrorHandler}
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp.HandleMessages.handle
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.p2p.effects.PacketHandler
import io.casperlabs.shared.Cell
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.execution.Scheduler

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Random
import monix.eval.Task

class HashSetCasperTestNode[F[_]](
    name: String,
    val local: PeerNode,
    tle: TransportLayerTestImpl[F],
    val genesis: BlockMessage,
    sk: Array[Byte],
    logicalTime: LogicalTime[F],
    implicit val errorHandlerEff: ErrorHandler[F],
    storageSize: Long,
    shardId: String = "rchain"
)(
    implicit scheduler: Scheduler,
    syncF: Sync[F],
    captureF: Capture[F],
    val abF: ToAbstractContext[F]
) {

  private val storageDirectory = Files.createTempDirectory(s"hash-set-casper-test-$name")

  implicit val logEff            = new LogStub[F]
  implicit val timeEff           = logicalTime
  implicit val connectionsCell   = Cell.unsafe[F, Connections](Connect.Connections.empty)
  implicit val transportLayerEff = tle
  implicit val metricEff         = new Metrics.MetricsNOP[F]
  val dir                        = BlockStoreTestFixture.dbDir
  implicit val blockStore =
    LMDBBlockStore.create[F](LMDBBlockStore.Config(path = dir, mapSize = storageSize))
  implicit val turanOracleEffect = SafetyOracle.turanOracle[F]
  implicit val rpConfAsk         = createRPConfAsk[F](local)

  val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()

  val runtimeManager                 = RuntimeManager.fromExecutionEngineService(casperSmartContractsApi)
  val defaultTimeout: FiniteDuration = FiniteDuration(1000, MILLISECONDS)

  val validatorId = ValidatorIdentity(Ed25519.toPublic(sk), sk, "ed25519")

  val approvedBlock = ApprovedBlock(candidate = Some(ApprovedBlockCandidate(block = Some(genesis))))

  implicit val labF = LastApprovedBlock.unsafe[F](Some(approvedBlock))

  val genesisBonds          = ProtoUtil.bonds(genesis)
  val initialLatestMessages = genesisBonds.map(_.validator -> genesis).toMap
  val dag = BlockDag.empty.copy(
    latestMessages = initialLatestMessages,
    dataLookup = Map(genesis.blockHash -> BlockMetadata.fromBlock(genesis)),
    topoSort = Vector(Vector(genesis.blockHash))
  )
  val postGenesisStateHash = ProtoUtil.postStateHash(genesis)
  implicit val casperEff = new MultiParentCasperImpl[F](
    runtimeManager,
    Some(validatorId),
    genesis,
    dag,
    postGenesisStateHash,
    shardId
  )

  implicit val multiparentCasperRef = MultiParentCasperRef.unsafe[F](Some(casperEff))

  val handlerInternal = new ApprovedBlockReceivedHandler(casperEff, approvedBlock)
  val casperPacketHandler =
    new CasperPacketHandlerImpl[F](Ref.unsafe[F, CasperPacketHandlerInternal[F]](handlerInternal))
  implicit val packetHandlerEff = PacketHandler.pf[F](
    casperPacketHandler.handle
  )

  def initialize(): F[Unit] =
    // pre-population removed from internals of Casper
    blockStore.put(genesis.blockHash, genesis) *>
      InterpreterUtil
        .validateBlockCheckpoint[F](
          genesis,
          dag,
          runtimeManager
        )
        .void

  def receive(): F[Unit] = tle.receive(p => handle[F](p, defaultTimeout), kp(().pure[F]))

  def tearDown(): Unit = {
    tearDownNode()
    dir.recursivelyDelete()
  }

  def tearDownNode(): Unit =
    blockStore.close()
}

object HashSetCasperTestNode {
  type Effect[A] = EitherT[Task, CommError, A]

  implicit val absF = new ToAbstractContext[Effect] {
    def fromTask[A](fa: Task[A]): Effect[A] = new MonadOps(fa).liftM[CommErrT]
  }

  def standaloneF[F[_]](
      genesis: BlockMessage,
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10
  )(
      implicit scheduler: Scheduler,
      errorHandler: ErrorHandler[F],
      syncF: Sync[F],
      captureF: Capture[F],
      absF: ToAbstractContext[F]
  ): F[HashSetCasperTestNode[F]] = {
    val name     = "standalone"
    val identity = peerNode(name, 40400)
    val tle =
      new TransportLayerTestImpl[F](identity, Map.empty[PeerNode, Ref[F, mutable.Queue[Protocol]]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]

    val result = new HashSetCasperTestNode[F](
      name,
      identity,
      tle,
      genesis,
      sk,
      logicalTime,
      errorHandler,
      storageSize
    )
    result.initialize.map(_ => result)
  }
  def standalone(genesis: BlockMessage, sk: Array[Byte], storageSize: Long = 1024L * 1024 * 10)(
      implicit scheduler: Scheduler
  ): HashSetCasperTestNode[Id] = {
    implicit val errorHandlerEff = errorHandler
    implicit val absId           = ToAbstractContext.idToAbstractContext

    standaloneF[Id](genesis, sk, storageSize)
  }
  def standaloneEff(genesis: BlockMessage, sk: Array[Byte], storageSize: Long = 1024L * 1024 * 10)(
      implicit scheduler: Scheduler
  ): HashSetCasperTestNode[Effect] =
    standaloneF[Effect](genesis, sk, storageSize)(
      scheduler,
      ApplicativeError_[Effect, CommError],
      syncEffectInstance,
      Capture[Effect],
      ToAbstractContext[Effect]
    ).value.unsafeRunSync.right.get

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      storageSize: Long = 1024L * 1024 * 10
  )(
      implicit scheduler: Scheduler,
      errorHandler: ErrorHandler[F],
      syncF: Sync[F],
      captureF: Capture[F],
      absF: ToAbstractContext[F]
  ): F[IndexedSeq[HashSetCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (1 to n).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))
    val msgQueues = peers
      .map(_ -> new mutable.Queue[Protocol]())
      .toMap
      .mapValues(Ref.unsafe[F, mutable.Queue[Protocol]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]

    val nodes =
      names
        .zip(peers)
        .zip(sks)
        .map {
          case ((n, p), sk) =>
            val tle = new TransportLayerTestImpl[F](p, msgQueues)
            new HashSetCasperTestNode[F](
              n,
              p,
              tle,
              genesis,
              sk,
              logicalTime,
              errorHandler,
              storageSize
            )
        }
        .toVector

    import Connections._
    //make sure all nodes know about each other
    val pairs = for {
      n <- nodes
      m <- nodes
      if n.local != m.local
    } yield (n, m)

    for {
      _ <- nodes.traverse(_.initialize).void
      _ <- pairs.foldLeft(().pure[F]) {
            case (f, (n, m)) =>
              f.flatMap(
                _ =>
                  n.connectionsCell.modify(_.addConn[F](m.local)(Monad[F], n.logEff, n.metricEff))
              )
          }
    } yield nodes
  }
  def network(
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      storageSize: Long = 1024L * 1024 * 10
  )(implicit scheduler: Scheduler): IndexedSeq[HashSetCasperTestNode[Id]] = {
    implicit val errorHandlerEff = errorHandler
    implicit val absId           = ToAbstractContext.idToAbstractContext

    networkF[Id](sks, genesis, storageSize)
  }
  def networkEff(
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      storageSize: Long = 1024L * 1024 * 10
  )(implicit scheduler: Scheduler): Effect[IndexedSeq[HashSetCasperTestNode[Effect]]] =
    networkF[Effect](sks, genesis, storageSize)(
      scheduler,
      ApplicativeError_[Effect, CommError],
      syncEffectInstance,
      Capture[Effect],
      ToAbstractContext[Effect]
    )

  val appErrId = new ApplicativeError[Id, CommError] {
    def ap[A, B](ff: Id[A => B])(fa: Id[A]): Id[B] = Applicative[Id].ap[A, B](ff)(fa)
    def pure[A](x: A): Id[A]                       = Applicative[Id].pure[A](x)
    def raiseError[A](e: CommError): Id[A] = {
      val errString = e match {
        case UnknownCommError(msg)                => s"UnknownCommError($msg)"
        case DatagramSizeError(size)              => s"DatagramSizeError($size)"
        case DatagramFramingError(ex)             => s"DatagramFramingError($ex)"
        case DatagramException(ex)                => s"DatagramException($ex)"
        case HeaderNotAvailable                   => "HeaderNotAvailable"
        case ProtocolException(th)                => s"ProtocolException($th)"
        case UnknownProtocolError(msg)            => s"UnknownProtocolError($msg)"
        case PublicKeyNotAvailable(node)          => s"PublicKeyNotAvailable($node)"
        case ParseError(msg)                      => s"ParseError($msg)"
        case EncryptionHandshakeIncorrectlySigned => "EncryptionHandshakeIncorrectlySigned"
        case BootstrapNotProvided                 => "BootstrapNotProvided"
        case PeerNodeNotFound(peer)               => s"PeerNodeNotFound($peer)"
        case PeerUnavailable(peer)                => s"PeerUnavailable($peer)"
        case MalformedMessage(pm)                 => s"MalformedMessage($pm)"
        case CouldNotConnectToBootstrap           => "CouldNotConnectToBootstrap"
        case InternalCommunicationError(msg)      => s"InternalCommunicationError($msg)"
        case TimeOut                              => "TimeOut"
        case _                                    => e.toString
      }

      throw new Exception(errString)
    }

    def handleErrorWith[A](fa: Id[A])(f: (CommError) => Id[A]): Id[A] = fa
  }

  implicit val syncEffectInstance = cats.effect.Sync.catsEitherTSync[Task, CommError]

  val errorHandler = ApplicativeError_.applicativeError[Id, CommError](appErrId)

  def randomBytes(length: Int): Array[Byte] = Array.fill(length)(Random.nextInt(256).toByte)

  def endpoint(port: Int): Endpoint = Endpoint("host", port, port)

  def peerNode(name: String, port: Int): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes), endpoint(port))

}