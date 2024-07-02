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

import catcheffect.Catch.{RaisedInUncancellable, RaisedWithoutHandler}
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
          _ <- IOCatch[String](hs2 => hs1.raise("1"))
          _ <- IO.raiseError(new AssertionError("should not reach this point"))
        } yield ()
      )
      _ = assertEquals(res, Left("1"))
    } yield res
  }

  test("Raising in an uncancellable region may correctly raise the error or throw RaisedInUncancellable") {
    // There's a small race condition where cancellation from outside will win against the IO.cancel call,
    // therefore RaisedInUncancellable isn't thrown. Unfortunately it doesn't seem like we can detect
    // whether the current scope is cancellable without actually triggering cancellation
    (for {
      res <- IOCatch[String](h => IO.uncancelable(_ => h.raise("oops").void))
      // If it did raise error instead of throwing RaisedInUncancellable, at least make sure it's raised correctly
      _ = assertEquals(res, Left("oops"))
    } yield ()).recoverWith {
      case _: RaisedInUncancellable[_] => IO.unit // succeed
      case e                           => IO.raiseError(e)
    }
  }

  test("Can raise in an cancellable region when polled") {
    for {
      res <- IOCatch[String](h => IO.uncancelable(poll => poll(h.raise("oops"))))
      _ = assertEquals(res, Left("oops"))
    } yield ()
  }

  test("Fail to raise if the Raise instance is used outside of its original fiber") {
    (for {
      cached <- Deferred[IO, Raise[IO, String]]
      res <- IOCatch[String](h => cached.complete(h).void)
      _ = assertEquals(res, Right(()))
      _ <- cached.get.flatMap(_.raise("hm"))
    } yield ()).attempt.map {
      case Left(_: RaisedWithoutHandler[_]) => () // succeed
      case other                            => fail(s"Expected RaisedWithoutHandler, got $other")
    }
  }
}
