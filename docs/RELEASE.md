# Release and publication procedure

Token Pilot publishes seven library modules with one shared version:

- `token-pilot-core`
- `token-pilot-spring-ai`
- `token-pilot-micrometer`
- `token-pilot-budget`
- `token-pilot-notification`
- `token-pilot-autoconfigure`
- `token-pilot-starter`

The sample app is not a published module. The current user-facing Spring
artifact is `token-pilot-starter`; do not use the unpublished name
`token-pilot-spring-ai-starter`.

## Version policy

The default build version is `0.0.1-SNAPSHOT`. A release candidate is selected
explicitly:

```bash
./gradlew properties -PprojectVersion=0.1.0 | grep '^version:'
```

Do not describe `0.1.0` as published until a consumer resolves it from the
intended repository. Staging verification and Central production publication
are separate gates.

## Secretless verification

These checks should run in ordinary CI without signing or publication
credentials:

```bash
./gradlew test
./gradlew verifyPublicationMetadata verifyCompatibilityMatrix
./gradlew verifyCoreConsumer
./gradlew verifyMicrometerConsumer
./gradlew verifyPublishedIntegrationConsumer
```

The generated consumers are temporary external-style projects. They verify the
Core runtime boundary, optional Micrometer owners, the Spring AI adapter, and
the Starter with an explicitly selected provider.

## Local snapshot consumption

```bash
./gradlew publishToMavenLocal
```

Use `mavenLocal()` only for local verification. A consumer must still depend on
the published coordinates rather than using Gradle `project(...)` substitution
when validating publication metadata.

## Staging a release candidate

First validate the intended version and generated metadata:

```bash
./gradlew verifyPublicationMetadata \
  -PprojectVersion=0.1.0
```

Then create signed staging artifacts:

```bash
./gradlew publishAllPublicationsToStagingRepository \
  -PprojectVersion=0.1.0
```

The staging repository is module-local under each published module's
`build/staging-deploy` directory. The release build must contain binary,
sources, Javadoc, POM, and Gradle module metadata artifacts.

## Central Portal deployment

JReleaser targets the Central Publisher Portal namespace
`cloud.token-pilot`. Deployment requires credentials and signing material
provided outside the repository:

```bash
export CENTRAL_USERNAME="..."
export CENTRAL_PASSWORD="..."
export SIGNING_KEY_FILE="/secure/path/private-key.asc"
export SIGNING_PUBLIC_KEY_FILE="/secure/path/public-key.asc"
export SIGNING_PASSWORD="..."

./gradlew jreleaserDeploy -PprojectVersion=0.1.0
```

The following values are supported through Gradle properties or environment
variables:

| Purpose | Gradle property | Environment variable |
| --- | --- | --- |
| Central username | `centralUsername` | `CENTRAL_USERNAME` |
| Central password | `centralPassword` | `CENTRAL_PASSWORD` |
| Signing secret key file | `signingKeyFile` | `SIGNING_KEY_FILE` |
| Signing public key file | `signingPublicKeyFile` | `SIGNING_PUBLIC_KEY_FILE` |
| Signing passphrase | `signingPassword` | `SIGNING_PASSWORD` |
| GitHub release token | `githubToken` | `GITHUB_TOKEN` |

Never commit credentials, armored keys, or API keys. A non-snapshot release
requires signing material; a missing secret should fail clearly rather than
silently producing an unsigned release.

## GitHub Packages snapshots

Snapshots can be published separately when the repository credentials are
available:

```bash
./gradlew publish \
  -PmavenRepoUrl=https://maven.pkg.github.com/tokenpliot/tokenpilot \
  -PmavenRepoUsername="$GITHUB_ACTOR" \
  -PmavenRepoPassword="$GITHUB_TOKEN"
```

## Final consumption gate

Before announcing a release, verify both paths from clean external consumers:

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-core:0.1.0'
}
```

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-starter:0.1.0'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai:2.0.0'
}
```

The Core consumer must resolve without Spring, Spring AI, Micrometer, or
Reactor runtime dependencies. The Starter consumer must not receive a
provider implementation transitively from Token Pilot. Only after this gate
passes may `0.1.0` be described as released.
