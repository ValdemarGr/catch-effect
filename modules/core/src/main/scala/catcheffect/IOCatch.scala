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

import Catch.ioCatch
import cats.effect.*

object IOCatch {

  def apply[E]: IOCatchPartiallyApplied[E] =
    new IOCatchPartiallyApplied[E]

  class IOCatchPartiallyApplied[E] {
    def apply[A](f: Handle[IO, E] => IO[A]): IO[Either[E, A]] = {
      ioCatch.flatMap { c =>
        c.use[E](f)
      }
    }
  }

}
