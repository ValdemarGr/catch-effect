package catapiler

// import cats.mtl._
import cats._
import cats.free._
import cats.arrow._
import cats.implicits._
import cats.effect._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

object Main extends App {
  println("holla")
}

trait SealedHandle[F[_], A, E] {
  def unseal: F[Either[E, A]]
}

trait Raise[F[_], E] {
  def raise[A](e: E): F[A]
}

trait Handle[F[_], E] extends Raise[F, E] {
  def handle[A](fa: F[A])(f: E => F[A]): F[A]
}

trait Catch[F[_]] {
  def allocated[E]: F[Handle[F, E]]
}

trait Ask[F[_], A] {
  def ask: F[A]
}

trait Local[F[_], A] extends Ask[F, A] {
  def local[B](f: A => A)(fa: F[B]): F[B]
}

trait Context[F[_]] {
  def allocated[A]: F[(Local[F, A], A => F ~> F)]
}

trait Tell[F[_], A] {
  def tell(a: A): F[Unit]
}

trait Listen[F[_], A] extends Tell[F, A] {
  def listen[B](fa: F[B]): F[(B, A)]
}
trait Emit[F[_]] {
  def allocated[A]: F[(Listen[F, A], F ~> F)]
}
