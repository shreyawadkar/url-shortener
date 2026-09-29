# Scalable URL Shortener

A distributed URL-shortening service built with **Java 17, Spring Boot 3, Kafka, Redis and sharded MySQL**, with **AWS** infrastructure (ECS Fargate, RDS, ElastiCache, MSK, Lambda, CloudWatch) defined in **Terraform**.

```
                    ┌──────────────┐
  client ──HTTP──▶  │  Spring Boot │──cache-aside──▶ Redis (code → URL, negative caching)
                    │   API (N×)   │
                    └──┬────────┬──┘
          CRC32(code)%3│        │ click event (keyed by code)
         ┌─────────────┼───┐    ▼
         ▼             ▼   ▼  Kafka topic "click-events" (3 partitions)
     MySQL shard0  shard1  shard2        │
         ▲             ▲   ▲             ├──▶ in-app consumer group (3 threads) ─┐
         └─────────────┴───┴─────────────┴──▶ AWS Lambda (MSK trigger) ──────────┴─▶ batched click-count updates per shard
```

## What's inside

| Area | Implementation |
|---|---|
| Sharding | 3 MySQL shards, each with its own HikariCP pool. Codes are routed with `CRC32(code) % 3` ([ShardRouter](src/main/java/com/shortener/shard/ShardRouter.java)), stable across JVMs so the Lambda routes identically. |
| Caching | Redis cache-aside with TTL (capped at link expiry) and 60 s negative caching for unknown codes. Redis errors degrade to DB reads. Falls back to Caffeine when `REDIS_URL` is unset. |
| Concurrency control | Per-shard bounded worker pools (bulkheads with back-pressure); unique index + retry for code collisions and racing custom aliases; lock-free click aggregation with `ConcurrentHashMap.merge/remove` flushed as one JDBC batch per shard in parallel ([ClickAggregator](src/main/java/com/shortener/events/ClickAggregator.java)). |
| Event streaming | Redirects publish click events to Kafka (fire-and-forget, keyed by code, lz4, batched). Consumed by an in-app listener group and/or the [AWS Lambda](lambda/src/main/java/com/shortener/lambda/ClickEventHandler.java). |
| Infra as code | [Terraform](infra/terraform): VPC, 3× RDS MySQL, ElastiCache Redis, MSK, ECR, ECS Fargate + ALB, Lambda with MSK event-source mapping. |
| Monitoring | Micrometer timers with p50/p95/p99 at `/actuator/metrics` and `/actuator/prometheus`; CloudWatch dashboard plus alarms for ALB p99 > 50 ms, 5xx, and Lambda errors. |

Every external dependency is optional: with no env vars set, the app runs with 3 in-memory MySQL-mode shards (H2), a local cache and an in-process event bus. Set the env vars below to switch on the real services.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/urls` | `{"url": "...", "customAlias": "opt", "ttlDays": 30}` → `201 {code, shortUrl, shard, ...}`; `409` if alias taken |
| `POST` | `/api/v1/urls/batch` | Array of the above (max 100), processed in parallel across shards |
| `GET` | `/{code}` | `302` redirect, `404` if unknown or expired |
| `GET` | `/api/v1/urls/{code}/stats` | Click count, shard, timestamps |
| `GET` | `/api/v1/system` | Cache/event mode and per-shard row counts, clicks, pool usage |
| `GET` | `/actuator/metrics/shortener.redirect` | Redirect latency |

## Run locally

```bash
mvn spring-boot:run          # zero-dependency mode, http://localhost:8080
mvn test                     # unit + integration + concurrency tests
docker compose up --build    # full stack: 3× MySQL, Redis, Kafka, app
```

## Load test

```bash
java loadtest/LoadTest.java http://localhost:8080 32 30   # base URL, threads, seconds
```

Mix is 90% redirects / 10% creates over 1,000 seeded links. Measured on an Apple-silicon laptop in zero-dependency mode (in-memory shards): **~39,900 req/s, p50 0.65 ms, p99 2.5 ms, 0 errors** over 798k requests. With real MySQL 3-shard + Redis on the same laptop: **~24,000 req/s, p50 1.1 ms, p99 3.7 ms, 0 errors** over 480k requests. Numbers against networked MySQL/Redis will be lower, so rerun it against your deployment.

## Configuration

| Env var | Purpose |
|---|---|
| `BASE_URL` | Public base for generated short links |
| `MYSQL_URL` | One hosted MySQL server, `mysql://user:pass@host:port/db` (e.g. Aiven's Service URI). Creates databases `shard0`–`shard2` on it and overrides the per-shard URLs below. |
| `SHARD0_URL`, `SHARD1_URL`, `SHARD2_URL` | JDBC URLs, e.g. `jdbc:mysql://host:3306/shard0?sslMode=REQUIRED` |
| `DB_USER`, `DB_PASSWORD` | Shared shard credentials (or per shard: `SHARDn_USER` / `SHARDn_PASSWORD`) |
| `REDIS_URL` | `redis://` or `rediss://user:pass@host:port` |
| `KAFKA_BOOTSTRAP_SERVERS` | Enables Kafka; add `KAFKA_USERNAME`, `KAFKA_PASSWORD`, `KAFKA_SASL_MECHANISM` for SASL_SSL |

## Deploy

**Free (Render):** New → Blueprint → select this repo. [render.yaml](render.yaml) builds the Dockerfile on Render's free plan. For persistent links set two env vars: `MYSQL_URL` (Aiven free MySQL Service URI) and `REDIS_URL` (Upstash free Redis `rediss://` URL).

**AWS (Terraform):**
```bash
cd lambda && mvn package && cd ../infra/terraform
terraform init
terraform apply -var db_password=... -target=aws_ecr_repository.app
docker build -t <ecr_repository_url>:latest ../.. && docker push <ecr_repository_url>:latest
terraform apply -var db_password=...
```
Note: MSK, NAT gateway, 3× RDS and the ALB cost roughly $150–300/month. Run `terraform destroy` when you're done.
