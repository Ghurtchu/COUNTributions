package com.rockthejvm

import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*

object config {

  opaque type Token = String

  object Token {
    def apply(value: String): Token = value
  }

  case class AppConfig(
    token: Token,
    cacheExpirationInMillis: Long,
  ) derives ConfigReader

}
