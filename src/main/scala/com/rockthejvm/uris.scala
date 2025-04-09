package com.rockthejvm

import cats.effect.IO
import org.http4s.Uri

object uris {

  def publicRepos(orgName: String) =
    s"https://api.github.com/orgs/$orgName"

  def contributors(repoName: String, orgName: String, page: Int): String =
    s"https://api.github.com/repos/$orgName/$repoName/contributors?per_page=100&page=$page"

  def repos(orgName: String, page: Int): String =
    s"https://api.github.com/orgs/$orgName/repos?per_page=100&page=$page"

  def uri(url: String): IO[Uri] =
    IO.fromEither(Uri.fromString(url))
}
