package com.paulbutcher.test

object Shims {
  given canEqualAny[L, R]: CanEqual[L, R] = CanEqual.derived
}
