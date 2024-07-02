package catcheffect

import catcheffect.Catch.RaisedWithoutHandler
import cats.effect.*
import munit.CatsEffectSuite

class IOCatchTest extends CatsEffectSuite {
  test("should abort execution as soon as raise is called") {
    for {
      ref <- Ref[IO].of("init")
      res <- IOCatch[String](h =>
        for {
          _ <- h.raise("error")
          _ <- ref.set("should not be set")
        } yield 1
      )
      _ = assertEquals(res, Left("error"))
      _ <- assertIO(ref.get, "init")
    } yield ()
  }
  
  test("can nest correctly") {
    for {
      res <- IOCatch[String](hs1 =>
        for {
          _ <- IOCatch[String](hs2 =>
            hs1.raise("1")
          )
          _ <- IO.raiseError(new AssertionError("should not reach this point"))
        } yield ()
      )
      _ = assertEquals(res, Left("1"))
    } yield res
  }
  
  test("Can raise in an uncancellable region") {
    for {
      res <- IOCatch[String](h =>
        IO.uncancelable(_ => h.raise("oops"))
      )
      _ = assertEquals(res, Left("oops"))
    } yield ()
  }

  test("Can raise in an cancellable region when polled") {
    for {
      res <- IOCatch[String](h =>
        IO.uncancelable(poll => poll(h.raise("oops")))
      )
      _ = assertEquals(res, Left("oops"))
    } yield ()
  }
  
  test("Fail to raise if the Raise instance is used outside of its original fiber") {
    (for {
      cached <- Deferred[IO, Raise[IO, String]]
      res <- IOCatch[String](h =>
        cached.complete(h).void
      )
      _ = assertEquals(res, Right(()))
      _ <- cached.get.flatMap(_.raise("hm"))
    } yield ()).attempt.map {
      case Left(_: RaisedWithoutHandler[_]) => () // succeed
      case other => fail(s"Expected RaisedWithoutHandler, got $other")
    }
  }
}
