# Release
These are general instructions on how to adapt the changes from the upstream driver (ClickHouse) and how to build a new version of the driver against a new version of Metabase.

## Building
1. Visit Metabase's GitHub [releases page](https://github.com/metabase/metabase/releases) and make a note of the version of the _latest_ binary release (JAR file).
(The _latest_ JAR can also be downloaded from the Metabase [distribution page](https://metabase.com/start/jar.html)).

   As at the time of this writing, the latest version is [v0.50.20](https://github.com/metabase/metabase/releases/tag/v0.50.20).

2. Update the version number for Metabase in `project.clj`. Also remember to bump the driver's version number when building a new version:
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

7. Create a vendored copy of the Metabase jar you downloaded above:
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

8. Update your pom.xml to contain the version and commit of the driver you are about to build:
```bash
lein pom
```

9. Build a new version of the Proton driver that will be written to `target/uberjar/proton.metabase-driver.jar`
```bash
LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
```

10. Copy the Proton driver to the folder we will use for testing in the next section:
```bash
mkdir -p /tmp/metabase/plugins
cp target/uberjar/proton.metabase-driver.jar /tmp/metabase/plugins
```


## Testing 
These commands will set up a running instance of Metabase with the newly built driver:

```bash
cd /tmp
mkdir -p /tmp/metabase/plugins && cd /tmp/metabase/

export METABASE_VERSION=v0.50.20
curl -o metabase.jar https://downloads.metabase.com/$METABASE_VERSION/metabase.jar

cp metabase-proton-driver/target/uberjar/proton.metabase-driver.jar /tmp/metabase/plugins
MB_PLUGINS_DIR=./plugins; java -Duser.timezone=GMT -jar metabase.jar
```

