Some people say that functional programming is slow. This app demonstrates that they are bulshitting.

Parallelism is a piece of cake when programs are expressions which can be composed and manipulated like normal strings and integers.

The app aggregates all the contributors for a certain GitHub organization and sorts them by their contributions.

Parallelism is only limited by the restrictions GitHub REST API.

You are welcome to improve the implementation.

`curl localhost:9000/org/{org_name}` will yield sorted JSON response which looks like:
```json
{
  "count": 3,
  "contributors": [
    {
      "login": "user1",
      "contributions": 5000
    },
    {
      "login": "user2",
      "contributions": 2500
    },
    {
      "login": "user3",
      "contributions": 1000
    }
  ]
}
```

Benchmarks for different organizations: 
- quantori - 3 seconds, 59 public repos, 1000 contributors
- typelevel - 4 seconds, 101 public repos, 1500 contributors
- zio - 5 seconds, 94 public repos, 2000 contributors
- Google - 15 seconds, 2560 public repos, 14000 contributors



