package catcheffect

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
}
