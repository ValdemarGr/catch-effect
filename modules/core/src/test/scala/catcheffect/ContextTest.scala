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
