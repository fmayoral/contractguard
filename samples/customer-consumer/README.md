# Customer Consumer

Demo Spring Boot service consuming the Customer API.

## Upstream API usage

- `GET /customers/{id}` — fetches a customer record; the response `Customer`
  object carries `fullName` and a `status` of `ACTIVE`, `SUSPENDED` or
  `CLOSED`.

Order placement is refused for suspended and closed customers
(`OrderEligibilityPolicy`).

## Build

```bash
./mvnw verify
```
