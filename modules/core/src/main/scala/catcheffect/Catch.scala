/*
 * Copyright 2024 Valdemar Grange
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package catcheffect

import cats.effect._
import cats._
import cats.implicits._
import org.tpolecat.sourcepos._
import org.typelevel.vault._
import cats.data.EitherT
import cats.data.Kleisli

trait Raise[F[_], E] { self =>
  def monad: Monad[F]

  def raise[A](e: E)(implicit sp: SourcePos): F[A]

  def raiseIf[A](e: => E)(
      cond: Boolean
  )(implicit sp: SourcePos): F[Unit] =
    if (cond) monad.void(raise(e)) else monad.unit

  def fromOptionF[A](e: => E)(
      oa: F[Option[A]]
  )(implicit sp: SourcePos): F[A] =
    monad.flatMap(oa)(_.fold(raise[A](e))(monad.pure))

  def fromOption[A](e: => E)(
      oa: Option[A]
  )(implicit sp: SourcePos): F[A] =
    oa.fold(raise[A](e))(monad.pure)

  def contramap[E2](f: E2 => E): Raise[F, E2] = new Raise[F, E2] {
    def monad = self.monad
    override def raise[A](e: E2)(implicit sp: SourcePos): F[A] =
      self.raise(f(e))
  }

  def mapK[G[_]](fk: F ~> G)(implicit G: Monad[G]): Raise[G, E] = new Raise[G, E] {
    def monad = G
    override def raise[A](e: E)(implicit sp: SourcePos): G[A] =
      fk(self.raise(e))
  }
}

trait Handle[F[_], E] extends Raise[F, E] {
  def handleWith[A](fa: F[A])(f: E => F[A])(implicit sp: SourcePos): F[A]

  def attempt[A](
      fa: F[A]
  )(implicit sp: SourcePos): F[Either[E, A]] =
    handleWith(monad.map(fa)(Right(_): Either[E, A]))(e => monad.pure(Left(e)))

  def attemptK(implicit sp: SourcePos): F ~> EitherT[F, E, *] =
    new (F ~> EitherT[F, E, *]) {
      def apply[A](fa: F[A]): EitherT[F, E, A] = EitherT(attempt(fa))
    }
}

object Handle {
  implicit def forEitherT[F[_]: Monad, E]: Handle[EitherT[F, E, *], E] =
    new Handle[EitherT[F, E, *], E] {
      def monad = implicitly
      override def raise[A](e: E)(implicit sp: SourcePos): EitherT[F, E, A] =
        EitherT.leftT(e)

      override def handleWith[A](fa: EitherT[F, E, A])(
          f: E => EitherT[F, E, A]
      )(implicit sp: SourcePos): EitherT[F, E, A] =
        fa.handleErrorWith(f)
    }
}

/** An abstraction that can construct an instance of the [[Handle]] mtl algebra without any constraints.
  *
  * [[Catch]] provides the (cap)ability to introduce ad-hoc error channels which compose, like [[cats.data.EitherT]], but with no required
  * lifting (and un-lifting).
  *
  * [[Catch]], unlike mtl stacks, [[Catch]] does not require introuction of more monad transformers when layering.
  *
  * Nested handlers for [[Catch]] are well-defined.
  */
trait Catch[F[_]] { self =>
  def monad: Monad[F]

  /** A low-level method for requesting a new [[Handle]] instance and allocating a new error channel for any error type.
    *
    * This api is low-level and potentially unsafe. Any effect `F` that uses invokes `raise` but is not enclosed in a `handleWith` will lead
    * to a runtime error.
    *
    * If possible, prefer the safer variant [[use]] instead.
    */
  def allocated[E](implicit sp: SourcePos): F[Handle[F, E]]

  /** Within the scope of `f`, the use of [[Handle]] is well defined.
    *
    * The [[use]] method should be treated like a [[cats.effect.Resource]]'s use.
    *
    * Consider the following example usage:
    * {{{
    *   sealed trait AddUsersError
    *   object AddUsersError {
    *     case object NoUsers extends AddUsersError
    *     case class DuplicateUsers extends AddUsersError
    *   }
    *   def addUsers[F[_]](users: List[User])(implicit
    *     R: Raise[F, AddUsersError],
    *     userRepo: UserRepository[F]
    *   ): F[Unit] = {
    *     import AddUsersError._
    *     for {
    *       _ <- R.raiseIf(NoUsers)(users.isEmpty)
    *       userIds = users.map(_.id).toSet
    *       // A stream
    *       _ <- userRepo.getUsers(users.map(_.id))
    *         .evalMap(user => R.raiseIf(DuplicateUsers)(userIds.contains(user.id)))
    *         .compile.drain
    *       _ <- R.raiseIf(DuplicateUsers)(userIds.size != users.size)
    *       _ <- userRepo.add(users)
    *     } yield ()
    *   }
    *
    *   def addUsersRoute[F[_]: Catch](users: List[User])(implicit
    *     R: Raise[F, AddUsersError],
    *     userRepo: UserRepository[F]
    *   ) =
    *     Catch[F].use[AddUsersError]{ implicit R =>
    *       addUsers[F](users)
    *     }.flatMap{
    *       case Left(AddUsersError.NoUsers) => ???
    *       case Left(AddUsersError.DuplicateUsers) => ???
    *       case Right(_) => ???
    *     }
    * }}}
    */
  def use[E] = new Catch.PartiallyAppliedUse[F, E](self)
}

