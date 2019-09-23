package io.casperlabs.storage.dag

import cats.Monad
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import io.casperlabs.casper.consensus.Block

final class IndexedDagStorage[F[_]: Monad](
    lock: Semaphore[F],
    underlying: DagStorage[F],
    idToBlocksRef: Ref[F, Map[Long, Block]],
    currentIdRef: Ref[F, Long]
) extends DagStorage[F] {

  /* May not return immutable representation for some implementations */
  def getRepresentation: F[DagRepresentation[F]] =
    for {
      _      <- lock.acquire
      result <- underlying.getRepresentation
      _      <- lock.release
    } yield result

  private[storage] def insert(block: Block): F[DagRepresentation[F]] =
    for {
      _          <- lock.acquire
      _          <- underlying.insert(block)
      _          <- lock.release
      updatedDag <- getRepresentation
    } yield updatedDag

  def index(block: Block): F[Block] =
    for {
      _         <- lock.acquire
      header    = block.header.get
      currentId <- currentIdRef.get
      nextId    = currentId + 1L
      dag       <- underlying.getRepresentation
      dependenciesMsg <- (header.parentHashes ++ header.justifications.map(_.latestBlockHash)).toList.distinct
                          .traverse(dag.lookup)
                          .map(_.flatten)
      maxRank = dependenciesMsg.foldLeft(-1L) {
        case (acc, b) => math.max(b.rank, acc)
      }
      rank = maxRank + 1
      nextCreatorSeqNum <- dag
                            .latestMessage(block.getHeader.validatorPublicKey)
                            .map(_.fold(0)(_.validatorMsgSeqNum) + 1)
      modifiedBlock = block
        .withHeader(
          header
            .withValidatorBlockSeqNum(nextCreatorSeqNum)
            .withRank(rank)
        )
      _ <- idToBlocksRef.update(_.updated(nextId, modifiedBlock))
      _ <- currentIdRef.set(nextId)
      _ <- lock.release
    } yield modifiedBlock

  def inject(index: Int, block: Block): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- idToBlocksRef.update(_.updated(index.toLong, block))
      _ <- underlying.insert(block)
      _ <- lock.release
    } yield ()

  def checkpoint(): F[Unit] = underlying.checkpoint()

  def clear(): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- underlying.clear()
      _ <- idToBlocksRef.set(Map.empty)
      _ <- currentIdRef.set(-1)
      _ <- lock.release
    } yield ()

  def close(): F[Unit] = underlying.close()

  def lookupById(id: Int): F[Option[Block]] =
    for {
      idToBlocks <- idToBlocksRef.get
    } yield idToBlocks.get(id.toLong)

  def lookupByIdUnsafe(id: Int): F[Block] =
    for {
      idToBlocks <- idToBlocksRef.get
    } yield idToBlocks(id.toLong)
}

object IndexedDagStorage {
  def apply[F[_]](implicit B: IndexedDagStorage[F]): IndexedDagStorage[F] = B

  def create[F[_]: Concurrent](underlying: DagStorage[F]): F[IndexedDagStorage[F]] =
    for {
      semaphore  <- Semaphore[F](1)
      idToBlocks <- Ref.of[F, Map[Long, Block]](Map.empty)
      currentId  <- Ref.of[F, Long](-1L)
    } yield new IndexedDagStorage[F](
      semaphore,
      underlying,
      idToBlocks,
      currentId
    )
}
