package catcheffect

import cats.effect._
import cats.implicits._
import munit.CatsEffectSuite

class CatchTest extends CatsEffectSuite {
  val C = Catch.ioCatch.unsafeRunSync()

  test("should be able to throw, catch and re-throw") {
    val program = C.use[String] { hs =>
      C.use[Int] { hi =>
        hi.raise(1) *> hs.raise("error") as true
      }.flatMap(_.leftTraverse(i => hs.raise(i.toString())))
    }

    assertIO(program, Left("1"))
  }

  test("handling exceptions should work in other scopes") {
    val program = C.use[String] { hs =>
      C.use[Int] { hi =>
        hi.attempt {
          hs.attempt {
            hi.attempt {
              hs.raise[Boolean]("error")
            }
          }
        }
      }
    }

    // use[String] of use[Int] of attempt[Int] of attempt handled[String]
    assertIO(program, Right(Right(Right(Left("error")))))
  }

  test("leaking algebra is considered an error") {
    val program = C
      .use[String](hs => IO.pure(hs))
      .flatMap(_.traverse(_.raise("error")))

    program.attempt.map {
      case Right(x) => fail(x.toString()): Unit
      case Left(x)  => assert(x.getMessage().contains("You are trying to"), x.getMessage())
    }
  }
}
