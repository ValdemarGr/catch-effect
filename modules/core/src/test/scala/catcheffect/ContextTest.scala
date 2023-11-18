package catcheffect

import cats.effect._
import munit.CatsEffectSuite

class ContextTest extends CatsEffectSuite {
  val C = Context.ioContext.unsafeRunSync()

  test("nominal nested use of Context should work as expected") {
    C.use("one") { l1 =>
      C.use("two") { l2 =>
        assertIO(l1.ask, "one") *> assertIO(l2.ask, "two") *>
          l1.local(assertIO(l2.ask, "two"))(_ => "test") *> l2.local(assertIO(l1.ask, "one"))(_ => "test") *>
          l1.local(assertIO(l1.ask, "test"))(_ => "test") *> l2.local(assertIO(l2.ask, "test"))(_ => "test")
      }
    }
  }

  test("leaking algebra is considered an error") {
    val program = C
      .use("hey")(hs => IO.pure(hs))
      .flatMap(_.ask)

    program.attempt.map {
      case Right(x) => fail(x.toString()): Unit
      case Left(x)  => assert(x.getMessage().contains("A Local operator was invoked"), x.getMessage())
    }
  }
}
