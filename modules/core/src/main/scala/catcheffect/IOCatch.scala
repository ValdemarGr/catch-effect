package catcheffect

import Catch.catchForIO
import cats.effect.*

object IOCatch {
  
  def ioCatching[E]: IOCatchPartiallyApplied[E] =
    new IOCatchPartiallyApplied[E]
  
  class IOCatchPartiallyApplied[E] {
    def apply[A](f: Handle[IO, E] => IO[A]): IO[Either[E, A]] = {
      catchForIO.flatMap { c =>
        c.use[E](f)
      }
    }
  }
  
}

