# Catch effect <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>
Catch effect is a small library that facilitates MTL-like programs, without the need for monad transformers, but just an effect type like `IO`.

Catch effect is built on top of [Vault](https://github.com/typelevel/vault), which is a map that can hold values of different types.

## Installation
```scala
"io.github.valdemargr" %% "catch-effect" % "0.1.2"
```

## Context
Context can create instances of `Local`.
`Local` provides the ability to run an effect given some input and modify the input.

The MTL counterpart of `Context` is `Kleisli`/`ReaderT`, (`A => F[B]` for the uninitiated).
`Context` is a building block for the more interesting `Catch` sturcture described below.

If you are using `cats-effect` and you are familiar with `IOLocal`, then `Context` should be feel familiar.

You can construct an instance of `Context` in various ways, here is one using `cats-effect`.
```scala
Context.ioContext: IO[Context[IO]]
```

### Example
When you have an instance of `Context` in scope, new `Local` instances can be spawned on the fly.
```scala
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
```scala
Context.ioContext.flatMap(C => run(C))
// doing user op with user
// doing admin operation with admin
// doing user op with user again
```

### Other ways of constructing Context
There are several other ways to construct `Context` in the case that you are not working in `IO`.
For instance, if you are working in `Kleisli` a natural implementation exists.
```scala
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
```scala
Catch.ioCatch: IO[Catch[IO]]
```

### Example in `IO`
If you work in `IO`, the usage of `Catch` becomes simpler.
`IOCatch` provides a utility to immediately create a `Handle` instance.
```scala
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
```scala
(
  IOCatch[UserError](verifyUser("pass")(_)),
  IOCatch[UserError](verifyUser("supersafepassword123")(_))
).tupled
// verifying user
// verifying user
// user verified
// (Left(WeakPassword),Right(()))
```

### Example for any effect
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

`Catch`, `Raise` and `Handle` instances are well behaved when nested and can raise errors on their completely isolated error channels.

`Handle`'s API can also facilitate parallel gathering of errors, like `EitherT`'s `Parallel` instance.
```scala
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
```scala
Catch.ioCatch.flatMap(implicit C => createUserBatch[IO])
// Errors: 
// InvalidEmail
// InvalidPassword
// InvalidEmail
// InvalidPassword
// InvalidEmail
// InvalidPassword
// InvalidEmail
// InvalidPassword
// InvalidEmail
// InvalidPassword
// InvalidEmail
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
// You are trying to raise at README.md:234,
// but this operation occured outside of the scope of the handler.
// Either widen the scope of your handler or don't leak the algebra.
// The handler was defined at README.md:233
```
And then then Context example:
```scala
Context.ioContext.flatMap(contextError)
// catcheffect.Context$NoHandlerInScope: A Local operator was invoked outside of it's handler.
// The Local operator was invoked at README.md:240.
// The handler for this Local instance was defined at README.md:239.
// 
// You may have leaked the Local algebra by accident.
// This can be casued by functions of similar form as the following.
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
//   Context[F].allocated[A].flatMap { loc => 
//      val fk = loc.setK(initialValue)
//      fk {
//          make[F](loc).mapK(fk)
//      }
//   }.flatMap(algebra => algebra.doSomething)
// ```
```
