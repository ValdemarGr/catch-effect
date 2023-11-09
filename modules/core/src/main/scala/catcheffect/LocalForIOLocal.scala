/*
 * Copyright 2023 Valdemar Grange
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

import cats._
import cats.effect._
import org.tpolecat.sourcepos.SourcePos

object LocalForIOLocal {
  def localForIOLocal[A](iol: IOLocal[A]): Local[IO, A] =
    new Local[IO, A] {
      override def set[B](fa: IO[B])(c: A)(implicit sp: SourcePos): IO[B] =
        iol.get.flatMap(a => iol.set(c) *> fa <* iol.set(a))

      override def applicative: Applicative[IO] = implicitly

      override def ask(implicit sp: SourcePos): IO[A] = iol.get

      override def local[B](fa: IO[B])(f: A => A)(implicit sp: SourcePos): IO[B] =
        iol.get.flatMap(p => iol.set(f(p)) *> fa <* iol.set(p))
    }

  def localForIOLocalDefault[A](a: A): IO[Local[IO, A]] =
    IOLocal(a).map(localForIOLocal(_))
}
