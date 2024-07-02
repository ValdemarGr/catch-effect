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

