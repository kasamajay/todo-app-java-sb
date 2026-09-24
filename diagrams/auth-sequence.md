# Sequence Diagram — Login

`AuthController.login` (`api/src/main/java/io/todo/api/controllers/AuthController.java`) is written so PBKDF2 always runs — for a real user or a fixed dummy salt/hash — before any branch that could return early, so response timing can't reveal whether an email is registered. See [`../decisions/0002-pbkdf2-on-javax-crypto-mac.md`](../decisions/0002-pbkdf2-on-javax-crypto-mac.md) and [`../features/authentication.md`](../features/authentication.md).

```mermaid
sequenceDiagram
    actor User
    participant Web as Login.jsx
    participant API as AuthHandler.Login
    participant Store as UserStore (users.json)
    participant PBKDF2 as auth.PBKDF2Key

    User->>Web: submit email + password
    Web->>API: POST /api/auth/login {email, password}
    API->>Store: FindByEmail(email)

    alt email found
        Store-->>API: user (real salt + hash)
    else email not found
        Store-->>API: not found
        API->>API: use fixed dummy salt + hash
    end

    API->>PBKDF2: PBKDF2Key(password, salt, 100000, 64)
    Note over API,PBKDF2: Always runs - found or not - so timing doesn't leak account existence
    PBKDF2-->>API: derived key
    API->>API: subtle.ConstantTimeCompare(derived, stored)

    alt email not found
        API-->>Web: 401 invalid_credentials
    else account is locked (now < LockedUntil)
        API-->>Web: 403 account_locked
    else password mismatch
        API->>Store: FailedLoginCount++, set LockedUntil if count >= 3
        API-->>Web: 401 invalid_credentials
    else password matches
        API->>Store: reset FailedLoginCount, clear LockedUntil
        API->>API: auth.Mint(secret, claims{uid, adm, exp})
        API-->>Web: 200 {token, user}
        Web->>Web: setToken(token), render boards view
    end
```

Notable: the "email not found" and "password mismatch" branches both return the same generic `401 invalid_credentials` (never a distinct "no such account" message), and both are reached only *after* the PBKDF2 call above — the dummy-hash branch exists specifically so that cost is paid identically in both cases.
