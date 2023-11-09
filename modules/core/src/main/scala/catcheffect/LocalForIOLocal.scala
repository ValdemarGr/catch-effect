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
