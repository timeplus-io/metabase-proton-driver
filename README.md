# Proton driver for Metabase

This repo is a forked from https://github.com/ClickHouse/metabase-clickhouse-driver with necessary revisions to better fit streaming processing and Proton.

## Add database

1. Once you've started up Metabase, open http://localhost:3000 , go to "Admin settings" (top-right), then "Databases" tab and add a database and select "Timeplus Proton".
2. You'll need to provide the Host/Port. Default localhost and 8123 just work.

## Run Query
Please note, by default Proton's query behavior is streaming SQL, looking for new data in the future and never ends. This can be considered as hang for Metabase. So please use `select .. from .. LIMIT 100` to stop the query at 100 events. Or use a historical query, such as `select .. from table(car_live_data)..`

### Build from source
The build process is largely based on https://github.com/databendcloud/metabase-databend-driver. (IMHO, Leiningen provides much better compiling error message than the built-in `clojure -X:build:drivers:build/driver`)

### Prerequisites

- [Leiningen](https://leiningen.org/)

## Steps

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
   lein pom
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=metabase-core -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=metabase.jar
   ```

4. Build the jar

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. Let's assume we download `metabase.jar` from the [Metabase jar](https://www.metabase.com/docs/latest/operations-guide/running-the-metabase-jar-file.html) to `~/metabase/` and we built the project above. Copy the built jar(proton.metabase-driver.jar) to the Metabase plugins directly and run Metabase from there!

   ```shell
   cd ~/metabase/
   java -jar metabase.jar
   ```

You should see a message on startup similar to:

```
2023-11-18 09:55:37,102 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :proton...
2023-11-18 09:55:37,102 INFO driver.impl :: Registered driver :proton (parents: [:sql-jdbc]) 🚚
```
