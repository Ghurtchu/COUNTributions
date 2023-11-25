Some people say that functional programming is slow. This app demonstrates that they are bulshitting.

Parallelism is a piece of cake when programs are expressions which can be composed and manipulated like normal strings and integers.

The app aggregates all the contributors for a certain GitHub organization and sorts them by their contributions.

`curl localhost:9000/org/{org_name}` will yield sorted JSON response which looks like:
```json
{
  "count": 1000,
  "contributors": [
    {
      "login": "user1",
      "contributions": 5000
    },
    ..
    ..
    {
      "login": "user1000",
      "contributions": 14
    }
  ]
}
```

Benchmarks for different organizations: In Progress



