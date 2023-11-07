package catapiler

import cats.effect._
import cats._
import cats.implicits._
import org.tpolecat.sourcepos._
import org.typelevel.vault._
import cats.data.EitherT
import cats.data.Kleisli

trait Raise[F[_], E] { self =>
  def raise[A](e: E)(implicit sp: SourcePos): F[A]

  def raiseIf[A](e: => E)(
      cond: Boolean
  )(implicit sp: SourcePos, F: Applicative[F]): F[Unit] =
    if (cond) raise(e).void else F.unit

  def fromOptionF[A](e: => E)(
      oa: F[Option[A]]
  )(implicit sp: SourcePos, F: Monad[F]): F[A] =
    oa.flatMap(_.fold(raise[A](e))(F.pure))

  def fromOption[A](e: => E)(
      oa: Option[A]
  )(implicit sp: SourcePos, F: Applicative[F]): F[A] =
    oa.fold(raise[A](e))(F.pure)

  def contramap[E2](f: E2 => E): Raise[F, E2] = new Raise[F, E2] {
    override def raise[A](e: E2)(implicit sp: SourcePos): F[A] =
      self.raise(f(e))
  }
}

trait Handle[F[_], E] extends Raise[F, E] { self =>
  def handleWith[A](fa: F[A])(f: E => F[A])(implicit sp: SourcePos): F[A]

  def attempt[A](
      fa: F[A]
  )(implicit sp: SourcePos, F: Applicative[F]): F[Either[E, A]] =
    handleWith(fa.map(Right(_): Either[E, A]))(e => F.pure(Left(e)))
}

object Handle {
  implicit def forEitherT[F[_]: Monad, E]: Handle[EitherT[F, E, *], E] =
    new Handle[EitherT[F, E, *], E] {
      override def raise[A](e: E)(implicit sp: SourcePos): EitherT[F, E, A] =
        EitherT.leftT(e)

      override def handleWith[A](fa: EitherT[F, E, A])(
          f: E => EitherT[F, E, A]
      )(implicit sp: SourcePos): EitherT[F, E, A] =
        fa.handleErrorWith(f)
    }
}

trait Catch[F[_]] { self =>
  val F: Monad[F]

  def allocated[E](implicit sp: SourcePos): F[Handle[F, E]]

  def use[E] = new Catch.PartiallyAppliedUse[F, E](self)
}

object Catch {
  def apply[F[_]](implicit F: Catch[F]): Catch[F] = F

  final class PartiallyAppliedUse[F[_], E](private val instance: Catch[F]) {
    def apply[A](
        f: Handle[F, E] => F[A]
    )(implicit sp: SourcePos): F[Either[E, A]] =
      instance.F.flatMap(instance.allocated[E])(h =>
        h.attempt(f(h))(sp, instance.F)
      )
  }

  final case class HandleWithInUncancellable(
      alloc: SourcePos,
      caller: SourcePos
  ) extends RuntimeException {
    override def getMessage(): String =
      s"""|"handleWith" was invoked at ${caller},
          |but this operation occured inside of an uncancellable block.
          |Catch does not known how to produce a value of type `F[A]` if it cannot cancel the fiber.""".stripMargin
  }

  final case class RaisedWithoutHandler[E](
      e: E,
      alloc: SourcePos,
      caller: SourcePos
  ) extends RuntimeException {
    override def getMessage(): String =
      s"""|I think you might have a resource leak.
          |You are trying to raise at ${caller}.
          |But this operation occured outside of the scope of the handler.
          |Either widen the scope of your handler or don't leak the algebra.
          |The handler was started at $alloc""".stripMargin
  }

  final case class RaisedInUncancellable[E](
      e: E,
      alloc: SourcePos,
      caller: SourcePos
  ) extends RuntimeException {
    override def getMessage(): String =
      s"""|"raise" was invoked at ${caller},
          |but this operation occured inside of an uncancellable block.
          |Catch does not known how to produce a value of type `F[A]` if it cannot cancel the fiber.""".stripMargin
  }

  def ioCatch: IO[Catch[IO]] =
    LocalForIOLocal
      .localForIOLocalDefault(Vault.empty)
      .map(implicit loc => fromLocal[IO])

  def kleisli[F[_]: Concurrent]: Catch[Kleisli[F, Vault, *]] =
    fromLocal[Kleisli[F, Vault, *]]

  def fromLocal[F[_]](implicit F: Concurrent[F], L: Local[F, Vault]) = {
    implicit val F0 = F
    new Catch[F] {
      override val F: Monad[F] = F0

      override def allocated[E](implicit sp0: SourcePos): F[Handle[F, E]] =
        Key.newKey[F, E => F[Unit]].map { key =>
          new Handle[F, E] {
            override def raise[A](e: E)(implicit sp: SourcePos): F[A] =
              L.ask(sp)
                .flatMap(_.lookup(key) match {
                  case Some(f) =>
                    f(e) *> F0.canceled *> F0
                      .raiseError(RaisedInUncancellable(e, sp0, sp))
                  case None => F0.raiseError(RaisedWithoutHandler(e, sp0, sp))
                })

            override def handleWith[A](fa: F[A])(f: E => F[A])(implicit
                sp: SourcePos
            ): F[A] =
              F0.deferred[E].flatMap { prom =>
                val program = L
                  .local(fa)(_.insert(key, (x: E) => prom.complete(x).void))(sp)

                F0.start(program)
                  .flatMap(_.join.flatMap {
                    case Outcome.Succeeded(fa) => fa
                    case Outcome.Errored(e)    => F0.raiseError(e)
                    case Outcome.Canceled() =>
                      prom.tryGet.flatMap {
                        case Some(e) => f(e)
                        case None =>
                          F0.canceled *> F0
                            .raiseError(HandleWithInUncancellable(sp0, sp))
                      }
                  })
              }
          }
        }
    }
  }

  def fromHandle[F[_]](implicit
      F: Concurrent[F],
      H: Handle[F, Vault]
  ): Catch[F] = {
    implicit val F0 = F
    new Catch[F] {
      override val F: Monad[F] = F0

      override def allocated[E](implicit sp0: SourcePos): F[Handle[F, E]] =
        Key.newKey[F, E].map { k =>
          new Handle[F, E] {
            override def raise[A](e: E)(implicit sp: SourcePos): F[A] =
              H.raise(Vault.empty.insert(k, e))(sp)

            override def handleWith[A](fa: F[A])(f: E => F[A])(implicit
                sp: SourcePos
            ): F[A] =
              H.handleWith(fa)(v => v.lookup(k).fold[F[A]](H.raise(v)(sp))(f))(
                sp
              )
          }
        }
    }
  }

  def eitherT[F[_]: Concurrent]: Catch[EitherT[F, Vault, *]] = {
    type G[A] = EitherT[F, Vault, A]
    implicit val handle = new Handle[G, Vault] {
      override def raise[A](e: Vault)(implicit sp: SourcePos): G[A] =
        EitherT.leftT(e)

      override def handleWith[A](fa: G[A])(f: Vault => G[A])(implicit
          sp: SourcePos
      ): G[A] =
        fa.handleErrorWith(f)
    }
    fromHandle[G]
  }
}
