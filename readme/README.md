# Catch effect <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>
Catch effect is a small library that facilitates MTL-like programs, without the need for monad transformers, but just an effect type like `IO`.

Catch effect is built on top of [Vault](https://github.com/typelevel/vault), which is a map that can hold values of different types.

## Installation
```scala
"io.github.valdemargr" %% "catch-effect" % "@VERSION@"
```

## Context
Context can create instances of `Local`.
`Local` provides the ability to run an effect given some input and modify the input.

The MTL counterpart of `Context` is `Kleisli`/`ReaderT`, (`A => F[B]` for the uninitiated).
`Context` is a building block for the more interesting `Catch` sturcture described below.

If you are using `cats-effect` and you are familiar with `IOLocal`, then `Context` should be feel familiar.

You can construct an instance of `Context` in various ways, here is one using `cats-effect`.
```scala mdoc:invisible
import catcheffect._
import cats.effect._
import org.typelevel.vault._
import cats.data._
```
```scala mdoc:silent
Context.ioContext: IO[Context[IO]]
```

### Example
When you have an instance of `Context` in scope, new `Local` instances can be spawned on the fly.
```scala mdoc:invisible
import cats._
import cats.effect._
import cats.implicits._
import cats.effect.std._
def ioPrint[A](ioa: IO[A])(stringRepr: String): Unit = {
    println("```scala")
    println(stringRepr)
    import cats.effect.unsafe.implicits.global
    val a = ioa.unsafeRunSync()
    if (a != ()) {
        println(a.toString().split("\n").map(x => s"// $x").mkString("\n"))
    }
    println("```")
}
def println_(a: Any) = println(a)
implicit lazy val munitConsoleInstance: Console[IO] = new Console[IO] {
  def error[A](a: A)(implicit S: cats.Show[A]): cats.effect.IO[Unit] = ??? 
  def errorln[A](a: A)(implicit S: cats.Show[A]): cats.effect.IO[Unit] = ??? 
  def print[A](a: A)(implicit S: cats.Show[A]): cats.effect.IO[Unit] = ??? 
  def println[A](a: A)(implicit S: cats.Show[A]): cats.effect.IO[Unit] = IO.delay(println_("// " + S.show(a))) 
  def readLineWithCharset(charset: java.nio.charset.Charset): cats.effect.IO[String] = ??? 
}
```
```scala mdoc
type Auth = String
def authorizedRoute(L: Local[IO, Auth]): IO[Unit] = 
  for {
    user <- L.ask
    _ <- Console[IO].println(s"doing user op with $user")
    _ <- L.local{
      L.ask.flatMap{ user =>
        Console[IO].println(s"doing admin operation with $user")
      }
    }(_ => "admin")
    user <- L.ask
    _ <- Console[IO].println(s"doing user op with $user again")
  } yield ()

def run(C: Context[IO]): IO[Unit] = 
  C.use("user")(authorizedRoute)
```
Running the program yields:
```scala mdoc:passthrough
ioPrint(Context.ioContext.flatMap(C => run(C)))(
  "Context.ioContext.flatMap(C => run(C))"
)
```

### Other ways of constructing Context
There are several other ways to construct `Context` in the case that you are not working in `IO`.
For instance, if you are working in `Kleisli` a natural implementation exists.
```scala mdoc:silent
Context.kleisli[IO]: Context[Kleisli[IO, Vault, *]]
```
Or for any instance of `Local[F, Vault]`.
```scala
implicit lazy val L: Local[IO, Vault] = ???
Context.local[IO]
```

## Catch
`Catch` is responsible for throwing and catching errors.
The MTL counterpart of `Catch` is `EitherT`.
`Catch` can introduce new ad-hoc error channels that are independent of eachother.
There are various ways to construct a catch, but the simplest (given that you're working in `cats-effect`) is the following.
```scala mdoc:silent
Catch.ioCatch: IO[Catch[IO]]
```

### Example in `IO`
If you work in `IO`, the usage of `Catch` becomes simpler.
`IOCatch` provides a utility to immediately create a `Handle` instance.
```scala mdoc
sealed trait UserError
case object WeakPassword extends UserError
def verifyUser(password: String)(R: Raise[IO, UserError]): IO[Unit] =
  for {
    _ <- Console[IO].println("verifying user")
    _ <- R.raiseIf(WeakPassword)(password.length < 8)
    _ <- Console[IO].println("user verified")
  } yield ()