object Catch {
  def apply[F[_]](implicit F: Catch[F]): Catch[F] = F

  final class PartiallyAppliedUse[F[_], E](private val instance: Catch[F]) {
    def apply[A](
        f: Handle[F, E] => F[A]
    )(implicit sp: SourcePos): F[Either[E, A]] =
      instance.monad.flatMap(instance.allocated[E])(h => h.attempt(f(h))(sp))
  }

  final case class RaisedWithoutHandler[E](
      e: E,
      alloc: SourcePos,
      caller: SourcePos
  ) extends RuntimeException {
    override def getMessage(): String =
      s"""|I think you might have a resource leak.
          |You are trying to raise at ${caller},
          |but this operation occured outside of the scope of the handler.
          |Either widen the scope of your handler or don't leak the algebra.
          |The handler was defined at $alloc""".stripMargin
  }

  final case class RaisedInUncancellable[E](
      e: E,
      alloc: SourcePos,
      caller: SourcePos
  ) extends RuntimeException {
    override def getMessage(): String =
      s"""|"raise" was invoked at ${caller},
          |but this operation occured inside of an uncancellable block.
          |Catch does not known how to produce a value of type `F[A]` if it cannot cancel the fiber.
          |The handler was defined at $alloc""".stripMargin
  }

  def ioCatch: IO[Catch[IO]] =
    LocalForIOLocal
      .localForIOLocalDefault(Vault.empty)
      .map(implicit loc => fromLocal[IO])

  def kleisli[F[_]: Concurrent]: Catch[Kleisli[F, Vault, *]] =
    fromLocal[Kleisli[F, Vault, *]]

  /** Implements [[Catch]] via the cancellation of an effect `F`.
    *
    * Note that when an instance of [[Catch]] has been constructed like this, invoking any method on [[Handle]] inside an `uncancelable`
    * block is considered an error.
    *
    * Using cancellation to raise errors has some advantages and disadvantages.
    *
    * [[Catch]], unlike [[cats.data.EitherT]], does not have potentially dangerous semantics regarding resource safety (EitherT's left case
    * when releasing resources). For reference the default implementation of [[cats.effect.MonadCancel]]'s `guarenteeCase` will not invoke
    * the finalizer when you use [[cats.data.EitherT]] and the effect is in the `Left` case.
    */
  def fromLocal[F[_]](implicit F: Concurrent[F], L: Local[F, Vault]): Catch[F] = {
    new Catch[F] {
      override def monad: Monad[F] = F

      override def allocated[E](implicit sp0: SourcePos): F[Handle[F, E]] =
        Key.newKey[F, E => F[Unit]].map { key =>
          new Handle[F, E] {
            def monad = F

            override def raise[A](e: E)(implicit sp: SourcePos): F[A] =
              L.ask(sp)
                .flatMap(_.lookup(key) match {
                  case None => F.raiseError(RaisedWithoutHandler(e, sp0, sp))
                  case Some(f) =>
                    f(e) *> F.canceled *>
                      F.raiseError(RaisedInUncancellable(e, sp0, sp))
                })

            override def handleWith[A](fa: F[A])(f: E => F[A])(implicit sp: SourcePos): F[A] =
              F.deferred[E].flatMap { prom =>
                val program: F[A] =
                  L.local(fa)(_.insert(key, (x: E) => prom.complete(x).void))(sp)

                F.race(prom.get, program).flatMap(_.fold(f, F.pure(_)))
              }
          }
        }
    }
  }

  def fromHandle[F[_]](implicit F: Concurrent[F], H: Handle[F, Vault]): Catch[F] = {
    new Catch[F] {
      override def monad: Monad[F] = F

      override def allocated[E](implicit sp0: SourcePos): F[Handle[F, E]] =
        Key.newKey[F, E].map { k =>
          new Handle[F, E] {
            def monad: Monad[F] = F
            override def raise[A](e: E)(implicit sp: SourcePos): F[A] =
              H.raise(Vault.empty.insert(k, e))(sp)

            override def handleWith[A](fa: F[A])(f: E => F[A])(implicit sp: SourcePos): F[A] =
              H.handleWith(fa)(v => v.lookup(k).fold[F[A]](H.raise(v)(sp))(f))(sp)
          }
        }
    }
  }

  def eitherT[F[_]](implicit F: Concurrent[F]): Catch[EitherT[F, Vault, *]] = {
    type G[A] = EitherT[F, Vault, A]
    implicit val G = EitherT.catsDataMonadErrorForEitherT[F, Vault](F)
    implicit val handle = new Handle[G, Vault] {
      def monad: Monad[G] = G
      override def raise[A](e: Vault)(implicit sp: SourcePos): G[A] =
        G.raiseError[A](e)

      override def handleWith[A](fa: G[A])(f: Vault => G[A])(implicit
          sp: SourcePos
      ): G[A] =
        G.handleErrorWith(fa)(f)
    }
    fromHandle[G]
  }
}
