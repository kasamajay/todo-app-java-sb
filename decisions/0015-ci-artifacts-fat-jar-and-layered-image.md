# 0015. CI/CD artifacts: an executable fat jar, shipped as a layered container image

## Context
Setting up CI/CD raised three questions: what the Spring Boot API actually compiles into, which artifacts are worth keeping, and how the Java runtime finds the code and its dependencies. The answers below come from inspecting the real build output.

### What `mvn package` produces
`spring-boot-maven-plugin` repackages the normal jar into **one executable "fat" jar**, `api/target/app.jar` (~20 MB). It's **not a war**: a war is deployed *into* an external servlet container (Tomcat, JBoss), whereas here Tomcat is itself a dependency (`tomcat-embed-core`), so the jar *is* the server.

```
app.jar
├─ META-INF/MANIFEST.MF        Main-Class:  org.springframework.boot.loader.launch.JarLauncher
│                              Start-Class: io.todo.api.TodoApiApplication
├─ org/springframework/boot/loader/…   Spring Boot's launcher (111 classes)
├─ BOOT-INF/classes/           our 46 classes + application.properties (~100 KB)
├─ BOOT-INF/lib/               all 30 dependency jars, nested unchanged (~18 MB)
├─ BOOT-INF/classpath.idx      dependency order
└─ BOOT-INF/layers.idx         dependencies / spring-boot-loader / snapshot-dependencies / application
```
`spring-boot-devtools` is excluded automatically.

### How the JVM runs it
`java -jar app.jar` makes the JVM read the manifest and start `Main-Class`. That's **`JarLauncher`**, not our code. JarLauncher builds a classloader over `BOOT-INF/classes` plus every jar in `BOOT-INF/lib` (in `classpath.idx` order), reading the **nested jars in place** without unpacking them, then calls `Start-Class`'s `main`.

The only external requirements are a **Java 21 runtime** (a JRE; no JDK, no Maven) and configuration through environment variables.

## Decision
**Build once, keep versioned artifacts, and ship a layered image.**

| Artifact | Kept where | Why |
|---|---|---|
| `app.jar` + `app.jar.sha256` | CI workflow artifact on every run (30 days); attached to the GitHub Release on `vX.Y.Z` tags | The portable build output. Runs anywhere with a JRE 21. |
| API image `ghcr.io/kasamajay/todo-app-java-sb-api` | GHCR: `:<sha>` + `:latest` on every push to `main`, `:X.Y.Z` on tags | **What production runs.** The deploy unit. |
| Web image `ghcr.io/kasamajay/todo-app-java-sb-web` | GHCR, same tags | nginx + the prebuilt Vite bundle. |
| `surefire-reports/` | CI workflow artifact (14 days, also on failure) | Diagnosing test failures. |

Not kept: `target/classes`, the Maven cache (the Actions cache handles that) and the dependency jars on their own, since they're inside the jar and the image.

**Layered image** (`api/Dockerfile`): the build stage runs `java -Djarmode=tools -jar app.jar extract --layers`, and each layer is `COPY`'d separately, least-changing first. In this extracted form the image doesn't use the nested-jar launcher. `/app` holds a **thin `app.jar` (~92 KB, our code)** whose manifest has `Main-Class: io.todo.api.TodoApiApplication` and a `Class-Path` listing **`lib/*.jar`**, the 30 dependency jars in a normal directory, loaded by the plain JVM classpath. It still starts with `java -jar /app/app.jar`, as one process.

Measured:
- Image layers: dependencies 19.8 MB, application 92.9 kB.
- After a source change, 9 of the 10 image layers were byte-identical; only the application layer changed.
- CI pushes, registry storage and server pulls per code change drop from about 20 MB to about 100 KB.

**Pipelines** (`.github/workflows/`):
- `ci.yml`, on PRs and on pushes to `main`:
  - `test`: `mvn verify` natively on the runner.
  - `package`: the jar plus its checksum as an artifact.
  - `contract`: the real production stack under Compose, with the shared contract suite run through nginx, plus smoke checks that the images have no javac or Maven.
  - `images`: builds both images on every run, but pushes to GHCR only on `main`.
- `release.yml`, on `vX.Y.Z` tags: `versions:set` from the tag (so the manifest's `Implementation-Version` matches), `verify`, a GitHub Release with the jar and checksum, and images tagged `X.Y.Z`.

**CD (deploy) is intentionally not included yet.** It needs a target (a VM running Compose that pulls GHCR images, a PaaS, Kubernetes) and credentials. The images above are the deploy unit whatever the target.

## Alternatives considered
- **War file + external Tomcat:** an extra server to install, version and configure, and it would lose the self-contained "one jar = one service" model Spring Boot is built around.
- **Copying the fat jar into the image as one layer** (the previous Dockerfile): simpler, but every commit re-ships all 20 MB.
- **Buildpacks (`mvn spring-boot:build-image`) or Jib:** both produce layered images without a Dockerfile, but add tooling. The explicit Dockerfile keeps the dev/prod setup readable and consistent with the Go and Python ports.
- **Publishing the jar to a Maven repository:** useful for libraries, but pointless for a service that nobody depends on as a library.

## Consequences
- Every `main` commit produces immutable, SHA-tagged images, and every release a downloadable jar with a checksum. Rollback means redeploying an earlier tag.
- GHCR packages are created on the first push to `main`. For a public repo they may need their visibility set to public in the package settings before anonymous pulls work.
- The runtime layout differs slightly between `java -jar target/app.jar` (fat jar, `JarLauncher`, nested jars) and the image (thin jar + `lib/`, plain classpath). Both run the same classes, and the contract suite and JUnit cover the image and the jar respectively.
