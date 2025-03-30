package com.rockthejvm

object domain {
  opaque type PublicRepos = Int

  object PublicRepos {
    val Empty: PublicRepos = PublicRepos.apply(0)

    def apply(value: Int): PublicRepos = value
  }

  extension (repos: PublicRepos) def value: Int = repos

  opaque type RepoName = String

  object RepoName {
    def apply(value: String): RepoName = value
  }

  extension (repoName: RepoName) def value: String = repoName

  final case class Contributor(login: String, contributions: Long)

  final case class Contributions(count: Long, contributors: Vector[Contributor])
}