```
Running the program yields:
```scala mdoc:passthrough
ioPrint((
  IOCatch[UserError](verifyUser("pass")(_)),
  IOCatch[UserError](verifyUser("supersafepassword123")(_))
).tupled)(
  """(
  IOCatch[UserError](verifyUser("pass")(_)),
  IOCatch[UserError](verifyUser("supersafepassword123")(_))
).tupled"""
)
```

### Example for any effect
With an instance of `Catch` in scope, you can create locally scoped domain-specific errors.
```scala mdoc
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
```scala mdoc:passthrough
ioPrint(Catch.ioCatch.flatMap(implicit C => doDomainEffect[IO]))(
  "Catch.ioCatch.flatMap(implicit C => doDomainEffect[IO])"
)
```

`Catch`, `Raise` and `Handle` instances are well behaved when nested and can raise errors on their completely isolated error channels.

`Handle`'s API can also facilitate parallel gathering of errors, like `EitherT`'s `Parallel` instance.
```scala mdoc:nest
trait CreateUserError
case object InvalidEmail extends CreateUserError
case object InvalidPassword extends CreateUserError
def createUser[F[_]: Console: Sync](idx: Int)(R: Raise[F, CreateUserError]): F[Unit] = 
  for {
    _ <- R.raiseIf(InvalidEmail)(idx % 2 == 0)
    _ <- R.raiseIf(InvalidPassword)(true)
    _ <- Console[F].println(s"Created user!")
  } yield ()

def createUserBatch[F[_]: Console: Sync: Catch]: F[Unit] =
  Catch[F].use[List[CreateUserError]]{ implicit H => 
    val one = H.contramap[CreateUserError](List(_))
    implicit val P: Parallel[F] = H.accumulatingParallelForApplicative
    (0 to 10).toList.parTraverse(createUser[F](_)(one))
  }.flatMap{
    case Left(errors) => Console[F].println(s"Errors: ${errors.map(x => "\n// " + x.toString()).mkString("")}")
    case Right(_) => Console[F].println("Success!")
  }
```
Running the program yields:
```scala mdoc:passthrough
ioPrint(Catch.ioCatch.flatMap(implicit C => createUserBatch[IO]))(
  "Catch.ioCatch.flatMap(implicit C => createUserBatch[IO])"
)
```

### Incorrect usage of Catch
`Catch` builds uppon cancellation of effects.
As such, any invocation of `raise` (or it's other variants) must not occur in an `uncancelable` block.
This is usually not an issue for most (if not all) applications, but must be respected regardless.
```scala
// Raise cannot cancel itself when in an uncancelable block
IOCatch[String](r => IO.uncancelable(_ => r.raise("error")))
```

### Other ways of constructing Catch
1. Catch can occur for an instance of `Handle[F, Vault]` (or `EitherT[F, Vault, A]`)
2. Catch can occur for an instance of `Local[F, Vault]` (or `Kleisli[F, Vault, A]`) and `Concurrent[F]` via cancellation

An interestingly, you can in fact construct `Catch` if you have `Context` and `Concurrent`.
```scala mdoc
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
```scala mdoc:silent
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
```scala mdoc:passthrough
ioPrint(Catch.ioCatch.flatMap(catchError).attempt.map(_.left.toOption.get))(
  "Catch.ioCatch.flatMap(catchError)"
)
```
And then then Context example:
```scala mdoc:passthrough
ioPrint(Context.ioContext.flatMap(contextError).attempt.map(_.left.toOption.get))(
  "Context.ioContext.flatMap(contextError)"
)
