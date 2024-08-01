package org.scalamock.test.scalatest

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AbstractOverrideMethodTest extends AnyFlatSpec with Matchers with MockFactory {
  class A extends B with D
  trait B extends C { def foo(): Int = 1 }
  trait C { def foo(): Int }
  trait D extends C { abstract override def foo(): Int = super.foo() * 2 }

  "ScalaTest suite" should "permit mocking classes build with stackable trait pattern" in {
    val mockedClass = mock[A]
    (mockedClass.foo _).expects().returning(42)
    mockedClass.foo() shouldBe 42
  }
}
