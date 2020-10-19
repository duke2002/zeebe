#!/bin/bash -eux


export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=$((LIMITS_CPU))"

tmpfile=$(mktemp)

mvn -o -B --fail-never -s ${MAVEN_SETTINGS_XML} verify -P skip-unstable-ci -pl upgrade-tests -DtestMavenId=4 -Dsurefire.rerunFailingTestsCount=7 | tee ${tmpfile}

status=${PIPESTATUS[0]}

if grep -q "\[WARNING\] Flakes:" ${tmpfile}; then

  tmpfile2=$(mktemp)

  awk '/^\[WARNING\] Flakes:.*$/{flag=1}/^\[ERROR\] Tests run:.*Flakes: [0-9]*$/{print;flag=0}flag' ${tmpfile} > ${tmpfile2}

  grep "\[ERROR\]   Run 1: " ${tmpfile} | awk '{print $4}' >> ./target/FlakyTests.txt

  echo ERROR: Flaky Tests detected>&2

  exit 1
fi

if [[ $status != 0 ]]; then
  exit $status;
fi

if grep -q "\[INFO\] Build failures were ignored\." ${tmpfile}; then
  exit 1
fi
