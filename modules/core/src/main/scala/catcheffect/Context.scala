package catcheffect

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

object Ask {
  def const[F[_], C](a: C)(implicit F: Applicative[F]): Ask[F, C] =
    new Ask[F, C] {
      override def applicative: Applicative[F] = F
      override def ask(implicit sp: SourcePos): F[C] = F.pure(a)
    }

  implicit def askForKleisli[F[_]: Applicative, C]: Ask[Kleisli[F, C, *], C] =
    new Ask[Kleisli[F, C, *], C] {
      override def applicative: Applicative[Kleisli[F, C, *]] = implicitly
      override def ask(implicit sp: SourcePos): Kleisli[F, C, C] = Kleisli.ask[F, C]
    }
}

trait Local[F[_], C] extends Ask[F, C] {
  def local[A](fa: F[A])(f: C => C)(implicit sp: SourcePos): F[A]

  def set[A](fa: F[A])(c: C)(implicit sp: SourcePos): F[A]

  def setK(implicit sp: SourcePos): C => F ~> F = c =>
    new (F ~> F) {
      def apply[A](fa: F[A]): F[A] = set(fa)(c)(sp)
    }
}

object Local {
  implicit def localForKleisli[F[_]: Monad, C]: Local[Kleisli[F, C, *], C] =
    new Local[Kleisli[F, C, *], C] {
      override def set[A](fa: Kleisli[F, C, A])(c: C)(implicit sp: SourcePos): Kleisli[F, C, A] =
        local[A](fa)(_ => c)

      override val applicative: Applicative[Kleisli[F, C, *]] = implicitly

      override def ask(implicit sp: SourcePos): Kleisli[F, C, C] =
        Kleisli.ask[F, C]

      override def local[A](fa: Kleisli[F, C, A])(f: C => C)(implicit
        sp: SourcePos
      ): Kleisli[F, C, A] =
        Kleisli.local(f)(fa)
    }
}

/**
 * An abstraction that can construct an instance of the [[Local]] mtl algebra without any constraints.
 *
 * You gain the (cap)ability to introduce a new instance of [[Local]] for any type, anywhere a [[Context]] is present.
 *
 * For programs written in [[cats.data.Kleisli]] or tagless final, [[Context]] provides the ability to introduce new [[Local]] instances
 * without having to lift any algebras or penalize your performance.
 *
 * Nested use of [[Context]] is well defined.
 */
trait Context[F[_]] {
  def monad: Monad[F]

  /**
   * The low-level primitive which makes up a [[Context]]. This api is low-level and potentially unsafe, so use it with caution.
   *
   * [[allocated]] is more powerful than [[use]] in that you can write a program with [[Local]] without having an initial value yet.
   * Allocated let's the user choose when to provide the initial value in either through `set` or a natural transformation `C => F ~> F`.
   *
   * If an effect `F` that depends on [[Local]] is not provided with an initial value, through either `set` or `setK`, a detailed runtime error will be raised.
   *
   * For instance, here is a good use-case for allocated.
   * {{{
   *   trait MyAlgebra[F[_]] {
   *     def doThing: F[Unit]
   *
   *     def mapK[G[_]](fk: F ~> G): MyAlgebra[G]
   *   }
   *   // Let it be an expensive operation to construct an instance of MyAlgebra,
   *   // such that we only wish to construct it once.
   *   def make[F[_]](loc: Local[F, Auth]): MyAlgebra[F] = ???
   *
   *   def processInput[F[_]](alg: MyAlgebra[F]) = ???
   *   // ...
   *   Context[F].allocated.flatMap{ loc =>
   *      val alg = make[F](loc)
   *
   *      def runAuthedRequest(auth: Auth) =
   *        processInput[F](alg.mapK(loc.setK(auth)))
   *
   *      startAuthedServer(runAuthedRequest)
   *   }
   * }}}
   */
  def allocated[C](implicit sp: SourcePos): F[Local[F, C]]

  /**
   * Within the scope of `f`, the use of [[Local]] is well defined.
   *
   * If you use this combinator like you would a [[cats.effect.Resource]]'s `use`, your program will be safe.
   */
  def use[C, A](c: C)(f: Local[F, C] => F[A])(implicit sp: SourcePos): F[A] =
    monad.flatMap(allocated[C]) { l =>
      l.set(f(l))(c)
    }
}

object Context {
  def apply[F[_]](implicit F: Context[F]): Context[F] = F

  final case class NoHandlerInScope(alloc: SourcePos, caller: SourcePos) extends RuntimeException {
    override def getMessage(): String =
      s"""|A Local operator was invoked outside of it's handler.
          |The Local operator was invoked at $caller.
          |The handler for this Local instance was defined at $alloc.
          |
          |You may have leaked the Local algebra by accident.
          |This can be casued by function signatures such as the following.
          |```
          |  trait Algebra[F[_]] {
          |    def doSomething: F[Unit]
          |  }
          |  def make[F[_]](loc: Local[F, A]): F[Algebra[F]] = ???
          |  // ...
          |  Context[F].use(initialValue) { loc => 
          |     make[F](loc)
          |  }.flatMap(algebra => algebra.doSomething)
          |```
          |
          |Either move your handler further out.
          |```
          |  Context[F].use(initialValue) { loc => 
          |     make[F](loc)
          |       .flatMap(algebra => algebra.doSomething)
          |  }
          |```
          |
          |Or use the low-level `Context[F].allocated` method.
          |```
          |  Context[F].allocated[A].flatMap { case (loc, ffk) => 
          |     val fk = ffk(initialValue)
          |     fk {
          |         make[F](loc).mapK(fk)
          |     }
          |  }.flatMap(algebra => algebra.doSomething)
          |```""".stripMargin
  }

  def ioContext: IO[Context[IO]] =
    LocalForIOLocal
      .localForIOLocalDefault(Vault.empty)
      .map(implicit loc => local[IO])

  def local[F[_]](implicit F: MonadThrow[F], U: Unique[F], L: Local[F, Vault]): Context[F] =
    new Context[F] {
      override def monad: Monad[F] = F

      override def allocated[C](implicit
        sp0: SourcePos
      ): F[Local[F, C]] =
        Key.newKey[F, C].map { key =>
          new Local[F, C] {

            override def set[A](fa: F[A])(c: C)(implicit sp: SourcePos): F[A] =
              L.local(fa)(_.insert(key, c))(sp)

            override def applicative: Applicative[F] = F

            override def ask(implicit sp: SourcePos): F[C] =
              L.ask(sp)
                .flatMap(_.lookup(key) match {
                  case Some(c) => F.pure(c)
                  case None    => F.raiseError(NoHandlerInScope(sp0, sp))
                })

            override def local[A](fa: F[A])(f: C => C)(implicit
              sp: SourcePos
            ): F[A] =
              ask(sp).flatMap(c => set(fa)(f(c))(sp))
          }
        }
    }

  def kleisli[F[_]: MonadThrow](implicit U: Unique[F]): Context[Kleisli[F, Vault, *]] = {
    implicit val uniqueInstance = new Unique[Kleisli[F, Vault, *]] {
      override def applicative: Applicative[Kleisli[F, Vault, *]] = implicitly
      override def unique: Kleisli[F, Vault, Unique.Token] = Kleisli.liftF(U.unique)
    }
    local[Kleisli[F, Vault, *]]
  }
}
