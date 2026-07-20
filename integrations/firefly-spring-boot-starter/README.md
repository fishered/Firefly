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

Jobs can also be created at application startup:

```java
@Component
class BillingJobs {
    @FireflyJob(
            id = "billing-daily-job",
            cron = "0 0 2 * * *",
            zoneId = "Asia/Shanghai"
    )
    public void billingHandler(ExecutionContext context) {
        // business code
    }
}
```

The method name is the default handler name. A method may carry multiple
`@FireflyJob` annotations when the same handler needs multiple schedules.
Programmatic `FireflyJobRegistration` beans remain available for dynamic cases.

Configure `firefly.executor.job-registration.admin-url` when the Admin API is not
available at `http://127.0.0.1:9710`. Existing jobs are left unchanged by default;
set `update-existing=true` only when code should own the complete job definition.
