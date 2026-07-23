# Firefly Spring Boot Starter

This is the single user-facing Spring Boot integration artifact.

```xml
<dependency>
    <groupId>com.firefly</groupId>
    <artifactId>firefly-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure `firefly.executor.name` and annotate methods on regular Spring beans.
The transport client, handler discovery, job synchronization, reconnection,
heartbeats, and lifecycle are auto-configured.

```yaml
firefly:
  executor:
    name: billing-executor
    gateway-addresses:
      - 127.0.0.1:9700
    integration-key: ${FIREFLY_INTEGRATION_KEY}
```

Generate the Integration Key once from the Firefly Admin console. The same key
authenticates Gateway registration and optional startup job synchronization;
the Starter does not need a machine account, client ID, or client secret.

Jobs can also be created at application startup:

```java
@Component
class BillingJobs {
    @FireflyJob(
            cron = "0 0 2 * * *",
            zoneId = "Asia/Shanghai"
    )
    public void billingHandler(ExecutionContext context) {
        // business code
    }
}
```

The fully qualified class and method name, such as
`com.example.BillingJobs#billingHandler`, is the automatic entrypoint and job ID.
No global ID or handler name is required. A method may carry multiple
`@FireflyJob` annotations when the same entrypoint needs multiple schedules; in
that case each declaration must provide a unique local `key`.
Programmatic `FireflyJobRegistration` beans remain available for dynamic cases.

Configure `firefly.executor.job-registration.admin-url` when the Admin API is not
available at `http://127.0.0.1:9710`. Existing jobs are left unchanged by default;
set `update-existing=true` only when code should own the complete job definition.
