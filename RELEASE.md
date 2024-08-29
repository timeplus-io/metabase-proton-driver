# Release
These are general instructions on how to adapt the changes from the upstream driver (ClickHouse) and how to build a new version of the driver against a new version of Metabase.

## Updating the Driver from Upstream
Updating the driver from the upstream repo ([ClickHouse](https://github.com/ClickHouse/metabase-clickhouse-driver)) is mostly a series of find & replace.

This version of the Timeplus driver is based on [01ecc5fbcc53d007b9a7357bd358428a4f3bc738](https://github.com/ClickHouse/metabase-clickhouse-driver/commit/01ecc5fbcc53d007b9a7357bd358428a4f3bc738) of the upstream driver.

There are a total of 4 files that need to be updated:
1. `src/metabase/driver/proton.clj`
2. `src/metabase/driver/proton_introspection.clj`
3. `src/metabase/driver/proton_version.clj`
4. `src/metabase/driver/proton_qp.clj`

A. Start by performing simple text replacements for:
  * `s/ClickHouse/Proton/g` -- proper nouns embedded in documentation and comments
  * `s/:clickhouse/:proton/g` -- keywords
  * `s/clickhouse/proton/g` -- symbols

B. The 4th file -- `src/metabase/driver/proton_qp.clj` -- requires additional text replacements.

   Simply run `replace.sh` inside the `scripts/` folder to complete the replacement of database function names from cameCase to kebab_case:
```bash
cd metabase-proton-driver/scripts/
./replace.sh ../src/metabase/driver/proton_qp.clj
```

C. Note that `src/metabase/driver/proton.clj` and `src/metabase/driver/proton_qp.clj` now hard-code a minimum supported version for Timeplus. It's currently set to v1.5 in both files.
* `src/metabase/driver/proton.clj` L220
* `src/metabase/driver/proton_qp.clj` L391-L398


D. Running the command below should catch any stray errors like unbalanced parentheses or syntax errors: 
```bash
lein check
```

## Building a New Driver Version
0. Please use JDK 11, as JDK 17 or above are not offically supported by Metabase ([source](https://github.com/metabase/metabase/issues/47207#issuecomment-2309931552)), e.g.`sdk use java 11.0.24-tem`
1. Visit Metabase's GitHub [releases page](https://github.com/metabase/metabase/releases) and make a note of the version of the _latest_ binary release (JAR file).
(The _latest_ JAR can also be downloaded from the Metabase [distribution page](https://metabase.com/start/jar.html)).

   As at the time of this writing, the latest version is [v0.50.21](https://github.com/metabase/metabase/releases/tag/v0.50.21).

2. Update the version number for Metabase in `project.clj`.
   
   Also remember to bump the driver's version number when building a new version in:
   * `project.clj` and
   * `resources/metabase-plugin.yaml`.

   Here's the highlight for `project.clj`:
```
 (defproject metabase/proton-driver "0.50.5"
   {:dependencies [[com.timeplus.external/metabase-core "0.50.21"]]}
```

   Here's the highlight for `resources/metabase-plugin.yaml`:
```
   version: 0.50.5
         - default: 8123
```

3. Save the desired version of Metabase you noted earlier in an environment variable. We will build a new driver against this version.
```bash
export METABASE_VERSION=0.50.21
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

8. Don't edit the `pom.xml` manually. Instead, update the `pom.xml` to contain the bumped version and commit hash of the driver, via:
```bash
lein pom
```

9. Build a new version of the Timeplus driver that will be written to `target/uberjar/proton.metabase-driver.jar`
```bash
LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
```

10. Copy the newly built Timeplus driver and other assets to a test folder (used for testing in the next section):
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

Visit http://localhost:3000/ then login with:
* admin@example.com
* pa55w0rd!

