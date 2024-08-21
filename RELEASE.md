# Release
These are general instructions on how to adapt the changes from the upstream driver (ClickHouse) and how to build a new version of the driver against a new version of Metabase.

## Updating the Driver from Upstream
Updating the driver from the upstream repo ([ClickHouse](https://github.com/ClickHouse/metabase-clickhouse-driver)) is mostly a series of find & replace.

This version of the Timeplus Proton driver is based on [01ecc5fbcc53d007b9a7357bd358428a4f3bc738](https://github.com/ClickHouse/metabase-clickhouse-driver/commit/01ecc5fbcc53d007b9a7357bd358428a4f3bc738) of the upstream driver. 

A. Start by performing simple text replacements for:
  * `s/ClickHouse/Proton/g` -- proper nouns embedded in documentation and comments
  * `s/:clickhouse/:proton/g` -- keywords
  * `s/clickhouse/proton/g` -- symbols
 
 inside the following 4 files:
1. `src/metabase/driver/proton.clj`
2. `src/metabase/driver/proton_introspection.clj`
3. `src/metabase/driver/proton_version.clj`
4. `src/metabase/driver/proton_qp.clj`


B. The 4th file -- `src/metabase/driver/proton_qp.clj` -- requires additional text replacements.

   Simply run `replace.sh` inside the `scripts/` folder to complete the replacement of database function names from cameCase to kebab_case:
```bash
cd metabase-proton-driver/scripts/
./replace.sh ../src/metabase/driver/proton_qp.clj
```

C. Running the command below should catch any stray errors like unbalanced parentheses or syntax errors: 
```bash
lein check
```

## Building a New Driver Version
1. Visit Metabase's GitHub [releases page](https://github.com/metabase/metabase/releases) and make a note of the version of the _latest_ binary release (JAR file).
(The _latest_ JAR can also be downloaded from the Metabase [distribution page](https://metabase.com/start/jar.html)).

   As at the time of this writing, the latest version is [v0.50.20](https://github.com/metabase/metabase/releases/tag/v0.50.20).

2. Update the version number for Metabase in `project.clj`.
   
   Also remember to bump the driver's version number when building a new version in:
   * `project.clj` and
   * `resources/metabase-plugin.yaml`.

   Here's the diff for `project.clj`:   
```diff
1c1
< (defproject metabase/proton-driver "0.0.3"
---
> (defproject metabase/proton-driver "0.50.4"
20c20
<    {:dependencies [[metabase-core "1.40"]]}
---
>    {:dependencies [[com.timeplus.external/metabase-core "0.50.20"]]}
```

   Here's the diff for `resources/metabase-plugin.yaml`:
```diff
3c3
<   version: 0.0.3
---
>   version: 0.50.4
18c18
<         - default: 3218
---
>         - default: 8123
```

3. Save the desired version of Metabase you noted earlier in an environment variable. We will build a new driver against this version.
```bash
export METABASE_VERSION=0.50.20
```

4. Clone the Timeplus Proton driver locally:
```bash
git clone https://github.com/timeplus-io/metabase-proton-driver
```

5. Download the specified version of Metabase inside the Timeplus Proton driver folder:
```bash
cd metabase-proton-driver/
curl -o metabase.jar https://downloads.metabase.com/v$METABASE_VERSION/metabase.jar
```

6. Create a vendored copy of the Metabase jar you downloaded above. It will be added to the Maven classpath used for compilation:
```bash
mkdir repo
mvn deploy:deploy-file \
    -Durl=file:repo \
    -DgroupId=com.timeplus.external \
    -DartifactId=metabase-core \
    -Dversion=$METABASE_VERSION \
    -Dpackaging=jar \
    -Dfile=metabase.jar
```

7. Resolve all dependencies referenced in `project.clj`:
```bash
lein deps
# or use:
clojure -X:deps prep
```

8. Update your `pom.xml` to contain the bumped version and commit hash of the driver you are about to build:
```bash
lein pom
```

9. Build a new version of the Proton driver that will be written to `target/uberjar/proton.metabase-driver.jar`
```bash
LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
```

10. Copy the newly built Proton driver and other assets to a test folder (used for testing in the next section):
```bash
mkdir -p /tmp/metabase/plugins

# copy the newly built Proton driver to the plugins folder
cp target/uberjar/proton.metabase-driver.jar /tmp/metabase/plugins

# copy the Metabase jar since you've already downloaded it
cp metabase.jar /tmp/metabase/

# copy the test H2 database so you don't have to setup the dashboard afresh
cp scripts/metabase.db.mv.db /tmp/metabase/
```


## Testing the Newly Built Driver Manually
These commands will set up a running instance of Metabase with the newly built driver:

```bash
cd /tmp/metabase/

MB_PLUGINS_DIR=./plugins; java -Duser.timezone=GMT -jar metabase.jar
```

<details>
<summary>Example output from Metabase starting up (click to expand)</summary>
<pre>
 2024-08-21 00:16:58,413 INFO metabase.util :: Maximum memory available to JVM: 4.0 GB
2024-08-21 00:17:01,209 INFO util.encryption :: Saved credentials encryption is DISABLED for this Metabase instance. 🔓 
 For more information, see https://metabase.com/docs/latest/operations-guide/encrypting-database-details-at-rest.html
2024-08-21 00:17:02,208 WARN db.env :: WARNING: Using Metabase with an H2 application database is not recommended for production deployments. For production deployments, we highly recommend using Postgres, MySQL, or MariaDB instead. If you decide to continue to use H2, please be sure to back up the database file regularly. For more information, see https://metabase.com/docs/latest/operations-guide/migrating-from-h2.html
2024-08-21 00:17:07,577 INFO driver.impl :: Registered abstract driver :sql  🚚
2024-08-21 00:17:07,615 INFO driver.impl :: Registered abstract driver :sql-jdbc (parents: [:sql]) 🚚
2024-08-21 00:17:07,626 INFO metabase.util :: Load driver :sql-jdbc took 17.0 ms
2024-08-21 00:17:07,626 INFO driver.impl :: Registered driver :h2 (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:07,924 INFO driver.impl :: Registered driver :mysql (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:07,959 INFO driver.impl :: Registered driver :postgres (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:10,187 INFO metabase.core :: 
Metabase v0.50.20 (df82d58) 

Copyright © 2024 Metabase, Inc. 

Metabase Enterprise Edition extensions are NOT PRESENT.
2024-08-21 00:17:10,199 INFO metabase.core :: Starting Metabase in STANDALONE mode
2024-08-21 00:17:10,275 INFO metabase.server :: Launching Embedded Jetty Webserver with config:
 {:port 3000}

2024-08-21 00:17:10,342 INFO metabase.core :: Starting Metabase version v0.50.20 (df82d58) ...
2024-08-21 00:17:10,346 INFO metabase.core :: System info:
 {"file.encoding" "UTF-8",
 "java.runtime.name" "OpenJDK Runtime Environment",
 "java.runtime.version" "21.0.1+12-29",
 "java.vendor" "Oracle Corporation",
 "java.vendor.url" "https://java.oracle.com/",
 "java.version" "21.0.1",
 "java.vm.name" "OpenJDK 64-Bit Server VM",
 "java.vm.version" "21.0.1+12-29",
 "os.name" "Mac OS X",
 "os.version" "12.6",
 "user.language" "en",
 "user.timezone" "GMT"}

2024-08-21 00:17:10,349 INFO metabase.plugins :: Loading plugins in /private/tmp/metabase/plugins...
2024-08-21 00:17:10,489 INFO util.files :: Extract file /modules/sqlserver.metabase-driver.jar -> /private/tmp/metabase/plugins/sqlserver.metabase-driver.jar
2024-08-21 00:17:10,514 INFO util.files :: Extract file /modules/redshift.metabase-driver.jar -> /private/tmp/metabase/plugins/redshift.metabase-driver.jar
2024-08-21 00:17:10,526 INFO util.files :: Extract file /modules/snowflake.metabase-driver.jar -> /private/tmp/metabase/plugins/snowflake.metabase-driver.jar
2024-08-21 00:17:10,957 INFO util.files :: Extract file /modules/sqlite.metabase-driver.jar -> /private/tmp/metabase/plugins/sqlite.metabase-driver.jar
2024-08-21 00:17:11,025 INFO util.files :: Extract file /modules/athena.metabase-driver.jar -> /private/tmp/metabase/plugins/athena.metabase-driver.jar
2024-08-21 00:17:11,132 INFO util.files :: Extract file /modules/druid.metabase-driver.jar -> /private/tmp/metabase/plugins/druid.metabase-driver.jar
2024-08-21 00:17:11,143 INFO util.files :: Extract file /modules/mongo.metabase-driver.jar -> /private/tmp/metabase/plugins/mongo.metabase-driver.jar
2024-08-21 00:17:11,166 INFO util.files :: Extract file /modules/presto-jdbc.metabase-driver.jar -> /private/tmp/metabase/plugins/presto-jdbc.metabase-driver.jar
2024-08-21 00:17:11,238 INFO util.files :: Extract file /modules/vertica.metabase-driver.jar -> /private/tmp/metabase/plugins/vertica.metabase-driver.jar
2024-08-21 00:17:11,241 INFO util.files :: Extract file /modules/sparksql.metabase-driver.jar -> /private/tmp/metabase/plugins/sparksql.metabase-driver.jar
2024-08-21 00:17:11,321 INFO util.files :: Extract file /modules/druid-jdbc.metabase-driver.jar -> /private/tmp/metabase/plugins/druid-jdbc.metabase-driver.jar
2024-08-21 00:17:11,376 INFO util.files :: Extract file /modules/bigquery-cloud-sdk.metabase-driver.jar -> /private/tmp/metabase/plugins/bigquery-cloud-sdk.metabase-driver.jar
2024-08-21 00:17:11,682 INFO util.files :: Extract file /modules/oracle.metabase-driver.jar -> /private/tmp/metabase/plugins/oracle.metabase-driver.jar
2024-08-21 00:17:11,853 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :bigquery-cloud-sdk...
2024-08-21 00:17:11,853 INFO driver.impl :: Registered driver :bigquery-cloud-sdk (parents: [:sql]) 🚚
2024-08-21 00:17:11,879 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :snowflake...
2024-08-21 00:17:11,880 INFO driver.impl :: Registered driver :snowflake (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,887 INFO plugins.dependencies :: Metabase cannot initialize plugin Metabase Oracle Driver due to required dependencies. Metabase requires the Oracle JDBC driver in order to connect to Oracle databases, but we can't ship it as part of Metabase due to licensing restrictions. See https://metabase.com/docs/latest/administration-guide/databases/oracle.html for more details.

2024-08-21 00:17:11,888 INFO plugins.dependencies :: Metabase Oracle Driver dependency {:class oracle.jdbc.OracleDriver} satisfied? false
2024-08-21 00:17:11,889 INFO plugins.dependencies :: Plugins with unsatisfied deps: ["Metabase Oracle Driver"]
2024-08-21 00:17:11,898 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :mongo...
2024-08-21 00:17:11,899 INFO driver.impl :: Registered driver :mongo  🚚
2024-08-21 00:17:11,903 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :proton...
2024-08-21 00:17:11,903 INFO driver.impl :: Registered driver :proton (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,906 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :druid...
2024-08-21 00:17:11,907 INFO driver.impl :: Registered driver :druid  🚚
2024-08-21 00:17:11,910 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :redshift...
2024-08-21 00:17:11,911 INFO driver.impl :: Registered driver :redshift (parents: [:postgres]) 🚚
2024-08-21 00:17:11,913 INFO plugins.dependencies :: Metabase cannot initialize plugin Metabase Vertica Driver due to required dependencies. Metabase requires the Vertica JDBC driver in order to connect to Vertica databases, but we can't ship it as part of Metabase due to licensing restrictions. See https://metabase.com/docs/latest/administration-guide/databases/vertica.html for more details.

2024-08-21 00:17:11,914 INFO plugins.dependencies :: Metabase Vertica Driver dependency {:class com.vertica.jdbc.Driver} satisfied? false
2024-08-21 00:17:11,915 INFO plugins.dependencies :: Plugins with unsatisfied deps: ["Metabase Oracle Driver" "Metabase Vertica Driver"]
2024-08-21 00:17:11,917 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :sqlite...
2024-08-21 00:17:11,917 INFO driver.impl :: Registered driver :sqlite (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,926 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :presto-jdbc...
2024-08-21 00:17:11,926 INFO driver.impl :: Registered driver :presto-jdbc (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,931 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :druid-jdbc...
2024-08-21 00:17:11,932 INFO driver.impl :: Registered driver :druid-jdbc (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,935 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :sqlserver...
2024-08-21 00:17:11,935 INFO driver.impl :: Registered driver :sqlserver (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,943 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :athena...
2024-08-21 00:17:11,943 INFO driver.impl :: Registered driver :athena (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,949 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :hive-like...
2024-08-21 00:17:11,949 INFO driver.impl :: Registered abstract driver :hive-like (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:11,949 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :sparksql...
2024-08-21 00:17:11,950 INFO driver.impl :: Registered driver :sparksql (parents: [:hive-like]) 🚚
2024-08-21 00:17:11,954 INFO metabase.core :: Setting up and migrating Metabase DB. Please sit tight, this may take a minute...
2024-08-21 00:17:11,957 INFO db.setup :: Verifying h2 Database Connection ...
2024-08-21 00:17:12,495 INFO db.setup :: Successfully verified H2 2.1.214 (2022-06-13) application database connection. ✅
2024-08-21 00:17:12,496 INFO db.setup :: Checking if a database downgrade is required...
2024-08-21 00:17:13,116 INFO db.setup :: Running Database Migrations...
2024-08-21 00:17:13,117 INFO db.setup :: Setting up Liquibase...
2024-08-21 00:17:13,269 INFO db.setup :: Liquibase is ready.
2024-08-21 00:17:13,270 INFO db.liquibase :: Checking if Database has unrun migrations...
2024-08-21 00:17:13,648 INFO db.liquibase :: No unrun migrations found.
2024-08-21 00:17:13,648 INFO db.setup :: Database Migrations Current ... ✅
2024-08-21 00:17:13,649 INFO metabase.util :: Database setup took 1.7 s
2024-08-21 00:17:13,797 INFO driver.impl :: Initializing driver :sql...
2024-08-21 00:17:13,798 INFO driver.impl :: Initializing driver :sql-jdbc...
2024-08-21 00:17:13,798 INFO driver.impl :: Initializing driver :h2...
2024-08-21 00:17:13,897 INFO util.files :: Extract file /sample-database.db.mv.db -> /private/tmp/metabase/plugins/sample-database.db.mv.db
2024-08-21 00:17:14,040 INFO task.sync-databases :: A trigger for "Sync and Analyze" of Database "Sample Database" has been enabled with schedule: "0 51 * * * ? *"
2024-08-21 00:17:14,041 INFO task.sync-databases :: A trigger for "Scan Field Values" of Database "Sample Database" has been enabled with schedule: "0 0 8 * * ? *"
2024-08-21 00:17:14,078 INFO impl.StdSchedulerFactory :: Using default implementation for ThreadExecutor
2024-08-21 00:17:14,093 INFO core.SchedulerSignalerImpl :: Initialized Scheduler Signaller of type: class org.quartz.core.SchedulerSignalerImpl
2024-08-21 00:17:14,093 INFO core.QuartzScheduler :: Quartz Scheduler v.2.3.2 created.
2024-08-21 00:17:14,094 INFO jdbcjobstore.JobStoreTX :: Using db table-based data access locking (synchronization).
2024-08-21 00:17:14,096 INFO jdbcjobstore.JobStoreTX :: JobStoreTX initialized.
2024-08-21 00:17:14,097 INFO core.QuartzScheduler :: Scheduler meta-data: Quartz Scheduler (v2.3.2) 'MetabaseScheduler' with instanceId '13-inch-MacBook-Pro.local1724199434081'
  Scheduler class: 'org.quartz.core.QuartzScheduler' - running locally.
  NOT STARTED.
  Currently in standby mode.
  Number of jobs executed: 0
  Using thread pool 'org.quartz.simpl.SimpleThreadPool' - with 10 threads.
  Using job-store 'org.quartz.impl.jdbcjobstore.JobStoreTX' - which supports persistence. and is clustered.

2024-08-21 00:17:14,097 INFO impl.StdSchedulerFactory :: Quartz scheduler 'MetabaseScheduler' initialized from default resource file in Quartz package: 'quartz.properties'
2024-08-21 00:17:14,097 INFO impl.StdSchedulerFactory :: Quartz scheduler version: 2.3.2
2024-08-21 00:17:14,185 INFO core.QuartzScheduler :: Scheduler MetabaseScheduler_$_13-inch-MacBook-Pro.local1724199434081 paused.
2024-08-21 00:17:14,185 INFO metabase.task :: Task scheduler initialized into standby mode.
2024-08-21 00:17:14,238 INFO metabase.task :: Initializing task Cache 📆
2024-08-21 00:17:14,239 INFO metabase.task :: Initializing task SyncDatabases 📆
2024-08-21 00:17:14,259 INFO task.sync-databases :: Updated default schedules for 0 databases
2024-08-21 00:17:14,259 INFO metabase.task :: Initializing task PersistRefresh 📆
2024-08-21 00:17:14,269 INFO driver.impl :: Initializing driver :proton...
2024-08-21 00:17:14,270 INFO plugins.classloader :: Added URL file:/private/tmp/metabase/plugins/proton.metabase-driver.jar to classpath
2024-08-21 00:17:14,271 DEBUG plugins.init-steps :: Loading plugin namespace metabase.driver.proton...
2024-08-21 00:17:14,323 INFO driver.impl :: Registered driver :proton (parents: [:sql-jdbc]) 🚚
2024-08-21 00:17:14,347 DEBUG plugins.jdbc-proxy :: Registering JDBC proxy driver for com.timeplus.proton.jdbc.ProtonDriver...
2024-08-21 00:17:14,348 INFO metabase.util :: Load lazy loading driver :proton took 77.6 ms
2024-08-21 00:17:14,413 INFO metabase.task :: Initializing task CheckForNewVersions 📆
2024-08-21 00:17:14,428 INFO metabase.task :: Initializing task PersistPrune 📆
2024-08-21 00:17:14,431 INFO metabase.task :: Initializing task SendAnonymousUsageStats 📆
2024-08-21 00:17:14,439 INFO metabase.task :: Initializing task ModelIndexValues 📆
2024-08-21 00:17:14,441 INFO metabase.task :: Initializing task RefreshSlackChannelsAndUsers 📆
2024-08-21 00:17:14,452 INFO metabase.task :: Initializing task TruncateAuditTables 📆
2024-08-21 00:17:14,459 INFO metabase.task :: Initializing task SendPulses 📆
2024-08-21 00:17:14,464 INFO metabase.task :: Initializing task SendFollowUpEmails 📆
2024-08-21 00:17:14,472 INFO metabase.task :: Initializing task SendCreatorSentimentEmails 📆
2024-08-21 00:17:14,478 INFO metabase.task :: Initializing task SendLegacyNoSelfServiceEmail 📆
2024-08-21 00:17:14,479 INFO metabase.task :: Initializing task TaskHistoryCleanup 📆
2024-08-21 00:17:14,486 INFO metabase.task :: Initializing task SendWarnPulseRemovalEmail 📆
2024-08-21 00:17:14,491 INFO core.QuartzScheduler :: Scheduler MetabaseScheduler_$_13-inch-MacBook-Pro.local1724199434081 started.
2024-08-21 00:17:14,491 INFO metabase.task :: Task scheduler started
2024-08-21 00:17:14,491 INFO metabase.core :: Metabase Initialization COMPLETE in 21.6 s
2024-08-21 00:17:14,495 INFO jdbcjobstore.JobStoreTX :: Handling 3 trigger(s) that missed their scheduled fire-time.
2024-08-21 00:17:14,522 INFO task.refresh-slack-channel-user-cache :: Slack is not configured, not refreshing slack user/channel cache.
</pre>
</details>


Visit http://localhost:3000/ then login with:
* admin@example.com
* pa55w0rd!

