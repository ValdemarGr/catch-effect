package catapiler

import cats.mtl._
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
