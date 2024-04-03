# Rād

Rād «ᚱ» is a small, zero-dependency Redis client for Clojure (JDK 21+).

## Quick start

```clojure
user=> (require '[rad.api :as rad])
nil
;; Connect to default Redis host ("localhost") and port (6379).
user=> (def redis (rad/client :decode rad/bytes->str))
#'user/redis
;; Send a command pipeline.
user=> @(redis [:ECHO "Hello"] [:ECHO "world!"])
["Hello", "world!"]
;; Close the connection.
user=> (redis)
nil
```

## Features

- No runtime dependencies
- Minimal API
- RESP3 support
- [Pipelining](https://redis.io/docs/latest/develop/use/pipelining/) support
- Supports every version of Redis that supports RESP3
- One connection per client (including [pub/sub](https://redis.io/docs/latest/develop/interact/pubsub/))
- Automatically reconnects when disconnected
- [Reasonable sequential throughput, pretty good concurrent throughput](https://github.com/eerohele/rad/actions/workflows/bench.yml) over a single connection

## Documentation

See [documentation](doc/INDEX.md) and [API documentation](https://eerohele.github.io/rad).

## Examples

See [`examples`](examples).

## Non-goals

- Connection pooling

## Limitations

- RESP3-only
- No automatic encoding of Clojure values into bytes
- No automatic decoding of bytes into Clojure values
- No [TLS](https://redis.io/docs/latest/operate/oss_and_stack/management/security/encryption/) support
- No [Redis Cluster](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) support
- Does not support the `MONITOR` command

## Contributing

If you have ideas on how to improve Rad's performance (sequential performance in particular) or the performance of the [RESP3 implementation](https://github.com/eerohele/rad/blob/main/src/rad/resp.clj) (apart from writing it in Java), I'd be interested in hearing about them.

Other than that, I'm not accepting code contributions at this time. If you have improvement ideas, bug reports, or feature requests, [file an issue](https://github.com/eerohele/rad/issues).
