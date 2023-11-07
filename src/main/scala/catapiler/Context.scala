package catapiler

import org.typelevel.vault._
import cats._
import cats.implicits._
import cats.effect._
import cats.Applicative
import org.tpolecat.sourcepos.SourcePos
import cats.data.Kleisli

trait Ask[F[_], C] {
    def applicative: Applicative[F]

    def ask(implicit sp: SourcePos): F[C]
}

trait Local[F[_], C] extends Ask[F, C] {
    def local[A](fa: F[A])(f: C => C)(implicit sp: SourcePos): F[A]
}

object Local {
    implicit def localFroKleisli[F[_]: Monad, C]: Local[Kleisli[F, C, *], C] = new Local[Kleisli[F, C, *], C] {
      override val applicative: Applicative[Kleisli[F,C,*]] = implicitly

      override def ask(implicit sp: SourcePos): Kleisli[F,C,C] = 
        Kleisli.ask[F, C]

      override def local[A](fa: Kleisli[F,C,A])(f: C => C)(implicit sp: SourcePos): Kleisli[F,C,A] = 
        Kleisli.local(f)(fa)
    }
}

trait Context[F[_]] {
  val F: Monad[F]

  def allocated[C](implicit sp: SourcePos): F[(Local[F, C], C => F ~> F)]

  def use[C, A](c: C)(f: Local[F, C] => F[A])(implicit sp: SourcePos): F[A] =
    F.flatMap(allocated[C]) { case (l, fk) =>
      fk(c) {
        f(l)
      }
    }
}

object Context {
  def apply[F[_]](implicit F: Context[F]): Context[F] = F

  def ioContext: IO[Context[IO]] =
    LocalForIOLocal.localForIOLocalDefault(Vault.empty).map(implicit loc => local[IO])

  def local[F[_]](implicit F: Concurrent[F], L: Local[F, Vault]): Context[F] = {
    implicit val F0 = F
    new Context[F] {
      override val F: Monad[F] = F0

      override def allocated[C](implicit sp: SourcePos): F[(Local[F, C], C => F ~> F)] =
        Key.newKey[F, C].map { key =>
          val l = new Local[F, C] {
            override def applicative: Applicative[F] = F

            override def ask(implicit sp: SourcePos): F[C] =
              L.ask.flatMap(_.lookup(key) match {
                  case Some(c) => F.pure(c)
                  case None    => ???
                })

            override def local[A](fa: F[A])(f: C => C)(implicit sp: SourcePos): F[A] =
              L.local(fa) { vault =>
                vault.lookup(key) match {
                  case Some(c) => vault.insert(key, f(c))
                  case None    => ???
                }
              }
          }

          val g = (c: C) =>
            new (F ~> F) {
              override def apply[A](fa: F[A]): F[A] = L.local(fa)(_.insert(key, c))
            }

          (l, g)
        }
    }
  }

  def kleisli[F[_]: Concurrent]: Context[Kleisli[F, Vault, *]] =
    local[Kleisli[F, Vault, *]]
}