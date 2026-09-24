# 0002. PBKDF2-HMAC-SHA512 on `javax.crypto.Mac`, with the Go backend's exact parameters

*Replaces todo-app's 0002 ("Hand-rolled PBKDF2-HMAC-SHA512, no external crypto package").*

## Context
The spec calls for PBKDF2-SHA512 password hashing with 100k iterations. The Java API must stay **data-compatible** with the Go and Python backends (decisions/0014): a `users.json` written by any of them must verify passwords in the others. Go hashes the password string's **UTF-8 bytes**.

The JDK does ship PBKDF2, as `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`, but its input is a `PBEKeySpec` holding a **`char[]`**. The provider converts those chars to bytes internally, which the API doesn't guarantee matches Go's bytes. It also rejects edge cases Go accepts, and the byte-level test vectors (e.g. passwords with embedded NUL bytes) can't be expressed through it directly.

## Decision
`auth/Passwords.java` implements PBKDF2 (RFC 8018) in about 25 lines on top of the JDK's `javax.crypto.Mac` (`HmacSHA512`), taking the password as `byte[]` (`String.getBytes(UTF_8)`), exactly as the Go implementation does. It uses the same parameters as Go:
- 100,000 iterations
- a 64-byte key
- a 16-byte salt from `SecureRandom`

The comparison uses `MessageDigest.isEqual`, which runs in constant time. Hashes and salts are stored as standard base64, the way Go's `encoding/json` marshals `[]byte`.

HMAC with an empty key is valid, but `SecretKeySpec` rejects empty arrays, so an empty password is mapped to the equivalent all-zero 128-byte (block-size) key.

`AuthPrimitivesTest` runs the same known-answer vectors as the Go and Python suites, including the NUL-byte case, and asserts the parameters.

## Alternatives considered
- **`PBKDF2WithHmacSHA512` SecretKeyFactory:** less code, but works on `char[]`, so byte-exactness with Go and Python isn't guaranteed by the API contract.
- **Spring Security's `Pbkdf2PasswordEncoder` / bcrypt / argon2:** these pull in Spring Security (decisions/0005) and use their own encoded formats. That breaks password compatibility with the other backends and existing data.

## Consequences
- A little project-owned crypto code, but it's built on the JDK's HMAC primitive and pinned by known-answer tests.
- Password hashes are interchangeable across all three backends. This was verified by logging into Java-created accounts through the Go and Python APIs.
