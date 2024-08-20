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

6. Set up dependencies
```bash
clojure -X:deps prep
```

7. Create a vendored copy of the Metabase jar you downloaded above. It will be added to the Maven classpath used for compilation:
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

8. Update your `pom.xml` to contain the bumped version and commit hash of the driver you are about to build:
```bash
lein pom
```

9. Build a new version of the Proton driver that will be written to `target/uberjar/proton.metabase-driver.jar`
```bash
LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
```

10. Copy the Proton driver and other assets to a test folder (used for testing in the next section):
```bash
mkdir -p /tmp/metabase/plugins
cp target/uberjar/proton.metabase-driver.jar /tmp/metabase/plugins
cp scripts/metabase.db.mv.db /tmp/metabase/
```


## Testing the Newly Built Driver Manually
These commands will set up a running instance of Metabase with the newly built driver:

```bash
cd /tmp
mkdir -p /tmp/metabase/plugins && cd /tmp/metabase/

export METABASE_VERSION=v0.50.20
curl -o metabase.jar https://downloads.metabase.com/$METABASE_VERSION/metabase.jar

# copy the newly built Proton driver to the plugins folder
cp metabase-proton-driver/target/uberjar/proton.metabase-driver.jar /tmp/metabase/plugins

# copy the test H2 database so you don't have to setup the dashboard afresh
cp metabase-proton-driver/scripts/metabase.db.mv.db /tmp/metabase/

MB_PLUGINS_DIR=./plugins; java -Duser.timezone=GMT -jar metabase.jar
```

Visit http://localhost:3000/ then login with:
* admin@example.com
* pa55w0rd!

