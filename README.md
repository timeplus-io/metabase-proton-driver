# Proton driver for Metabase

This repo is a forked from https://github.com/ClickHouse/metabase-clickhouse-driver with necessary revisions to better fit streaming processing and Proton.

## Configuring

1. Once you've started up Metabase, open http://localhost:3000 , go to add a database and select "proton".
2. You'll need to provide the Host/Port,  Database Name, Username and Password.

### Build from source

### Prerequisites

- [Leiningen](https://leiningen.org/)


1. Clone and build metabase dependency jar.

   ```shell
   git clone https://github.com/metabase/metabase
   cd metabase
   clojure -X:deps prep
   cd modules/drivers
   clojure -X:deps prep
   cd ../..
   ./bin/build.sh
   ```

2. Clone metabase-proton-driver repo

   ```shell
   cd modules/drivers
   git clone https://github.com/timeplus-io/metabase-proton-driver
   ```

3. Prepare metabase dependencies

   ```shell
   cp ../../target/uberjar/metabase.jar metabase-proton-driver/
   cd metabase-proton-driver
   mkdir repo
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.timeplus.proton -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=metabase.jar
   ```

4. Build the jar

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. Let's assume we download `metabase.jar` from the [Metabase jar](https://www.metabase.com/docs/latest/operations-guide/running-the-metabase-jar-file.html) to `~/metabase/` and we built the project above. Copy the built jar to the Metabase plugins directly and run Metabase from there!

   ```shell
   cd ~/metabase/
   java -jar metabase.jar
   ```

You should see a message on startup similar to:

```
2019-05-07 23:27:32 INFO plugins.lazy-loaded-driver :: Registering lazy loading driver :proton...
2019-05-07 23:27:32 INFO metabase.driver :: Registered driver :proton (parents: #{:sql-jdbc}) 🚚
```