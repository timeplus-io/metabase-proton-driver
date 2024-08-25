# Timeplus Proton Driver for Metabase

[Timeplus Proton](https://www.timeplus.com/) database driver for the [Metabase](https://metabase.com) ([GitHub](https://github.com/metabase/metabase)) business intelligence tool.

This repo is a forked from https://github.com/ClickHouse/metabase-clickhouse-driver with necessary revisions to better fit Timeplus Proton.

## Installation
If you are about to use Metabase for the first time: 

* install the required JDK version and start it with `java -jar metabase.jar` to start the app. This will create a `plugins/` folder in the current directory. 
* Stop the Java process with <kbd>Ctrl</kbd> + <kbd>C</kbd>, then copy the `proton.metabase-driver.jar` into the `plugins/` folder and restart the app.

Here's an example [(using Metabase v0.50.21 and Timeplus Proton driver 0.50.5)](#choosing-the-right-version):

```bash
export METABASE_VERSION=v0.50.21
export METABASE_PROTON_DRIVER_VERSION=v0.50.5

mkdir -p mb/plugins && cd mb
curl -o metabase.jar https://downloads.metabase.com/$METABASE_VERSION/metabase.jar
curl -L -o plugins/proton.metabase-driver.jar https://github.com/timeplus-io/metabase-proton-driver/releases/download/$METABASE_PROTON_DRIVER_VERSION/proton.metabase-driver.jar 
MB_PLUGINS_DIR=./plugins; java -jar metabase.jar
```

## Add a Database

1. Once you've started up Metabase, open http://localhost:3000 , go to "Admin settings" (top-right), then "Databases" tab and add a database and select "Timeplus Proton".
2. You'll need to provide the Host/Port. Default `localhost` and `8123` just work.

## Run Query
Please note when set to use port `8123`, Proton's query behavior will default to batch SQL querying, looking for the past data.

## Build from Source
The build process is largely based on https://github.com/databendcloud/metabase-databend-driver. 
(IMHO, Leiningen provides much better compiling error message than the built-in `clojure -X:build:drivers:build/driver`)

### Prerequisites

- [Leiningen](https://leiningen.org/)

### Steps

1. Clone and build Metabase dependency jar.

   ```shell
   git clone https://github.com/metabase/metabase
   cd metabase
   clojure -X:deps prep
   cd modules/drivers
   clojure -X:deps prep
   cd ../..
   clojure -T:build uberjar
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
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.timeplus.external -DartifactId=metabase-core -Dversion=0.50.21 -Dpackaging=jar -Dfile=metabase.jar
   ```

4. Build the jar (key steps to compile *.clj source code)

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. Let's assume we download `metabase.jar` from the [Metabase jar](https://www.metabase.com/docs/latest/operations-guide/running-the-metabase-jar-file.html) to `~/metabase/` and we built the project above. Copy the built jar(target/uberjar/proton.metabase-driver.jar) to the Metabase plugins folder and run Metabase from there!

   ```shell
   cd ~/metabase/
   java -jar metabase.jar
   ```

You should see a message on startup similar to:

```
2023-11-18 09:55:37,102 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :proton...
2023-11-18 09:55:37,102 INFO driver.impl :: Registered driver :proton (parents: [:sql-jdbc]) 🚚
```

## Choosing the Right Version

Starting with Metabase v0.50.0, ClickHouse adopted a new naming convention for driver releases. The new one is intended to reflect the Metabase version the driver is supposed to run on. 

For example, the driver version 1.**50.0** means that it should be used with Metabase v0.**50.x** or Metabase EE 1.**50.x** _only_, and it is _not guaranteed_ that this particular version of the driver can work with the previous or the following versions of Metabase.

We've adopted the same naming convention for the Timeplus Proton driver since our Metabase driver is a slightly modified version of the ClickHouse Metabase driver.

| Metabase Release | Driver Version |
|------------------|----------------|
| v0.47.8          | v0.0.3         |
| v0.50.21         | v0.50.5        |


