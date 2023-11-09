# Catch effect <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>
Catch effect is a small library for piling effectful algebras on top of eachother.

A small set of structures are introduced that allow construction of algebras akin to MTL, but without monad transformers and lifting effects.

Catch effect is built on top of [Vault](https://github.com/typelevel/vault), which is a map that can hold values of different types.

## Context
Context can create instances of `Local`, that represent running an effect given some input.
The MTL counterpart of `Context` is `Kleisli`/`ReaderT`.

If you are using `cats-effect` and you are familiar with `IOLocal`, then Context is very similar (and can be constructed on top of it).
However, `Context` allows spawning new ad-hoc `Local` instances for any effect type `F`, and not just `IO`.

You can construct an instance of `Context` in various ways, here is one using `cats-effect`.
```scala
Context.ioContext: IO[Context[IO]]
```

### Example
When you have an instance of `Context` in scope, new `Local` instances can be spawned.
```scala
type Auth = String
def authorizedRoute[F[_]: Console: Sync](L: Local[F, Auth]): F[Unit] = 
  for {
    user <- L.ask
    _ <- Console[F].println(s"doing user op with $user")
    _ <- L.local{
      L.ask.flatMap{ user =>
        Console[F].println(s"doing admin operation with $user")
      }
    }(_ => "admin")
    user <- L.ask
    _ <- Console[F].println(s"doing user op with $user again")
  } yield ()

def run[F[_]: Sync: Context: Console]: F[Unit] = 
  Context[F].use("user")(authorizedRoute[F])
```
Running the program yields:
```scala
Context.ioContext.flatMap(implicit C => run[IO])
// doing user op with user
// doing admin operation with admin
// doing user op with user again
```

### Other ways of constructing Context
There are several other ways to construct `Context` than to use `IO`.
If you are working in `Kleisli` a natural implementation exists.
```scala
Context.kleisli[IO]: Context[Kleisli[IO, Vault, *]]
```
Or for any instance of `Local[F, Vault]`.
```scala
implicit lazy val L: Local[IO, Vault] = ???
Context.local[IO]
```

## Catch
`Catch` responsible for throwing and catching errors.
The MTL counterpart of `Catch` is `EitherT`.
`Catch` can introduce new ad-hoc error channels that are independent of eachother.
There are various ways to construct a catch, but the simplest (given that you're working in `cats-effect`) is the following.
```scala
Catch.ioCatch: IO[Catch[IO]]
```

### Example
With an instance of `Catch` in scope, you can create locally scoped domain-specific errors.
```scala
sealed trait DomainError
case object MissingData extends DomainError
def domainFunction[F[_]: Console](R: Raise[F, DomainError])(implicit F: Async[F]) = 
  for {
    xs <- F.delay((0 to 10).toList)
    _ <- R.raiseIf(MissingData)(xs.nonEmpty)
    _ <- Console[F].println("Firing the missiles!")
  } yield ()

def doDomainEffect[F[_]: Catch: Async: Console]: F[Unit] = 
  Catch[F].use[DomainError](domainFunction[F]).flatMap{
    case Left(MissingData) => Console[F].println("Missing data!")
    case Right(()) => Console[F].println("Success!")
  }
```
Running this program yields:
```scala
Catch.ioCatch.flatMap(implicit C => doDomainEffect[IO])
// Missing data!
```

Nested `Catch`, `Raise` and `Handle` instances are well behaved when nested and can raise errors on their completely isolated error channels.

### Other ways of constructing Catch
1. Catch can occur an instance of `Handle[F, Vault]` (or `EitherT[F, Vault, A]`)
2. Catch can occur for an instance of `Local[F, Vault]` (or `Kleisli[F, Vault, A]`) and `Concurrent[F]` via cancellation

An interesting observation is that you can in fact construct `Catch` if you have `Context` and `Concurrent`.
```scala
import org.typelevel.vault._
trait MyError
def example[F[_]: Context: Concurrent] =
  Context[F].use(Vault.empty){ implicit L => 
    Catch.fromLocal[F].use[MyError]{ implicit R => 
      Concurrent[F].unit
    }
  }
```

## Unsafe use of algebras
Like any resources, the structures defined in this library are only well-behaved within a scope.
Use outside of their scope is considered an error and will be detected (like a resource leak).

Consider the following examples that lead to errors.
```scala
def catchError(ctch: Catch[IO]) =
  ctch.use[String](x => IO(x)).flatMap{ e => 
    e.traverse(_.raise("Oh no!"))
  }

def contextError(ctx: Context[IO]) = 
  ctx.use("initial")(x => IO(x)).flatMap{ L => 
    L.ask
  }
```
Running the Catch example:
```scala
Catch.ioCatch.flatMap(catchError)
// catcheffect.Catch$RaisedWithoutHandler: I think you might have a resource leak.
// You are trying to raise at README.md:163,
// but this operation occured outside of the scope of the handler.
// Either widen the scope of your handler or don't leak the algebra.
// The handler was defined at README.md:162
```
And then then Context example:
```scala
Context.ioContext.flatMap(contextError)
// catcheffect.Context$NoHandlerInScope: A Local operator was invoked outside of it's handler.
// The Local operator was invoked at README.md:169.
// The handler for this Local instance was defined at README.md:168.
// 
// You may have leaked the Local algebra by accident.
// This can be casued by function signatures such as the following.
// ```
//   trait Algebra[F[_]] {
//     def doSomething: F[Unit]
//   }
//   def make[F[_]](loc: Local[F, A]): F[Algebra[F]] = ???
//   // ...
//   Context[F].use(initialValue) { loc => 
//      make[F](loc)
//   }.flatMap(algebra => algebra.doSomething)
// ```
// 
// Either move your handler further out.
// ```
//   Context[F].use(initialValue) { loc => 
//      make[F](loc)
//        .flatMap(algebra => algebra.doSomething)
//   }
// ```
// 
// Or use the low-level `Context[F].allocated` method.
// ```
//   Context[F].allocated[A].flatMap { case (loc, ffk) => 
//      val fk = ffk(initialValue)
//      fk {
//          make[F](loc).mapK(fk)
//      }
//   }.flatMap(algebra => algebra.doSomething)
// ```
```
